package com.v2ray.ang.mpp

import com.v2ray.ang.dto.entities.MppProfileConfig
import java.security.SecureRandom

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

    private const val MANAGEMENT_TOKEN_BYTES = 24
    private const val MANAGEMENT_LISTEN = "127.0.0.1:7600"
    private const val LOWERCASE_HEX = "0123456789abcdef"
    private val secureRandom = SecureRandom()

    /** Creates a persisted document with proxy bindings and remote credentials as placeholders. */
    fun renderEditableTemplate(server: String, config: MppProfileConfig): String =
        renderTemplate(server, config, includeManagement = true)

    /**
     * Creates the ephemeral runtime document for a schema-zero profile.
     *
     * A legacy profile has nowhere to persist or expose a newly generated management token, so
     * this compatibility path deliberately omits the management listener. It must not be used to
     * create or replace the authoritative editor document of a saved profile.
     */
    internal fun renderLegacyRuntimeTemplate(server: String, config: MppProfileConfig): String =
        renderTemplate(server, config, includeManagement = false)

    private fun renderTemplate(
        server: String,
        config: MppProfileConfig,
        includeManagement: Boolean,
    ): String {
        val paths = config.effectivePaths(server)
        require(paths.isNotEmpty()) { "MPP requires at least one path" }
        val advanced = config.advanced
        val managementToken = if (includeManagement) newManagementToken() else null

        return buildString {
            appendLine("[logging]")
            appendLine("level = ${tomlString(config.logLevel)}")
            appendLine()
            if (managementToken != null) {
                appendLine("[management]")
                appendLine("listen = [${tomlString(MANAGEMENT_LISTEN)}]")
                appendLine("token = { from = \"raw\", value = ${tomlString(managementToken)} }")
                appendLine("dashboard = true")
                appendLine("allow_peer_diagnostics = false")
                appendLine()
            }
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
            appendLine("# Optional V2Ray-style `geoip:private` bypass. Uncomment this entire")
            appendLine("# outbound together with its complete routing rule below only when")
            appendLine("# this broad local bypass is intended.")
            appendLine("# [[outbounds]]")
            appendLine("# name = \"private-direct\"")
            appendLine("# protocol = \"direct\"")
            appendLine()
            appendLine("[routing]")
            config.targetResolution?.let { targetResolution ->
                require(targetResolution in MppProfileConfig.SUPPORTED_TARGET_RESOLUTIONS) {
                    "unsupported MPP target resolution"
                }
                appendLine("target_resolution = ${tomlString(targetResolution)}")
            }
            appendLine()
            appendLine("# Complete V2Fly `geoip:private` literal set. With `as-is`, hostnames")
            appendLine("# remain unresolved here and continue to the ordinary remote rule.")
            appendLine("# [[routing.rules]]")
            appendLine("# name = \"bypass-v2ray-private-literals\"")
            appendLine("# inbounds = [\"local-mixed\"]")
            appendLine("# destination_cidrs = [")
            V2RAY_PRIVATE_CIDRS.forEach { cidr ->
                appendLine("#   ${tomlString(cidr)},")
            }
            appendLine("# ]")
            appendLine("# decision = \"allow-restricted\"")
            appendLine("# outbound = \"private-direct\"")
            appendLine()
            appendLine("# Delegate literal targets, including a private VPN DNS endpoint such")
            appendLine("# as 10.1.2.3, through MPP. The server still authorizes actual egress.")
            appendLine("[[routing.rules]]")
            appendLine("name = \"delegate-literal-targets\"")
            appendLine("inbounds = [\"local-mixed\"]")
            appendLine("destination_cidrs = [\"0.0.0.0/0\", \"::/0\"]")
            appendLine("decision = \"allow-restricted\"")
            appendLine("outbound = \"remote-mpp\"")
            appendLine()
            appendLine("[[routing.rules]]")
            appendLine("name = \"default\"")
            appendLine("outbound = \"remote-mpp\"")
        }
    }

    private fun newManagementToken(): String {
        val bytes = ByteArray(MANAGEMENT_TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return buildString(MANAGEMENT_TOKEN_BYTES * 2) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(LOWERCASE_HEX[value ushr 4])
                append(LOWERCASE_HEX[value and 0x0f])
            }
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

    private val V2RAY_PRIVATE_CIDRS = listOf(
        "0.0.0.0/8",
        "10.0.0.0/8",
        "100.64.0.0/10",
        "127.0.0.0/8",
        "169.254.0.0/16",
        "172.16.0.0/12",
        "192.0.0.0/24",
        "192.0.2.0/24",
        "192.88.99.0/24",
        "192.168.0.0/16",
        "198.18.0.0/15",
        "198.51.100.0/24",
        "203.0.113.0/24",
        "224.0.0.0/4",
        "240.0.0.0/4",
        "255.255.255.255/32",
        "::/128",
        "::1/128",
        "fc00::/7",
        "fe80::/10",
        "ff00::/8",
    )
}
