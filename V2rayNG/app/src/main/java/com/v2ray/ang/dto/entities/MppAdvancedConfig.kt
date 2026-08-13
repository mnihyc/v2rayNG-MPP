package com.v2ray.ang.dto.entities

/**
 * Expert MPTunnel runtime tuning.
 *
 * These defaults mirror MPTunnel's native defaults. The parent profile keeps this object nullable
 * so profiles saved by builds without the expert editor remain distinguishable and retain the
 * renderer behavior they had before these controls were added.
 */
data class MppAdvancedConfig(
    val pathProbeIntervalMs: Long = DEFAULT_PATH_PROBE_INTERVAL_MS,
    val pathProbeTimeoutMs: Long = DEFAULT_PATH_PROBE_TIMEOUT_MS,
    val extraTrafficHintPercent: Int = DEFAULT_EXTRA_TRAFFIC_HINT_PERCENT,
    val authFreshnessWindowSeconds: Long = DEFAULT_AUTH_FRESHNESS_WINDOW_SECONDS,
    val sessionRetentionTimeoutMs: Long = DEFAULT_SESSION_RETENTION_TIMEOUT_MS,
    val tcpHeartbeatIntervalMs: Long = DEFAULT_TCP_HEARTBEAT_INTERVAL_MS,
    val tcpHeartbeatTimeoutMs: Long = DEFAULT_TCP_HEARTBEAT_TIMEOUT_MS,
    val quicKeepAliveIntervalMs: Long = DEFAULT_QUIC_KEEP_ALIVE_INTERVAL_MS,
    val quicIdleTimeoutMs: Long = DEFAULT_QUIC_IDLE_TIMEOUT_MS,
) {
    companion object {
        const val DEFAULT_PATH_PROBE_INTERVAL_MS = 10_000L
        const val DEFAULT_PATH_PROBE_TIMEOUT_MS = 2_000L
        const val DEFAULT_EXTRA_TRAFFIC_HINT_PERCENT = 5
        const val DEFAULT_AUTH_FRESHNESS_WINDOW_SECONDS = 300L
        const val DEFAULT_SESSION_RETENTION_TIMEOUT_MS = 300_000L
        const val DEFAULT_TCP_HEARTBEAT_INTERVAL_MS = 10_000L
        const val DEFAULT_TCP_HEARTBEAT_TIMEOUT_MS = 30_000L
        const val DEFAULT_QUIC_KEEP_ALIVE_INTERVAL_MS = 10_000L
        const val DEFAULT_QUIC_IDLE_TIMEOUT_MS = 30_000L

        /** Maximum QUIC varint value accepted by the native idle-timeout validation. */
        const val MAX_QUIC_IDLE_TIMEOUT_MS = 4_611_686_018_427_387_903L
        const val MAX_EXTRA_TRAFFIC_HINT_PERCENT = 65_535
    }
}
