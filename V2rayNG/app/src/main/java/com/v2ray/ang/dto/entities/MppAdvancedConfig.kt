package com.v2ray.ang.dto.entities

/**
 * Expert MPTunnel runtime tuning.
 *
 * These defaults mirror MPTunnel's native defaults. The parent profile keeps this object nullable
 * so profiles saved by builds without the expert editor remain distinguishable and retain the
 * renderer behavior they had before these controls were added.
 */
data class MppAdvancedConfig(
    val pathProbeIntervalS: Double = DEFAULT_PATH_PROBE_INTERVAL_S,
    val pathProbeTimeoutS: Double = DEFAULT_PATH_PROBE_TIMEOUT_S,
    val optionalReinjectionBudgetPercent: Int = DEFAULT_OPTIONAL_REINJECTION_BUDGET_PERCENT,
    val authFreshnessWindowS: Double = DEFAULT_AUTH_FRESHNESS_WINDOW_S,
    val sessionRetentionTimeoutS: Double = DEFAULT_SESSION_RETENTION_TIMEOUT_S,
    val tcpHeartbeatIntervalS: Double = DEFAULT_TCP_HEARTBEAT_INTERVAL_S,
    val tcpHeartbeatTimeoutS: Double = DEFAULT_TCP_HEARTBEAT_TIMEOUT_S,
    val quicKeepAliveIntervalS: Double = DEFAULT_QUIC_KEEP_ALIVE_INTERVAL_S,
    val quicIdleTimeoutS: Double = DEFAULT_QUIC_IDLE_TIMEOUT_S,
) {
    companion object {
        const val DEFAULT_PATH_PROBE_INTERVAL_S = 10.0
        const val DEFAULT_PATH_PROBE_TIMEOUT_S = 2.0
        const val DEFAULT_OPTIONAL_REINJECTION_BUDGET_PERCENT = 20
        const val DEFAULT_AUTH_FRESHNESS_WINDOW_S = 300.0
        const val DEFAULT_SESSION_RETENTION_TIMEOUT_S = 300.0
        const val DEFAULT_TCP_HEARTBEAT_INTERVAL_S = 10.0
        const val DEFAULT_TCP_HEARTBEAT_TIMEOUT_S = 30.0
        const val DEFAULT_QUIC_KEEP_ALIVE_INTERVAL_S = 10.0
        const val DEFAULT_QUIC_IDLE_TIMEOUT_S = 30.0

        /** Largest whole second exactly representable below native QUIC's varint-ms ceiling. */
        const val MAX_QUIC_IDLE_TIMEOUT_S = 4_611_686_018_427_387.0
        const val MAX_OPTIONAL_REINJECTION_BUDGET_PERCENT = 65_535
    }
}
