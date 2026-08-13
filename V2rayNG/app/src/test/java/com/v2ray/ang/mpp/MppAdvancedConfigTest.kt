package com.v2ray.ang.mpp

import com.google.gson.Gson
import com.v2ray.ang.dto.entities.MppAdvancedConfig
import com.v2ray.ang.dto.entities.MppProfileConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MppAdvancedConfigTest {

    @Test
    fun profileWithoutAdvancedObjectRetainsLegacyRendererShape() {
        val config = validConfig()

        val template = MppConfigRenderer.renderEditableTemplate("edge.example", config)

        assertTrue(template.contains("[session]\nretention_timeout_ms = 300000"))
        assertFalse(template.contains("[resources]"))
        assertFalse(template.contains("path_probe_interval_ms"))
        assertFalse(template.contains("[outbounds.performance]"))
        assertFalse(template.contains("auth_freshness_window_seconds"))
        assertNull(MppProfileValidator.validate(config))
    }

    @Test
    fun explicitNativeDefaultsRenderEveryExpertSectionAndValidate() {
        val config = validConfig().copy(advanced = MppAdvancedConfig())

        val template = MppConfigRenderer.renderEditableTemplate("edge.example", config)

        assertTrue(
            template.contains(
                """
                [session]
                retention_timeout_ms = 300000

                [resources]
                tcp_path_heartbeat_interval_ms = 10000
                tcp_path_heartbeat_timeout_ms = 30000
                quic_path_keep_alive_interval_ms = 10000
                quic_path_idle_timeout_ms = 30000
                """.trimIndent()
            )
        )
        assertTrue(template.contains("path_probe_interval_ms = 10000"))
        assertTrue(template.contains("path_probe_timeout_ms = 2000"))
        assertTrue(
            template.contains(
                "[outbounds.performance]\nextra_traffic_hint_percent = 5"
            )
        )
        assertTrue(template.contains("auth_freshness_window_seconds = 300"))
        assertNull(MppProfileValidator.validate(config))
    }

    @Test
    fun customExpertValuesRenderExactlyInNativeTomlLocations() {
        val advanced = MppAdvancedConfig(
            pathProbeIntervalMs = 12_345L,
            pathProbeTimeoutMs = 3_456L,
            extraTrafficHintPercent = 321,
            authFreshnessWindowSeconds = 654L,
            sessionRetentionTimeoutMs = 456_789L,
            tcpHeartbeatIntervalMs = 4_000L,
            tcpHeartbeatTimeoutMs = 9_000L,
            quicKeepAliveIntervalMs = 5_000L,
            quicIdleTimeoutMs = 15_000L,
        )
        val config = validConfig().copy(advanced = advanced)

        val template = MppConfigRenderer.renderEditableTemplate("edge.example", config)

        assertTrue(template.contains("[session]\nretention_timeout_ms = 456789"))
        assertTrue(
            template.contains(
                """
                [resources]
                tcp_path_heartbeat_interval_ms = 4000
                tcp_path_heartbeat_timeout_ms = 9000
                quic_path_keep_alive_interval_ms = 5000
                quic_path_idle_timeout_ms = 15000
                """.trimIndent()
            )
        )
        assertTrue(
            template.contains(
                """
                name = "remote-mpp"
                protocol = "mpp"
                path_probe_interval_ms = 12345
                path_probe_timeout_ms = 3456
                paths = [
                """.trimIndent()
            )
        )
        assertTrue(
            template.contains(
                "[outbounds.performance]\nextra_traffic_hint_percent = 321"
            )
        )
        assertTrue(
            template.contains(
                "credential_id = \"android-client\"\n" +
                        "auth_freshness_window_seconds = 654"
            )
        )
        assertNull(MppProfileValidator.validate(config))
    }

    @Test
    fun everyNativeAdvancedConstraintIsValidatedBeforeSave() {
        val defaults = MppAdvancedConfig()
        val invalidValues = listOf(
            defaults.copy(pathProbeIntervalMs = 0L),
            defaults.copy(pathProbeTimeoutMs = 0L),
            defaults.copy(extraTrafficHintPercent = -1),
            defaults.copy(
                extraTrafficHintPercent =
                MppAdvancedConfig.MAX_EXTRA_TRAFFIC_HINT_PERCENT + 1
            ),
            defaults.copy(authFreshnessWindowSeconds = 0L),
            defaults.copy(sessionRetentionTimeoutMs = 0L),
            defaults.copy(tcpHeartbeatIntervalMs = 0L),
            defaults.copy(
                tcpHeartbeatIntervalMs = 10_000L,
                tcpHeartbeatTimeoutMs = 9_999L,
            ),
            defaults.copy(quicKeepAliveIntervalMs = 0L),
            defaults.copy(
                quicKeepAliveIntervalMs = 10_000L,
                quicIdleTimeoutMs = 10_000L,
            ),
            defaults.copy(
                quicIdleTimeoutMs = MppAdvancedConfig.MAX_QUIC_IDLE_TIMEOUT_MS + 1L
            ),
        )

        invalidValues.forEach { advanced ->
            assertEquals(
                advanced.toString(),
                MppValidationError.ADVANCED_TUNING,
                MppProfileValidator.validate(validConfig().copy(advanced = advanced)),
            )
        }
    }

    @Test
    fun inclusiveNativeBoundariesRemainAvailableToExpertProfiles() {
        val lowerBudget = MppAdvancedConfig(
            pathProbeIntervalMs = 1L,
            pathProbeTimeoutMs = 1L,
            extraTrafficHintPercent = 0,
            authFreshnessWindowSeconds = 1L,
            sessionRetentionTimeoutMs = 1L,
            tcpHeartbeatIntervalMs = 1L,
            tcpHeartbeatTimeoutMs = 1L,
            quicKeepAliveIntervalMs = 1L,
            quicIdleTimeoutMs = 2L,
        )
        val upperBudget = lowerBudget.copy(
            extraTrafficHintPercent = MppAdvancedConfig.MAX_EXTRA_TRAFFIC_HINT_PERCENT,
            quicIdleTimeoutMs = MppAdvancedConfig.MAX_QUIC_IDLE_TIMEOUT_MS,
        )

        assertNull(MppProfileValidator.validate(validConfig().copy(advanced = lowerBudget)))
        assertNull(MppProfileValidator.validate(validConfig().copy(advanced = upperBudget)))
    }

    @Test
    fun advancedObjectIsMigrationSafeAndRoundTripsThroughGson() {
        val gson = Gson()
        val legacy = gson.fromJson(
            """{"tcpPort":8443,"credentialId":"legacy"}""",
            MppProfileConfig::class.java,
        )
        assertNull(legacy.advanced)

        val original = validConfig().copy(
            advanced = MppAdvancedConfig(
                pathProbeIntervalMs = 25_000L,
                extraTrafficHintPercent = 42,
            )
        )
        val restored = gson.fromJson(gson.toJson(original), MppProfileConfig::class.java)
        assertEquals(original, restored)
    }

    @Test
    fun rawTomlRemainsTheAuthoritativeExpertEscapeHatch() {
        val base = validConfig()
        val template = MppConfigRenderer.renderEditableTemplate("edge.example", base)
        val staleInvalidStructuredTuning = MppAdvancedConfig(pathProbeIntervalMs = 0L)

        assertNull(
            MppProfileValidator.validate(
                base.copy(
                    advanced = staleInvalidStructuredTuning,
                    useRawToml = true,
                    rawToml = template,
                )
            )
        )
    }

    private fun validConfig() = MppProfileConfig(
        credentialSecret = "0123456789abcdef0123456789abcdef",
        pinnedCertificatePem = """
            -----BEGIN CERTIFICATE-----
            ZHVtbXk=
            -----END CERTIFICATE-----
        """.trimIndent(),
    )
}
