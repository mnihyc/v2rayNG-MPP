package com.v2ray.ang.mpp

import com.v2ray.ang.dto.entities.MppProfileConfig

/**
 * Creates the initial syntax-valid MPTUNNEL editor document.
 *
 * Afterwards the native syntax-aware editor owns projection and patching so guided edits preserve
 * comments and unknown settings. Managed references are finalized only for runtime.
 */
object MppConfigRenderer {
    const val CREDENTIAL_MATERIAL_ID = "credential"
    const val CERTIFICATE_MATERIAL_ID = "pinned-certificate"
    const val TRANSPORT_SECRET_MATERIAL_ID = "transport-secret"

    const val SOCKS_PORT_TOKEN = "@mptunnel-socks-port@"
    const val LOCAL_USER_DEFINITION_TOKEN = "@mptunnel-local-user-definition@"
    const val LOCAL_USER_BINDING_TOKEN = "@mptunnel-local-user-binding@"

    /** Creates an editable document with no runtime port, authentication, or material bytes. */
    fun renderEditableTemplate(server: String, config: MppProfileConfig): String {
        val paths = config.effectivePaths(server)
        require(paths.isNotEmpty()) { "MPP requires at least one path" }
        val advanced = config.advanced

        return buildString {
            appendLine("[logging]")
            appendLine("level = \"info\"")
            appendLine()
            appendLine("[[credentials]]")
            appendLine("credential_id = ${tomlString(config.credentialId)}")
            appendLine("principal_id = ${tomlString(config.principalId)}")
            appendLine("secret = ${managedRef(CREDENTIAL_MATERIAL_ID)}")
            appendLine()
            appendLine("# $LOCAL_USER_DEFINITION_TOKEN")
            appendLine()
            if (advanced != null) {
                appendLine("[session]")
                appendLine("retention_timeout_ms = ${advanced.sessionRetentionTimeoutMs}")
                appendLine()
                appendLine("[resources]")
                appendLine("tcp_path_heartbeat_interval_ms = ${advanced.tcpHeartbeatIntervalMs}")
                appendLine("tcp_path_heartbeat_timeout_ms = ${advanced.tcpHeartbeatTimeoutMs}")
                appendLine("quic_path_keep_alive_interval_ms = ${advanced.quicKeepAliveIntervalMs}")
                appendLine("quic_path_idle_timeout_ms = ${advanced.quicIdleTimeoutMs}")
                appendLine()
            }
            appendLine("[[inbounds]]")
            appendLine("name = \"local-mixed\"")
            appendLine("protocol = \"mixed\"")
            appendLine("listen = [${tomlString("127.0.0.1:$SOCKS_PORT_TOKEN")}]")
            appendLine("# $LOCAL_USER_BINDING_TOKEN")
            appendLine()
            appendLine("[[outbounds]]")
            appendLine("name = \"remote-mpp\"")
            appendLine("protocol = \"mpp\"")
            if (advanced != null) {
                appendLine("path_probe_interval_ms = ${advanced.pathProbeIntervalMs}")
                appendLine("path_probe_timeout_ms = ${advanced.pathProbeTimeoutMs}")
            }
            appendLine("paths = [")
            paths.forEachIndexed { index, path ->
                append("  { name = ${tomlString(path.name)}, endpoint = ${tomlString(path.endpoint)} }")
                if (index != paths.lastIndex) append(',')
                appendLine()
            }
            appendLine("]")
            appendLine()
            if (advanced != null) {
                appendLine("[outbounds.performance]")
                appendLine("extra_traffic_hint_percent = ${advanced.extraTrafficHintPercent}")
                appendLine()
            }
            appendLine("[outbounds.security]")
            appendLine("credential_id = ${tomlString(config.credentialId)}")
            if (advanced != null) {
                appendLine(
                    "auth_freshness_window_seconds = ${advanced.authFreshnessWindowSeconds}"
                )
            }
            if (config.tlsServerName.isNotBlank()) {
                appendLine("tls_server_name = ${tomlString(config.tlsServerName)}")
            }
            appendLine(
                "tls_pinned_certificate = ${managedRef(CERTIFICATE_MATERIAL_ID)}"
            )
            appendLine(
                "transport_secret = ${managedRef(TRANSPORT_SECRET_MATERIAL_ID)}"
            )
            appendLine()
            appendLine("[dns]")
            appendLine("default = \"mpp-doh\"")
            appendLine()
            appendLine("[[dns.servers]]")
            appendLine("name = \"mpp-doh\"")
            appendLine("protocol = \"doh\"")
            appendLine("address = \"1.1.1.1:443\"")
            appendLine("tls_name = \"cloudflare-dns.com\"")
            appendLine("path = \"/dns-query\"")
            appendLine()
            appendLine("[[dns.policies]]")
            appendLine("name = \"mpp-doh\"")
            appendLine("servers = [\"mpp-doh\"]")
            appendLine("family = \"ipv4-and-ipv6\"")
            appendLine("security = \"require-encrypted\"")
            appendLine("strategy = \"ordered\"")
            appendLine("answer_cidrs = []")
            appendLine("query = { timeout_ms = 5000, inflight = 64, answers = 64 }")
            appendLine(
                "cache = { entries = 4096, positive_ttl_ms = 300000, " +
                        "negative_ttl_ms = 30000, stale_ms = 30000, prefetch_ms = 30000 }"
            )
            appendLine()
            appendLine("[routing]")
            appendLine()
            appendLine("[[routing.rules]]")
            appendLine("name = \"default\"")
            appendLine("action = \"outbound\"")
            appendLine("outbound = \"remote-mpp\"")
        }
    }

    private fun managedRef(id: String): String =
        "{ from = \"managed\", id = ${tomlString(id)} }"

    private fun tomlString(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '\b' -> append("\\b")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\u000c' -> append("\\f")
                '\r' -> append("\\r")
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                else -> {
                    if (character.code < 0x20 || character.code == 0x7f) {
                        append("\\u")
                        append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
                }
            }
        }
        append('"')
    }
}
