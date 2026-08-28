package com.v2ray.ang.ui.server

import com.v2ray.ang.dto.entities.MppAdvancedConfig

/** Text-level validation for guided numeric edits which are not yet committed to the TOML model. */
internal data class MppAdvancedTextDraft(
    val pathProbeIntervalS: String,
    val pathProbeTimeoutS: String,
    val optionalReinjectionBudgetPercent: String,
    val authFreshnessWindowS: String,
    val sessionRetentionTimeoutS: String,
    val tcpHeartbeatIntervalS: String,
    val tcpHeartbeatTimeoutS: String,
    val quicKeepAliveIntervalS: String,
    val quicIdleTimeoutS: String,
) {
    fun isValid(): Boolean {
        val probeInterval = pathProbeIntervalS.toFiniteDoubleOrNull() ?: return false
        val probeTimeout = pathProbeTimeoutS.toFiniteDoubleOrNull() ?: return false
        val reinjectionBudget = optionalReinjectionBudgetPercent.toIntOrNull() ?: return false
        val authFreshness = authFreshnessWindowS.toFiniteDoubleOrNull() ?: return false
        val sessionRetention = sessionRetentionTimeoutS.toFiniteDoubleOrNull() ?: return false
        val tcpInterval = tcpHeartbeatIntervalS.toFiniteDoubleOrNull() ?: return false
        val tcpTimeout = tcpHeartbeatTimeoutS.toFiniteDoubleOrNull() ?: return false
        val quicKeepAlive = quicKeepAliveIntervalS.toFiniteDoubleOrNull() ?: return false
        val quicIdle = quicIdleTimeoutS.toFiniteDoubleOrNull() ?: return false
        return probeInterval > 0.0 &&
                probeTimeout > 0.0 &&
                reinjectionBudget in
                0..MppAdvancedConfig.MAX_OPTIONAL_REINJECTION_BUDGET_PERCENT &&
                authFreshness > 0.0 && authFreshness % 1.0 == 0.0 &&
                sessionRetention > 0.0 &&
                tcpInterval > 0.0 &&
                tcpTimeout >= tcpInterval &&
                quicKeepAlive > 0.0 &&
                quicIdle > quicKeepAlive &&
                quicIdle <= MppAdvancedConfig.MAX_QUIC_IDLE_TIMEOUT_S
    }

    private fun String.toFiniteDoubleOrNull(): Double? =
        toDoubleOrNull()?.takeIf { it.isFinite() }
}
