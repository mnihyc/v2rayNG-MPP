package com.v2ray.ang.mpp

import java.math.BigInteger
import java.net.InetAddress

/** The two native MPTUNNEL carrier transports. */
enum class MppPathUnderlay {
    TCP,
    QUIC,
}

/** One explicitly-valued query item in its original order. */
data class MppPathQueryOption(
    val key: String,
    val value: String,
)

/** Parsed native carrier endpoint data useful to the editor, summaries, and endpoint probing. */
data class MppParsedPath(
    val underlay: MppPathUnderlay,
    val host: String,
    val firstPort: Int,
    val lastPort: Int,
    val options: List<MppPathQueryOption>,
    val tcpCarrierMax: Int,
) {
    val isPortRange: Boolean
        get() = firstPort != lastPort

    val carrierSlots: Int
        get() = if (underlay == MppPathUnderlay.TCP) tcpCarrierMax else 1
}

/** Strict Android mirror of MPTUNNEL's current client `PathSpec` text grammar. */
object MppPathParser {
    const val DEFAULT_TCP_CARRIER_MAX = 3
    const val DEFAULT_PORT_ROTATION_INTERVAL_MS = 300_000L

    val QUERY_KEYS: List<String> = listOf(
        "source-address",
        "initial-srtt-ms",
        "initial-rttvar-ms",
        "initial-rate-bps",
        "initial-rate-kbps",
        "initial-rate-mbps",
        "initial-rate",
        "max-datagram-payload-bytes",
        "max-tcp-carriers",
        "port-rotation-interval-ms",
        "backup",
        "expensive",
        "allow-bulk",
        "control-only",
        "allow-datagrams",
    )

    private const val MIN_PORT_ROTATION_INTERVAL_MS = 5_000L
    private val U16_MAX = BigInteger.valueOf(65_535)
    private val U32_MAX = BigInteger("4294967295")
    private val U64_MAX = BigInteger("18446744073709551615")
    private val policyId = Regex("[a-z0-9][a-z0-9._-]{0,63}")
    private val canonicalPort = Regex("[1-9][0-9]*")
    private val unsignedInteger = Regex("\\+?[0-9]+")

    fun isCanonicalName(name: String): Boolean = policyId.matches(name)

    fun parse(endpoint: String): MppParsedPath? {
        val schemeEnd = endpoint.indexOf("://")
        if (schemeEnd < 0) return null
        val underlay = when (endpoint.substring(0, schemeEnd)) {
            "tcp" -> MppPathUnderlay.TCP
            "quic" -> MppPathUnderlay.QUIC
            else -> return null
        }
        val path = endpoint.substring(schemeEnd + 3)
        val queryStart = path.indexOf('?')
        val authority = if (queryStart >= 0) path.substring(0, queryStart) else path
        val query = if (queryStart >= 0) path.substring(queryStart + 1) else null
        val parsedAuthority = parseAuthority(authority) ?: return null
        val options = parseOptions(query) ?: return null

        val seen = HashSet<String>(options.size)
        var rateSeen = false
        var tcpCarrierMax = DEFAULT_TCP_CARRIER_MAX
        var maxTcpCarriersPresent = false
        var maxDatagramPayloadPresent = false
        var portRotationPresent = false
        var allowDatagramsPresent = false

        for (option in options) {
            val key = option.key
            val value = option.value
            if (!seen.add(key)) return null
            when (key) {
                "source-address" -> if (!isIpAddress(value)) return null
                "initial-srtt-ms" -> {
                    val parsed = parseUnsigned(value, U32_MAX) ?: return null
                    if (parsed == BigInteger.ZERO) return null
                }
                "initial-rttvar-ms" -> if (parseUnsigned(value, U32_MAX) == null) return null
                "initial-rate-bps", "initial-rate-kbps", "initial-rate-mbps" -> {
                    if (rateSeen) return null
                    rateSeen = true
                    val parsed = parseUnsigned(value, U64_MAX) ?: return null
                    if (parsed == BigInteger.ZERO) return null
                    val multiplier = when (key) {
                        "initial-rate-kbps" -> BigInteger.valueOf(1_000)
                        "initial-rate-mbps" -> BigInteger.valueOf(1_000_000)
                        else -> BigInteger.ONE
                    }
                    if (parsed.multiply(multiplier) > U64_MAX) return null
                }
                "initial-rate" -> {
                    if (rateSeen || value !in setOf("unknown", "unlimited")) return null
                    rateSeen = true
                }
                "max-datagram-payload-bytes" -> {
                    val parsed = parseUnsigned(value, U16_MAX)?.toInt() ?: return null
                    if (parsed !in 512..65_000) return null
                    maxDatagramPayloadPresent = true
                }
                "max-tcp-carriers" -> {
                    val parsed = parseUnsigned(value, U16_MAX)?.toInt() ?: return null
                    if (parsed == 0) return null
                    tcpCarrierMax = parsed
                    maxTcpCarriersPresent = true
                }
                "port-rotation-interval-ms" -> {
                    val parsed = parseUnsigned(value, U32_MAX)?.toLong() ?: return null
                    if (parsed < MIN_PORT_ROTATION_INTERVAL_MS) return null
                    portRotationPresent = true
                }
                "backup", "expensive", "allow-bulk", "control-only", "allow-datagrams" -> {
                    if (value != "true" && value != "false") return null
                    if (key == "allow-datagrams") allowDatagramsPresent = true
                }
                else -> return null
            }
        }

        if (underlay == MppPathUnderlay.QUIC && (maxTcpCarriersPresent || allowDatagramsPresent)) {
            return null
        }
        if (underlay == MppPathUnderlay.TCP && maxDatagramPayloadPresent) return null
        if (portRotationPresent && parsedAuthority.firstPort == parsedAuthority.lastPort) return null
        return MppParsedPath(
            underlay = underlay,
            host = parsedAuthority.host,
            firstPort = parsedAuthority.firstPort,
            lastPort = parsedAuthority.lastPort,
            options = options,
            tcpCarrierMax = tcpCarrierMax,
        )
    }

    private fun parseAuthority(value: String): ParsedAuthority? {
        if (value.isEmpty() || value.trim() != value) return null
        val host: String
        val ports: String
        if (value.startsWith('[')) {
            val close = value.indexOf(']')
            if (close < 0 || close + 1 >= value.length || value[close + 1] != ':') return null
            host = value.substring(1, close)
            if (!isIpv6Address(host)) return null
            ports = value.substring(close + 2)
        } else {
            val colon = value.lastIndexOf(':')
            if (colon < 0) return null
            host = value.substring(0, colon)
            ports = value.substring(colon + 1)
            if (host.isEmpty() || ':' in host || '[' in host || ']' in host ||
                host.any(Char::isWhitespace)
            ) {
                return null
            }
        }

        val dash = ports.indexOf('-')
        if (dash < 0) {
            val port = parsePort(ports) ?: return null
            return ParsedAuthority(host, port, port)
        }
        if (dash == 0 || dash == ports.lastIndex || ports.indexOf('-', dash + 1) >= 0) return null
        val first = parsePort(ports.substring(0, dash)) ?: return null
        val last = parsePort(ports.substring(dash + 1)) ?: return null
        if (first >= last) return null
        return ParsedAuthority(host, first, last)
    }

    private fun parseOptions(query: String?): List<MppPathQueryOption>? {
        if (query == null) return emptyList()
        if (query.isEmpty()) return null
        return query.split('&').map { part ->
            if (part.isEmpty()) return null
            val equals = part.indexOf('=')
            if (equals <= 0 || equals == part.lastIndex) return null
            MppPathQueryOption(part.substring(0, equals), part.substring(equals + 1))
        }
    }

    private fun parsePort(value: String): Int? {
        if (!canonicalPort.matches(value)) return null
        return value.toIntOrNull()?.takeIf { it in 1..65_535 }
    }

    private fun parseUnsigned(value: String, maximum: BigInteger): BigInteger? {
        if (!unsignedInteger.matches(value)) return null
        return runCatching { BigInteger(value) }.getOrNull()?.takeIf { it <= maximum }
    }

    private fun isIpAddress(value: String): Boolean =
        if (':' in value) isIpv6Address(value) else isIpv4Address(value)

    private fun isIpv4Address(value: String): Boolean {
        val octets = value.split('.')
        return octets.size == 4 && octets.all { octet ->
            octet.isNotEmpty() && octet.all { it in '0'..'9' } &&
                    (octet == "0" || !octet.startsWith('0')) &&
                    octet.toIntOrNull()?.let { it in 0..255 } == true
        }
    }

    private fun isIpv6Address(value: String): Boolean {
        if (value.isEmpty() || ':' !in value || '%' in value || '[' in value || ']' in value) {
            return false
        }
        return runCatching { InetAddress.getByName(value) }.isSuccess
    }

    private data class ParsedAuthority(
        val host: String,
        val firstPort: Int,
        val lastPort: Int,
    )
}
