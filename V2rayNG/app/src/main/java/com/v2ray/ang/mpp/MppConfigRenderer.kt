package com.v2ray.ang.mpp

import com.v2ray.ang.dto.entities.MppAdvancedConfig
import com.v2ray.ang.dto.entities.MppProfileConfig
import com.v2ray.ang.dto.entities.ProfileItem

/**
 * Renders the MPTunnel TOML document while keeping all secret/certificate values out of it.
 *
 * Material tokens are semantic bridge inputs, not paths. The native bridge replaces them with
 * confined app-private basenames after it has materialized the corresponding profile values.
 */
object MppConfigRenderer {
    const val CREDENTIAL_MATERIAL_TOKEN = "@mptunnel-profile-credential@"
    const val CERTIFICATE_MATERIAL_TOKEN = "@mptunnel-profile-certificate@"
    const val TRANSPORT_SECRET_MATERIAL_TOKEN = "@mptunnel-profile-transport-secret@"
    const val LOCAL_PROXY_PASSWORD_MATERIAL_TOKEN = "@mptunnel-local-proxy-password@"

    const val SOCKS_PORT_TOKEN = "@mptunnel-socks-port@"
    const val LOCAL_USER_DEFINITION_TOKEN = "@mptunnel-local-user-definition@"
    const val LOCAL_USER_BINDING_TOKEN = "@mptunnel-local-user-binding@"

    fun renderRuntime(
        profile: ProfileItem,
        socksPort: Int,
        proxyUsername: String,
        hasProxyPassword: Boolean,
    ): String {
        require(socksPort in 1..65535) { "invalid MPP SOCKS port" }
        val config = requireNotNull(profile.mpp) { "MPP profile data is missing" }
        val localAuth = localProxyAuth(proxyUsername, hasProxyPassword)
        return if (config.useRawToml) {
            config.rawToml
                .replace(SOCKS_PORT_TOKEN, socksPort.toString())
                .replace(LOCAL_USER_DEFINITION_TOKEN, localAuth.definition)
                .replace(LOCAL_USER_BINDING_TOKEN, localAuth.binding)
        } else {
            renderStructured(
                server = profile.server.orEmpty(),
                config = config,
                socksPort = socksPort.toString(),
                localUserDefinition = localAuth.definition,
                localUserBinding = localAuth.binding,
            )
        }
    }

    /** Creates the editable full-TOML view without exposing runtime port numbers or paths. */
    fun renderEditableTemplate(server: String, config: MppProfileConfig): String =
        renderStructured(
            server = server,
            config = config,
            socksPort = SOCKS_PORT_TOKEN,
            localUserDefinition = LOCAL_USER_DEFINITION_TOKEN,
            localUserBinding = LOCAL_USER_BINDING_TOKEN,
        )

    private fun renderStructured(
        server: String,
        config: MppProfileConfig,
        socksPort: String,
        localUserDefinition: String,
        localUserBinding: String,
    ): String {
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
            appendLine(
                "secret = { from = \"file\", path = " +
                        "${tomlString(CREDENTIAL_MATERIAL_TOKEN)} }"
            )
            appendLine()
            appendLine(localUserDefinition)
            appendLine()
            appendLine("[session]")
            appendLine(
                "retention_timeout_ms = " +
                        (advanced?.sessionRetentionTimeoutMs
                            ?: MppAdvancedConfig.DEFAULT_SESSION_RETENTION_TIMEOUT_MS)
            )
            appendLine()
            if (advanced != null) {
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
            appendLine("listen = [${tomlString("127.0.0.1:$socksPort")}]")
            appendLine(localUserBinding)
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
                appendLine(
                    "extra_traffic_hint_percent = ${advanced.extraTrafficHintPercent}"
                )
                appendLine()
            }
            appendLine("[outbounds.security]")
            appendLine("credential_id = ${tomlString(config.credentialId)}")
            if (advanced != null) {
                appendLine(
                    "auth_freshness_window_seconds = " +
                            advanced.authFreshnessWindowSeconds
                )
            }
            if (config.tlsServerName.isNotBlank()) {
                appendLine("tls_server_name = ${tomlString(config.tlsServerName)}")
            }
            appendLine(
                "tls_pinned_certificate_file = " +
                        tomlString(CERTIFICATE_MATERIAL_TOKEN)
            )
            if (config.transportSecret.isNotBlank()) {
                appendLine(
                    "transport_secret_file = " +
                            tomlString(TRANSPORT_SECRET_MATERIAL_TOKEN)
                )
            }
            appendLine()
            appendLine("[dns]")
            appendLine("default_dns_plan = \"mpp-doh\"")
            appendLine()
            appendLine("[[dns.upstreams]]")
            appendLine("name = \"mpp-doh\"")
            appendLine("transport = \"https\"")
            appendLine("bootstrap = \"1.1.1.1:443\"")
            appendLine("server_name = \"cloudflare-dns.com\"")
            appendLine("path = \"/dns-query\"")
            appendLine()
            appendLine("[[dns.plans]]")
            appendLine("name = \"mpp-doh\"")
            appendLine("upstreams = [\"mpp-doh\"]")
            appendLine("security = \"require-encrypted\"")
            appendLine()
            appendLine("[routing]")
            appendLine()
            appendLine("[[routing.rules]]")
            appendLine("name = \"default\"")
            appendLine("action = \"outbound\"")
            appendLine("outbound = \"remote-mpp\"")
        }
    }

    private fun localProxyAuth(username: String, hasPassword: Boolean): LocalProxyAuth {
        if (username.isBlank() || !hasPassword) return LocalProxyAuth("", "")
        val definition = buildString {
            appendLine("[[local_users]]")
            appendLine("name = \"v2rayng-local\"")
            appendLine("principal_id = \"v2rayng-local\"")
            appendLine("username = ${tomlString(username)}")
            append(
                "password = { from = \"file\", path = " +
                        "${tomlString(LOCAL_PROXY_PASSWORD_MATERIAL_TOKEN)} }"
            )
        }
        return LocalProxyAuth(
            definition = definition,
            binding = "local_users = [\"v2rayng-local\"]",
        )
    }

    private data class LocalProxyAuth(
        val definition: String,
        val binding: String,
    )

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
