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
    fun profileWithoutAdvancedObjectLeavesEveryNativeDefaultUnpinned() {
        val config = validConfig()

        val template = MppConfigRenderer.renderEditableTemplate("edge.example", config)

        assertFalse(template.contains("[session]"))
        assertFalse(template.contains("retention_timeout_s"))
        assertFalse(template.contains("[resources]"))
        assertFalse(template.contains("path_probe_interval_s"))
        assertFalse(template.contains("[outbounds.performance]"))
        assertTrue(
            template.contains(
                "# performance = { optional_reinjection_budget_percent = 20, " +
                        "quic_loss_compensation_percent = 5 } # overrides [flow]; " +
                        "path URI wins for loss"
            )
        )
        assertFalse(template.contains("auth_freshness_window_s"))
        assertNull(MppEditorProjection.from(config, "edge.example").advanced)
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
                retention_timeout_s = 300

                [resources]
                tcp_path_heartbeat_interval_s = 10
                tcp_path_heartbeat_timeout_s = 30
                quic_path_keep_alive_interval_s = 10
                quic_path_idle_timeout_s = 30
                """.trimIndent()
            )
        )
        assertTrue(template.contains("path_probe_interval_s = 10"))
        assertTrue(template.contains("path_probe_timeout_s = 2"))
        assertTrue(
            template.contains(
                "[outbounds.performance]\noptional_reinjection_budget_percent = 10"
            )
        )
        assertTrue(template.contains("auth_freshness_window_s = 300"))
        assertNull(MppProfileValidator.validate(config))
    }

    @Test
    fun customExpertValuesRenderExactlyInNativeTomlLocations() {
        val advanced = MppAdvancedConfig(
            pathProbeIntervalS = 12.345,
            pathProbeTimeoutS = 3.456,
            optionalReinjectionBudgetPercent = 321,
            authFreshnessWindowS = 654.0,
            sessionRetentionTimeoutS = 456.789,
            tcpHeartbeatIntervalS = 4.0,
            tcpHeartbeatTimeoutS = 9.0,
            quicKeepAliveIntervalS = 5.0,
            quicIdleTimeoutS = 15.0,
        )
        val config = validConfig().copy(advanced = advanced)

        val template = MppConfigRenderer.renderEditableTemplate("edge.example", config)

        assertTrue(template.contains("[session]\nretention_timeout_s = 456.789"))
        assertTrue(
            template.contains(
                """
                [resources]
                tcp_path_heartbeat_interval_s = 4
                tcp_path_heartbeat_timeout_s = 9
                quic_path_keep_alive_interval_s = 5
                quic_path_idle_timeout_s = 15
                """.trimIndent()
            )
        )
        assertTrue(
            template.contains(
                """
                name = "remote-mpp"
                protocol = "mpp"
                path_probe_interval_s = 12.345
                path_probe_timeout_s = 3.456
                paths = [
                """.trimIndent()
            )
        )
        assertTrue(
            template.contains(
                "[outbounds.performance]\noptional_reinjection_budget_percent = 321"
            )
        )
        assertTrue(
            template.contains(
                "credential_id = \"android-client\"\n" +
                        "auth_freshness_window_s = 654"
            )
        )
        assertNull(MppProfileValidator.validate(config))
    }

    @Test
    fun everyNativeAdvancedConstraintIsValidatedBeforeSave() {
        val defaults = MppAdvancedConfig()
        val invalidValues = listOf(
            defaults.copy(pathProbeIntervalS = 0.0),
            defaults.copy(pathProbeTimeoutS = 0.0),
            defaults.copy(optionalReinjectionBudgetPercent = -1),
            defaults.copy(
                optionalReinjectionBudgetPercent =
                MppAdvancedConfig.MAX_OPTIONAL_REINJECTION_BUDGET_PERCENT + 1
            ),
            defaults.copy(authFreshnessWindowS = 0.0),
            defaults.copy(authFreshnessWindowS = 0.5),
            defaults.copy(sessionRetentionTimeoutS = 0.0),
            defaults.copy(tcpHeartbeatIntervalS = 0.0),
            defaults.copy(
                tcpHeartbeatIntervalS = 10.0,
                tcpHeartbeatTimeoutS = 9.999,
            ),
            defaults.copy(quicKeepAliveIntervalS = 0.0),
            defaults.copy(
                quicKeepAliveIntervalS = 10.0,
                quicIdleTimeoutS = 10.0,
            ),
            defaults.copy(quicIdleTimeoutS = Double.POSITIVE_INFINITY),
            defaults.copy(pathProbeIntervalS = Double.NaN),
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
            pathProbeIntervalS = 0.001,
            pathProbeTimeoutS = 0.001,
            optionalReinjectionBudgetPercent = 0,
            authFreshnessWindowS = 1.0,
            sessionRetentionTimeoutS = 0.001,
            tcpHeartbeatIntervalS = 0.001,
            tcpHeartbeatTimeoutS = 0.001,
            quicKeepAliveIntervalS = 0.001,
            quicIdleTimeoutS = 0.002,
        )
        val upperBudget = lowerBudget.copy(
            optionalReinjectionBudgetPercent =
            MppAdvancedConfig.MAX_OPTIONAL_REINJECTION_BUDGET_PERCENT,
            quicIdleTimeoutS = MppAdvancedConfig.MAX_QUIC_IDLE_TIMEOUT_S,
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
                pathProbeIntervalS = 25.0,
                optionalReinjectionBudgetPercent = 42,
            )
        )
        val restored = gson.fromJson(gson.toJson(original), MppProfileConfig::class.java)
        assertEquals(original, restored)
    }

    @Test
    fun schemaTwoProjectionUsesOnlySecondsAndTheRenamedBudget() {
        val projection = MppEditorProjection.from(
            validConfig().copy(advanced = MppAdvancedConfig(pathProbeTimeoutS = 2.125)),
            "edge.example",
        )

        val json = MppEditorJson.encode(projection)

        assertTrue(json.contains("\"schema_version\":2"))
        assertTrue(json.contains("\"path_probe_timeout_s\":2.125"))
        assertTrue(json.contains("\"optional_reinjection_budget_percent\":10"))
        assertTrue(json.contains("\"auth_freshness_window_s\":300.0"))
        assertFalse(json.contains("_ms\""))
        assertFalse(json.contains("_seconds\""))
        assertFalse(json.contains("extra_traffic_hint_percent"))
    }

    @Test
    fun rawTomlRemainsTheAuthoritativeExpertEscapeHatch() {
        val base = validConfig()
        val template = MppConfigRenderer.renderEditableTemplate("edge.example", base)
        val staleInvalidStructuredTuning = MppAdvancedConfig(pathProbeIntervalS = 0.0)

        assertNull(
            MppProfileValidator.validate(
                base.copy(
                    editorSchemaVersion = MppProfileConfig.CURRENT_EDITOR_SCHEMA_VERSION,
                    editorToml = template,
                    advanced = staleInvalidStructuredTuning,
                    useRawToml = true,
                    credentialSecret = MppMaterialCodec.encodeStored(
                        MppMaterialCodec.encodeUtf8(base.credentialSecret)
                    ),
                    pinnedCertificatePem = MppMaterialCodec.encodeStored(
                        MppMaterialCodec.encodeUtf8(base.pinnedCertificatePem)
                    ),
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
