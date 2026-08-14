package com.v2ray.ang.ui.server

import com.v2ray.ang.mpp.MppPathParser
import com.v2ray.ang.mpp.MppPathUnderlay

/** A query item in the order and spelling accepted by [MppPathParser]. */
internal data class MppEditableEndpointOption(
    val key: String,
    val value: String?,
) {
    fun render(): String = if (value == null) key else "$key=$value"
}

/** The editable portions of a native MPTUNNEL carrier endpoint. */
internal data class MppEditableEndpoint(
    val underlay: MppPathUnderlay,
    val host: String,
    val ports: String,
    val options: List<MppEditableEndpointOption>,
)

/**
 * Loss-minimising edits for the structured path controls.
 *
 * Every rewrite first requires the source to pass [MppPathParser]. A rewritten target is not
 * parsed again: text fields may therefore expose an intermediate, invalid host, port, or value
 * without this helper silently reverting the user's edit.
 */
internal object MppEndpointUriEditor {
    private val RATE_OPTION_KEYS = setOf(
        "initial-rate",
        "initial-rate-bps",
        "initial-rate-kbps",
        "initial-rate-mbps",
    )
    private val BOOLEAN_OPTION_KEYS = setOf(
        "backup",
        "expensive",
        "allow-bulk",
        "control-only",
        "allow-datagrams",
    )
    private val KNOWN_OPTION_KEYS = MppPathParser.QUERY_KEYS

    fun parse(uri: String): MppEditableEndpoint? {
        val parsed = MppPathParser.parse(uri) ?: return null
        val editable = parseDraft(uri) ?: return null
        return editable.copy(
            underlay = parsed.underlay,
            host = parsed.host,
            options = parsed.options.map { option ->
                MppEditableEndpointOption(option.key, option.value)
            },
        )
    }

    /**
     * Parse only the editable URI structure and known option names.
     *
     * Unlike [parse], this deliberately does not validate hosts, port values/ranges, option values,
     * duplicates, or transport cross-field rules. It lets a control continue editing text that a
     * prior rewrite made temporarily invalid. Callers must opt in to that behaviour on rewrites.
     */
    fun parseDraft(uri: String): MppEditableEndpoint? {
        val schemeEnd = uri.indexOf("://")
        if (schemeEnd < 0) return null
        val underlay = when (uri.substring(0, schemeEnd)) {
            "tcp" -> MppPathUnderlay.TCP
            "quic" -> MppPathUnderlay.QUIC
            else -> return null
        }
        val path = uri.substring(schemeEnd + 3)
        val queryStart = path.indexOf('?')
        val authority = if (queryStart >= 0) path.substring(0, queryStart) else path
        val query = if (queryStart >= 0) path.substring(queryStart + 1) else null

        val host: String
        val ports: String
        if (authority.startsWith('[')) {
            val close = authority.indexOf(']')
            if (close < 0 || close + 1 >= authority.length || authority[close + 1] != ':') {
                return null
            }
            host = authority.substring(1, close)
            ports = authority.substring(close + 2)
        } else {
            val colon = authority.lastIndexOf(':')
            if (colon < 0) return null
            host = authority.substring(0, colon)
            if (':' in host) return null
            ports = authority.substring(colon + 1)
        }

        val options = when {
            query == null -> emptyList()
            query.isEmpty() -> return null
            else -> query.split('&').map { part ->
                if (part.isEmpty()) return null
                val equals = part.indexOf('=')
                val key = if (equals < 0) part else part.substring(0, equals)
                if (key !in KNOWN_OPTION_KEYS) return null
                MppEditableEndpointOption(
                    key = key,
                    value = if (equals < 0) null else part.substring(equals + 1),
                )
            }
        }

        return MppEditableEndpoint(
            underlay = underlay,
            host = host,
            ports = ports,
            options = options,
        )
    }

    fun render(endpoint: MppEditableEndpoint): String {
        val scheme = when (endpoint.underlay) {
            MppPathUnderlay.TCP -> "tcp"
            MppPathUnderlay.QUIC -> "quic"
        }
        val authorityHost = when {
            endpoint.host.startsWith('[') && endpoint.host.endsWith(']') -> endpoint.host
            ':' in endpoint.host -> "[${endpoint.host}]"
            else -> endpoint.host
        }
        val query = endpoint.options
            .takeIf { it.isNotEmpty() }
            ?.joinToString(separator = "&", prefix = "?") { it.render() }
            .orEmpty()
        return "$scheme://$authorityHost:${endpoint.ports}$query"
    }

    fun withUnderlay(
        uri: String,
        underlay: MppPathUnderlay,
        allowDraftSource: Boolean = false,
    ): String? {
        val endpoint = parseSource(uri, allowDraftSource) ?: return null
        if (endpoint.underlay == underlay) return uri
        val incompatibleKeys = when (underlay) {
            MppPathUnderlay.TCP -> setOf("max-datagram-payload-bytes")
            MppPathUnderlay.QUIC -> setOf("max-tcp-carriers", "allow-datagrams")
        }
        val options = endpoint.options.filterNot { it.key in incompatibleKeys }
        return render(endpoint.copy(underlay = underlay, options = options))
    }

    fun withHost(uri: String, host: String, allowDraftSource: Boolean = false): String? {
        val endpoint = parseSource(uri, allowDraftSource) ?: return null
        if (endpoint.host == host) return uri
        return render(endpoint.copy(host = host))
    }

    fun withPorts(uri: String, ports: String, allowDraftSource: Boolean = false): String? {
        val endpoint = parseSource(uri, allowDraftSource) ?: return null
        if (endpoint.ports == ports) return uri
        return render(endpoint.copy(ports = ports))
    }

    fun withScalarOption(
        uri: String,
        key: String,
        valueOrNull: String?,
        allowDraftSource: Boolean = false,
    ): String? {
        require(key in KNOWN_OPTION_KEYS && key !in RATE_OPTION_KEYS && key !in BOOLEAN_OPTION_KEYS) {
            "Unsupported scalar option: $key"
        }
        val endpoint = parseSource(uri, allowDraftSource) ?: return null
        if (valueOrNull != null && !scalarOptionApplies(endpoint, key)) return null
        val replacement = valueOrNull?.let { MppEditableEndpointOption(key, it) }
        return rewriteOptions(uri, endpoint, setOf(key), replacement)
    }

    fun withBooleanOption(
        uri: String,
        key: String,
        enabled: Boolean,
        allowDraftSource: Boolean = false,
    ): String? {
        require(key in BOOLEAN_OPTION_KEYS) { "Unsupported Boolean option: $key" }
        val endpoint = parseSource(uri, allowDraftSource) ?: return null
        if (key == "allow-datagrams" && endpoint.underlay != MppPathUnderlay.TCP) return null
        val replacement = MppEditableEndpointOption(key, enabled.toString())
        return rewriteOptions(uri, endpoint, setOf(key), replacement)
    }

    fun withRateOption(
        uri: String,
        keyOrNull: String?,
        valueOrNull: String?,
        allowDraftSource: Boolean = false,
    ): String? {
        val endpoint = parseSource(uri, allowDraftSource) ?: return null
        require(keyOrNull == null || keyOrNull in RATE_OPTION_KEYS) {
            "Unsupported rate option: $keyOrNull"
        }
        val replacement = if (keyOrNull != null && valueOrNull != null) {
            MppEditableEndpointOption(keyOrNull, valueOrNull)
        } else {
            null
        }
        return rewriteOptions(uri, endpoint, RATE_OPTION_KEYS, replacement)
    }

    private fun parseSource(uri: String, allowDraftSource: Boolean): MppEditableEndpoint? =
        parse(uri) ?: if (allowDraftSource) parseDraft(uri) else null

    private fun scalarOptionApplies(endpoint: MppEditableEndpoint, key: String): Boolean =
        when (key) {
            "max-datagram-payload-bytes" -> endpoint.underlay == MppPathUnderlay.QUIC
            "max-tcp-carriers" -> endpoint.underlay == MppPathUnderlay.TCP
            "port-rotation-interval-ms" -> '-' in endpoint.ports
            else -> true
        }

    /** Replace a logical option/group at its first position, dropping any later group members. */
    private fun rewriteOptions(
        sourceUri: String,
        endpoint: MppEditableEndpoint,
        targetKeys: Set<String>,
        replacement: MppEditableEndpointOption?,
    ): String {
        val firstTarget = endpoint.options.indexOfFirst { it.key in targetKeys }
        val rewritten = when {
            firstTarget < 0 && replacement == null -> return sourceUri
            firstTarget < 0 -> endpoint.options + replacement!!
            else -> buildList {
                endpoint.options.forEachIndexed { index, option ->
                    when {
                        index == firstTarget && replacement != null -> add(replacement)
                        option.key !in targetKeys -> add(option)
                    }
                }
            }
        }
        if (rewritten == endpoint.options) return sourceUri
        return render(endpoint.copy(options = rewritten))
    }
}
