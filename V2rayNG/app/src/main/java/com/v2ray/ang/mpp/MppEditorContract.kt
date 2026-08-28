package com.v2ray.ang.mpp

import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import com.v2ray.ang.dto.entities.MppAdvancedConfig
import com.v2ray.ang.dto.entities.MppPathConfig
import com.v2ray.ang.dto.entities.MppProfileConfig

/** Versioned JSON boundary shared with MPTUNNEL's syntax-preserving TOML editor. */
data class MppEditorProjection(
    @SerializedName("schema_version") val schemaVersion: Int = SCHEMA_VERSION,
    @SerializedName("log_level") val logLevel: String,
    /** Null represents compatibility mode; native patching then keeps the optional key omitted. */
    @SerializedName("target_resolution") val targetResolution: String?,
    val paths: List<MppPathConfig>,
    val advanced: Advanced?,
    @SerializedName("credential_id") val credentialId: String,
    @SerializedName("principal_id") val principalId: String,
    @SerializedName("tls_server_name") val tlsServerName: String,
) {
    data class Advanced(
        @SerializedName("path_probe_interval_s") val pathProbeIntervalS: Double,
        @SerializedName("path_probe_timeout_s") val pathProbeTimeoutS: Double,
        @SerializedName("optional_reinjection_budget_percent")
        val optionalReinjectionBudgetPercent: Int,
        @SerializedName("auth_freshness_window_s") val authFreshnessWindowS: Double,
        @SerializedName("session_retention_timeout_s") val sessionRetentionTimeoutS: Double,
        @SerializedName("tcp_heartbeat_interval_s") val tcpHeartbeatIntervalS: Double,
        @SerializedName("tcp_heartbeat_timeout_s") val tcpHeartbeatTimeoutS: Double,
        @SerializedName("quic_keep_alive_interval_s") val quicKeepAliveIntervalS: Double,
        @SerializedName("quic_idle_timeout_s") val quicIdleTimeoutS: Double,
    ) {
        fun toProfileValue() = MppAdvancedConfig(
            pathProbeIntervalS = pathProbeIntervalS,
            pathProbeTimeoutS = pathProbeTimeoutS,
            optionalReinjectionBudgetPercent = optionalReinjectionBudgetPercent,
            authFreshnessWindowS = authFreshnessWindowS,
            sessionRetentionTimeoutS = sessionRetentionTimeoutS,
            tcpHeartbeatIntervalS = tcpHeartbeatIntervalS,
            tcpHeartbeatTimeoutS = tcpHeartbeatTimeoutS,
            quicKeepAliveIntervalS = quicKeepAliveIntervalS,
            quicIdleTimeoutS = quicIdleTimeoutS,
        )

        companion object {
            fun from(value: MppAdvancedConfig) = Advanced(
                pathProbeIntervalS = value.pathProbeIntervalS,
                pathProbeTimeoutS = value.pathProbeTimeoutS,
                optionalReinjectionBudgetPercent = value.optionalReinjectionBudgetPercent,
                authFreshnessWindowS = value.authFreshnessWindowS,
                sessionRetentionTimeoutS = value.sessionRetentionTimeoutS,
                tcpHeartbeatIntervalS = value.tcpHeartbeatIntervalS,
                tcpHeartbeatTimeoutS = value.tcpHeartbeatTimeoutS,
                quicKeepAliveIntervalS = value.quicKeepAliveIntervalS,
                quicIdleTimeoutS = value.quicIdleTimeoutS,
            )
        }
    }

    fun applyTo(config: MppProfileConfig, editorToml: String = config.editorToml) = config.copy(
        editorSchemaVersion = MppProfileConfig.CURRENT_EDITOR_SCHEMA_VERSION,
        editorToml = editorToml,
        logLevel = logLevel,
        targetResolution = targetResolution,
        paths = paths,
        advanced = advanced?.toProfileValue(),
        credentialId = credentialId,
        principalId = principalId,
        tlsServerName = tlsServerName,
    )

    companion object {
        const val SCHEMA_VERSION = 2

        fun from(config: MppProfileConfig, server: String): MppEditorProjection =
            MppEditorProjection(
                logLevel = config.logLevel,
                targetResolution = config.targetResolution,
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
