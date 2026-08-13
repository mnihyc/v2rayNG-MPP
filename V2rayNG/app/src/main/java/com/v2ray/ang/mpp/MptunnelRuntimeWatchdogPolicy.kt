package com.v2ray.ang.mpp

/**
 * Interprets the lifecycle strings exported by the bundled MPTUNNEL JNI bridge.
 *
 * This policy is intentionally fail closed: once a runtime has reported listener readiness, only
 * its live states may keep the owning Android service alive. Stopping gets a bounded grace period
 * for cooperative teardown; stopped/failed, unavailable, or unknown states stop the owner.
 */
internal object MptunnelRuntimeWatchdogPolicy {
    const val STOPPING_GRACE_MS = 5_000L

    private val liveStates = setOf("starting", "ready")

    fun shouldStopService(state: String, stoppingElapsedMs: Long = 0L): Boolean = when (state) {
        in liveStates -> false
        "stopping" -> stoppingElapsedMs >= STOPPING_GRACE_MS
        else -> true
    }

    fun canStopOwner(
        watchGeneration: Long,
        currentGeneration: Long,
        isMptunnelActive: Boolean,
        ownsService: Boolean,
    ): Boolean = watchGeneration == currentGeneration && isMptunnelActive && ownsService
}
