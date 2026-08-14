package com.v2ray.ang.ui.server

import com.v2ray.ang.dto.entities.MppAdvancedConfig

/** Text-level validation for guided numeric edits which are not yet committed to the TOML model. */
internal data class MppAdvancedTextDraft(
    val pathProbeIntervalMs: String,
    val pathProbeTimeoutMs: String,
    val extraTrafficHintPercent: String,
    val authFreshnessWindowSeconds: String,
    val sessionRetentionTimeoutMs: String,
    val tcpHeartbeatIntervalMs: String,
    val tcpHeartbeatTimeoutMs: String,
    val quicKeepAliveIntervalMs: String,
    val quicIdleTimeoutMs: String,
) {
    fun isValid(): Boolean {
        val probeInterval = pathProbeIntervalMs.toLongOrNull() ?: return false
        val probeTimeout = pathProbeTimeoutMs.toLongOrNull() ?: return false
        val extraTraffic = extraTrafficHintPercent.toIntOrNull() ?: return false
        val authFreshness = authFreshnessWindowSeconds.toLongOrNull() ?: return false
        val sessionRetention = sessionRetentionTimeoutMs.toLongOrNull() ?: return false
        val tcpInterval = tcpHeartbeatIntervalMs.toLongOrNull() ?: return false
        val tcpTimeout = tcpHeartbeatTimeoutMs.toLongOrNull() ?: return false
        val quicKeepAlive = quicKeepAliveIntervalMs.toLongOrNull() ?: return false
        val quicIdle = quicIdleTimeoutMs.toLongOrNull() ?: return false
        return probeInterval > 0L &&
                probeTimeout > 0L &&
                extraTraffic in 0..MppAdvancedConfig.MAX_EXTRA_TRAFFIC_HINT_PERCENT &&
                authFreshness > 0L &&
                sessionRetention > 0L &&
                tcpInterval > 0L &&
                tcpTimeout >= tcpInterval &&
                quicKeepAlive > 0L &&
                quicIdle > quicKeepAlive &&
                quicIdle <= MppAdvancedConfig.MAX_QUIC_IDLE_TIMEOUT_MS
    }
}
