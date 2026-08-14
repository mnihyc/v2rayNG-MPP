package com.v2ray.ang.ui.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MppAdvancedTextDraftTest {

    @Test
    fun rejectsIncompleteOverflowAndInvalidRelationships() {
        val valid = validDraft()
        assertTrue(valid.isValid())
        listOf(
            valid.copy(pathProbeIntervalMs = ""),
            valid.copy(pathProbeTimeoutMs = "999999999999999999999999"),
            valid.copy(extraTrafficHintPercent = "-1"),
            valid.copy(authFreshnessWindowSeconds = "0"),
            valid.copy(sessionRetentionTimeoutMs = "0"),
            valid.copy(tcpHeartbeatIntervalMs = "0"),
            valid.copy(tcpHeartbeatTimeoutMs = "4999"),
            valid.copy(quicKeepAliveIntervalMs = "0"),
            valid.copy(quicIdleTimeoutMs = "10000"),
        ).forEach { invalid -> assertFalse(invalid.isValid()) }
    }

    private fun validDraft() = MppAdvancedTextDraft(
        pathProbeIntervalMs = "30000",
        pathProbeTimeoutMs = "5000",
        extraTrafficHintPercent = "20",
        authFreshnessWindowSeconds = "60",
        sessionRetentionTimeoutMs = "120000",
        tcpHeartbeatIntervalMs = "5000",
        tcpHeartbeatTimeoutMs = "15000",
        quicKeepAliveIntervalMs = "10000",
        quicIdleTimeoutMs = "30000",
    )
}
