package com.v2ray.ang.mpp

import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import com.v2ray.ang.dto.entities.MppAdvancedConfig
import com.v2ray.ang.dto.entities.MppPathConfig
import com.v2ray.ang.dto.entities.MppProfileConfig

/** Versioned JSON boundary shared with MPTUNNEL's syntax-preserving TOML editor. */
data class MppEditorProjection(
    @SerializedName("schema_version") val schemaVersion: Int = SCHEMA_VERSION,
    val paths: List<MppPathConfig>,
    val advanced: Advanced?,
    @SerializedName("credential_id") val credentialId: String,
    @SerializedName("principal_id") val principalId: String,
    @SerializedName("tls_server_name") val tlsServerName: String,
) {
    data class Advanced(
        @SerializedName("path_probe_interval_ms") val pathProbeIntervalMs: Long,
        @SerializedName("path_probe_timeout_ms") val pathProbeTimeoutMs: Long,
        @SerializedName("extra_traffic_hint_percent") val extraTrafficHintPercent: Int,
        @SerializedName("auth_freshness_window_seconds") val authFreshnessWindowSeconds: Long,
        @SerializedName("session_retention_timeout_ms") val sessionRetentionTimeoutMs: Long,
        @SerializedName("tcp_heartbeat_interval_ms") val tcpHeartbeatIntervalMs: Long,
        @SerializedName("tcp_heartbeat_timeout_ms") val tcpHeartbeatTimeoutMs: Long,
        @SerializedName("quic_keep_alive_interval_ms") val quicKeepAliveIntervalMs: Long,
        @SerializedName("quic_idle_timeout_ms") val quicIdleTimeoutMs: Long,
    ) {
        fun toProfileValue() = MppAdvancedConfig(
            pathProbeIntervalMs = pathProbeIntervalMs,
            pathProbeTimeoutMs = pathProbeTimeoutMs,
            extraTrafficHintPercent = extraTrafficHintPercent,
            authFreshnessWindowSeconds = authFreshnessWindowSeconds,
            sessionRetentionTimeoutMs = sessionRetentionTimeoutMs,
            tcpHeartbeatIntervalMs = tcpHeartbeatIntervalMs,
            tcpHeartbeatTimeoutMs = tcpHeartbeatTimeoutMs,
            quicKeepAliveIntervalMs = quicKeepAliveIntervalMs,
            quicIdleTimeoutMs = quicIdleTimeoutMs,
        )

        companion object {
            fun from(value: MppAdvancedConfig) = Advanced(
                pathProbeIntervalMs = value.pathProbeIntervalMs,
                pathProbeTimeoutMs = value.pathProbeTimeoutMs,
                extraTrafficHintPercent = value.extraTrafficHintPercent,
                authFreshnessWindowSeconds = value.authFreshnessWindowSeconds,
                sessionRetentionTimeoutMs = value.sessionRetentionTimeoutMs,
                tcpHeartbeatIntervalMs = value.tcpHeartbeatIntervalMs,
                tcpHeartbeatTimeoutMs = value.tcpHeartbeatTimeoutMs,
                quicKeepAliveIntervalMs = value.quicKeepAliveIntervalMs,
                quicIdleTimeoutMs = value.quicIdleTimeoutMs,
            )
        }
    }

    fun applyTo(config: MppProfileConfig, editorToml: String = config.editorToml) = config.copy(
        editorSchemaVersion = MppProfileConfig.CURRENT_EDITOR_SCHEMA_VERSION,
        editorToml = editorToml,
        paths = paths,
        advanced = advanced?.toProfileValue(),
        credentialId = credentialId,
        principalId = principalId,
        tlsServerName = tlsServerName,
    )

    companion object {
        const val SCHEMA_VERSION = 1

        fun from(config: MppProfileConfig, server: String): MppEditorProjection =
            MppEditorProjection(
                paths = config.effectivePaths(server),
                advanced = config.advanced?.let(Advanced::from),
                credentialId = config.credentialId,
                principalId = config.principalId,
                tlsServerName = config.tlsServerName,
            )
    }
}

data class MppFinalizeBindings(
    @SerializedName("schema_version") val schemaVersion: Int = SCHEMA_VERSION,
    @SerializedName("socks_port") val socksPort: Int,
    @SerializedName("credential_base64") val credentialBase64: String,
    @SerializedName("pinned_certificate_base64") val pinnedCertificateBase64: String,
    @SerializedName("transport_secret_base64") val transportSecretBase64: String?,
    @SerializedName("local_auth") val localAuth: LocalAuth?,
) {
    data class LocalAuth(
        val username: String,
        @SerializedName("password_base64") val passwordBase64: String,
    ) {
        override fun toString(): String =
            "LocalAuth(username=$username, passwordBase64=<redacted>)"
    }

    override fun toString(): String =
        "MppFinalizeBindings(" +
                "schemaVersion=$schemaVersion, socksPort=$socksPort, " +
                "credentialBase64=<redacted>, pinnedCertificateBase64=<redacted>, " +
                "transportSecretBase64=${if (transportSecretBase64 == null) "<absent>" else "<redacted>"}, " +
                "localAuth=$localAuth)"

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/** Emits every versioned JNI field, including optional values represented by explicit JSON null. */
internal object MppEditorJson {
    private val gson = GsonBuilder().serializeNulls().create()

    fun encode(value: MppEditorProjection): String = gson.toJson(value)

    fun encode(value: MppFinalizeBindings): String = gson.toJson(value)
}
