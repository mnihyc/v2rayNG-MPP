package com.v2ray.ang.mpp

import java.math.BigInteger
import java.net.Inet6Address
import java.net.InetAddress

/** The two native MPTUNNEL carrier transports. UDP endpoints are QUIC carriers. */
enum class MppPathUnderlay {
    TCP,
    UDP,
}

/** One query item in its original order. Values are not URL-decoded by MPTUNNEL. */
data class MppPathQueryOption(
    val key: String,
    val value: String?,
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

/** Strict parser for the current native `PathSpec` text grammar. */
object MppPathParser {
    private const val DEFAULT_TCP_CARRIER_MAX = 3
    private const val MIN_PORT_HOP_INTERVAL_MS = 5_000L
    private val U16_MAX = BigInteger.valueOf(65_535)
    private val U32_MAX = BigInteger("4294967295")
    private val U64_MAX = BigInteger("18446744073709551615")
    private val policyId = Regex("[a-z0-9][a-z0-9._-]{0,63}")

    fun isCanonicalName(name: String): Boolean = policyId.matches(name)

    fun parse(endpoint: String): MppParsedPath? {
        val schemeEnd = endpoint.indexOf("://")
        if (schemeEnd < 0) return null
        val underlay = when (endpoint.substring(0, schemeEnd)) {
            "tcp" -> MppPathUnderlay.TCP
            "udp" -> MppPathUnderlay.UDP
            else -> return null
        }
        val path = endpoint.substring(schemeEnd + 3)
        val queryStart = path.indexOf('?')
        val authority = (if (queryStart >= 0) path.substring(0, queryStart) else path).trim()
        val query = if (queryStart >= 0) path.substring(queryStart + 1) else null
        val parsedAuthority = parseAuthority(authority) ?: return null
        val options = parseOptions(query) ?: return null

        val scalarKeys = hashSetOf<String>()
        var rateSeen = false
        var tcpCarrierMax = DEFAULT_TCP_CARRIER_MAX
        var tcpCarriersPresent = false
        var hopIntervalPresent = false
        var noUdp = false

        for (option in options) {
            val key = option.key
            val value = option.value
            when (key) {
                "source-ip" -> {
                    if (!scalarKeys.add(key) || value == null || !isIpAddress(value)) return null
                }
                "srtt-ms", "jitter-ms" -> {
                    if (!scalarKeys.add(key) || parseUnsigned(value, U32_MAX) == null) return null
                }
                "rate-bps", "rate-kbps", "rate-mbps" -> {
                    if (rateSeen) return null
                    rateSeen = true
                    val parsed = parseUnsigned(value, U64_MAX) ?: return null
                    val multiplier = when (key) {
                        "rate-kbps" -> BigInteger.valueOf(1_000)
                        "rate-mbps" -> BigInteger.valueOf(1_000_000)
                        else -> BigInteger.ONE
                    }
                    if (parsed.multiply(multiplier) > U64_MAX) return null
                }
                "rate" -> {
                    if (rateSeen || value !in setOf("unknown", "unlimited")) return null
                    rateSeen = true
                }
                "datagram-payload-limit" -> {
                    if (!scalarKeys.add(key)) return null
                    val parsed = parseUnsigned(value, U16_MAX)?.toInt() ?: return null
                    if (parsed !in 512..65_000) return null
                }
                "tcp-carriers" -> {
                    if (!scalarKeys.add(key)) return null
                    tcpCarrierMax = parseTcpCarrierMax(value) ?: return null
                    tcpCarriersPresent = true
                }
                "port-hop-interval-ms" -> {
                    if (!scalarKeys.add(key)) return null
                    val parsed = parseUnsigned(value, U32_MAX)?.toLong() ?: return null
                    if (parsed < MIN_PORT_HOP_INTERVAL_MS) return null
                    hopIntervalPresent = true
                }
                "backup", "expensive", "bulk-allowed", "probe-only", "no-udp" -> {
                    val parsed = parseBooleanFlag(value) ?: return null
                    if (key == "no-udp") noUdp = parsed
                }
                else -> return null
            }
        }

        if (underlay == MppPathUnderlay.UDP && (tcpCarriersPresent || noUdp)) return null
        if (hopIntervalPresent && parsedAuthority.firstPort == parsedAuthority.lastPort) return null
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
        if (value.isEmpty()) return null
        val host: String
        val ports: String
        if (value.startsWith('[')) {
            val close = value.indexOf(']')
            if (close < 0 || close + 1 >= value.length || value[close + 1] != ':') return null
            host = value.substring(1, close)
            ports = value.substring(close + 2)
        } else {
            val colon = value.lastIndexOf(':')
            if (colon < 0) return null
            host = value.substring(0, colon)
            ports = value.substring(colon + 1)
            if (':' in host) return null
        }
        if (host.isEmpty()) return null

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
            if (equals < 0) {
                MppPathQueryOption(part, null)
            } else {
                MppPathQueryOption(part.substring(0, equals), part.substring(equals + 1))
            }
        }
    }

    private fun parsePort(value: String): Int? =
        value.toIntOrNull()?.takeIf { it in 1..65_535 }

    private fun parseTcpCarrierMax(value: String?): Int? {
        if (value == null) return null
        val dash = value.indexOf('-')
        if (dash <= 0 || dash == value.lastIndex || value.indexOf('-', dash + 1) >= 0) return null
        val minimum = parsePort(value.substring(0, dash)) ?: return null
        val maximum = parsePort(value.substring(dash + 1)) ?: return null
        return maximum.takeIf { minimum <= maximum }
    }

    private fun parseUnsigned(value: String?, maximum: BigInteger): BigInteger? {
        if (value == null || !Regex("\\+?[0-9]+").matches(value)) return null
        return runCatching { BigInteger(value) }.getOrNull()?.takeIf { it <= maximum }
    }

    private fun parseBooleanFlag(value: String?): Boolean? = when (value ?: "true") {
        "true" -> true
        "false" -> false
        else -> null
    }

    private fun isIpAddress(value: String): Boolean {
        if (value.isEmpty() || '%' in value || '[' in value || ']' in value) return false
        if (':' in value) {
            return runCatching { InetAddress.getByName(value) is Inet6Address }.getOrDefault(false)
        }
        val octets = value.split('.')
        return octets.size == 4 && octets.all { octet ->
            octet.isNotEmpty() && octet.all { it in '0'..'9' } &&
                    (octet == "0" || !octet.startsWith('0')) &&
                    octet.toIntOrNull()?.let { it in 0..255 } == true
        }
    }

    private data class ParsedAuthority(
        val host: String,
        val firstPort: Int,
        val lastPort: Int,
    )
}
