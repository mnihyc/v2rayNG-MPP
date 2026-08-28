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
            valid.copy(pathProbeIntervalS = ""),
            valid.copy(pathProbeTimeoutS = "Infinity"),
            valid.copy(optionalReinjectionBudgetPercent = "-1"),
            valid.copy(authFreshnessWindowS = "0"),
            valid.copy(authFreshnessWindowS = "0.5"),
            valid.copy(sessionRetentionTimeoutS = "0"),
            valid.copy(tcpHeartbeatIntervalS = "0"),
            valid.copy(tcpHeartbeatTimeoutS = "4.999"),
            valid.copy(quicKeepAliveIntervalS = "0"),
            valid.copy(quicIdleTimeoutS = "10"),
        ).forEach { invalid -> assertFalse(invalid.isValid()) }
    }

    private fun validDraft() = MppAdvancedTextDraft(
        pathProbeIntervalS = "30",
        pathProbeTimeoutS = "5.25",
        optionalReinjectionBudgetPercent = "20",
        authFreshnessWindowS = "60",
        sessionRetentionTimeoutS = "120",
        tcpHeartbeatIntervalS = "5",
        tcpHeartbeatTimeoutS = "15",
        quicKeepAliveIntervalS = "10",
        quicIdleTimeoutS = "30",
    )
}
