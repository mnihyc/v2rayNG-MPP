package com.v2ray.ang.mpp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MptunnelRuntimeWatchdogPolicyTest {

    @Test
    fun liveAndTransitionalStatesKeepTheService() {
        listOf("starting", "ready").forEach { state ->
            assertFalse(state, MptunnelRuntimeWatchdogPolicy.shouldStopService(state))
        }
        assertFalse(
            MptunnelRuntimeWatchdogPolicy.shouldStopService(
                "stopping",
                MptunnelRuntimeWatchdogPolicy.STOPPING_GRACE_MS - 1,
            )
        )
        assertTrue(
            MptunnelRuntimeWatchdogPolicy.shouldStopService(
                "stopping",
                MptunnelRuntimeWatchdogPolicy.STOPPING_GRACE_MS,
            )
        )
    }

    @Test
    fun terminalUnavailableAndUnknownStatesStopTheService() {
        listOf("stopped", "failed", "unavailable", "", "future-state").forEach { state ->
            assertTrue(state, MptunnelRuntimeWatchdogPolicy.shouldStopService(state))
        }
    }

    @Test
    fun onlyCurrentMptunnelGenerationMayStopItsOwner() {
        assertTrue(MptunnelRuntimeWatchdogPolicy.canStopOwner(7, 7, true, true))
        assertFalse(MptunnelRuntimeWatchdogPolicy.canStopOwner(6, 7, true, true))
        assertFalse(MptunnelRuntimeWatchdogPolicy.canStopOwner(7, 7, false, true))
        assertFalse(MptunnelRuntimeWatchdogPolicy.canStopOwner(7, 7, true, false))
    }
}
