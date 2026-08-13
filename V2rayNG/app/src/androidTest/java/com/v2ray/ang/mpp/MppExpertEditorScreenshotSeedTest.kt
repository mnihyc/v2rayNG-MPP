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
                            "tcp-carriers=1-4&port-hop-interval-ms=45000&" +
                            "srtt-ms=18&jitter-ms=4&rate-mbps=250&" +
                            "datagram-payload-limit=1350&bulk-allowed",
                ),
                MppPathConfig(
                    name = "mobile-quic",
                    endpoint = "udp://edge-b.example:7443?" +
                            "expensive&srtt-ms=55&jitter-ms=20&rate-mbps=60",
                ),
                MppPathConfig(
                    name = "backup-v6",
                    endpoint = "tcp://[2001:db8::20]:8443?" +
                            "tcp-carriers=1-1&backup&probe-only&no-udp",
                ),
            ),
            advanced = MppAdvancedConfig(
                pathProbeIntervalMs = 15_000L,
                pathProbeTimeoutMs = 2_500L,
                extraTrafficHintPercent = 12,
                authFreshnessWindowSeconds = 240L,
                sessionRetentionTimeoutMs = 420_000L,
                tcpHeartbeatIntervalMs = 8_000L,
                tcpHeartbeatTimeoutMs = 24_000L,
                quicKeepAliveIntervalMs = 12_000L,
                quicIdleTimeoutMs = 45_000L,
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
