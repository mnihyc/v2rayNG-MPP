package com.v2ray.ang.mpp

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v2ray.ang.dto.entities.MppAdvancedConfig
import com.v2ray.ang.dto.entities.MppPathConfig
import com.v2ray.ang.dto.entities.MppProfileConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.server.ServerMppActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Seeds a deterministic, non-production profile for emulator screenshots and opens its editor.
 *
 * The fixed GUID makes this idempotent. All material is an obvious test placeholder; the editor
 * receives material values directly, exactly like a real profile, rather than filesystem paths.
 */
@RunWith(AndroidJUnit4::class)
class MppExpertEditorScreenshotSeedTest {

    @Test
    fun seedAndLaunchExpertEditor() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val profile = expertProfile()

        assertNull(MppProfileValidator.validate(profile.mpp!!))
        assertEquals(
            SCREENSHOT_PROFILE_GUID,
            MmkvManager.encodeServerConfig(SCREENSHOT_PROFILE_GUID, profile),
        )
        assertEquals(profile, MmkvManager.decodeServerConfig(SCREENSHOT_PROFILE_GUID))

        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, ServerMppActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("guid", SCREENSHOT_PROFILE_GUID)
        )
        instrumentation.waitForIdleSync()

        assertTrue(activity is ServerMppActivity)
        assertTrue(activity.hasWindowFocus())
    }

    private fun expertProfile() = ProfileItem(
        configType = EConfigType.MPP,
        remarks = "Expert multipath",
        server = "edge-a.example",
        serverPort = "7000",
        mpp = MppProfileConfig(
            paths = listOf(
                MppPathConfig(
                    name = "wifi-primary",
                    endpoint = "tcp://edge-a.example:7000-7099?" +
                            "max-tcp-carriers=4&port-rotation-interval-s=45&" +
                            "initial-srtt-s=0.018&initial-rttvar-s=0.004&" +
                            "initial-rate-mbps=250&allow-bulk=true",
                ),
                MppPathConfig(
                    name = "mobile-quic",
                    endpoint = "quic://edge-b.example:7443?expensive=true&" +
                            "initial-srtt-s=0.055&initial-rttvar-s=0.020&" +
                            "loss-compensation-percent=10&" +
                            "initial-rate-mbps=60&max-datagram-payload-bytes=1350",
                ),
                MppPathConfig(
                    name = "backup-v6",
                    endpoint = "tcp://[2001:db8::20]:8443?" +
                            "max-tcp-carriers=1&backup=true&control-only=true&" +
                            "allow-datagrams=false",
                ),
            ),
            advanced = MppAdvancedConfig(
                pathProbeIntervalS = 15.0,
                pathProbeTimeoutS = 2.5,
                optionalReinjectionBudgetPercent = 12,
                authFreshnessWindowS = 240.0,
                sessionRetentionTimeoutS = 420.0,
                tcpHeartbeatIntervalS = 8.0,
                tcpHeartbeatTimeoutS = 24.0,
                quicKeepAliveIntervalS = 12.0,
                quicIdleTimeoutS = 45.0,
            ),
            credentialId = "expert-client",
            principalId = "advanced-user",
            credentialSecret = "not-a-secret-screenshot-placeholder-0001",
            tlsServerName = "mptunnel.example",
            pinnedCertificatePem = """
                -----BEGIN CERTIFICATE-----
                ZHVtbXktc2NyZWVuc2hvdC1jZXJ0aWZpY2F0ZQ==
                -----END CERTIFICATE-----
            """.trimIndent(),
        ),
    )

    private companion object {
        const val SCREENSHOT_PROFILE_GUID = "mpp-expert-screenshot"
    }
}
