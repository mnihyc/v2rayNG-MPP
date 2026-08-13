package com.v2ray.ang.mpp

import androidx.compose.runtime.saveable.SaverScope
import com.v2ray.ang.dto.entities.MppAdvancedConfig
import com.v2ray.ang.dto.entities.MppPathConfig
import com.v2ray.ang.dto.entities.MppProfileConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.ui.server.ServerUiState
import com.v2ray.ang.util.JsonUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MppProfileModelCompatibilityTest {

    @Test
    fun legacyMmkvJsonUsesExactLegacyPathSynthesis() {
        val legacyJson = """
            {
              "configType": "MPP",
              "server": "[2001:db8::10]",
              "mpp": {
                "tcpEnabled": true,
                "tcpPort": 7443,
                "tcpCarrierCount": 5,
                "udpEnabled": true,
                "udpPort": 9443
              }
            }
        """.trimIndent()

        val profile = requireNotNull(
            JsonUtil.fromJsonSafe(legacyJson, ProfileItem::class.java)
        )
        val config = requireNotNull(profile.mpp)

        assertNull(config.paths)
        assertEquals(
            listOf(
                MppPathConfig(
                    name = "path-tcp",
                    endpoint = "tcp://[2001:db8::10]:7443?tcp-carriers=1-5",
                ),
                MppPathConfig(
                    name = "path-udp",
                    endpoint = "udp://[2001:db8::10]:9443",
                ),
            ),
            config.effectivePaths(profile.server.orEmpty()),
        )
    }

    @Test
    fun explicitPathsRoundTripThroughMmkvJsonAndServerUiState() {
        val paths = listOf(
            MppPathConfig(
                name = "fiber-primary",
                endpoint = "tcp://edge-a.example:7000-7099" +
                        "?tcp-carriers=1-4&port-hop-interval-ms=30000&rate-mbps=900",
            ),
            MppPathConfig(
                name = "cell-backup",
                endpoint = "udp://[2001:db8::20]:8443" +
                        "?backup=true&expensive=true&datagram-payload-limit=1200",
            ),
        )
        val advanced = MppAdvancedConfig(
            pathProbeIntervalMs = 25_000L,
            extraTrafficHintPercent = 42,
            quicIdleTimeoutMs = 60_000L,
        )
        val original = ProfileItem(
            configType = EConfigType.MPP,
            server = "legacy.example",
            mpp = MppProfileConfig(paths = paths, advanced = advanced),
        )

        val restored = requireNotNull(
            JsonUtil.fromJsonSafe(JsonUtil.toJson(original), ProfileItem::class.java)
        )
        assertEquals(paths, restored.mpp?.paths)
        assertEquals(advanced, restored.mpp?.advanced)
        assertEquals(paths, restored.mpp?.effectivePaths(restored.server.orEmpty()))

        val uiState = ServerUiState.fromProfileItem(restored)
        assertEquals("edge-a.example", uiState.address)
        assertEquals("7000", uiState.port)
        val stateProfile = uiState.toProfileItem(restored)
        assertEquals(paths, stateProfile.mpp?.paths)
        assertEquals("edge-a.example", stateProfile.server)
        assertEquals("7000", stateProfile.serverPort)

        val saved = ServerUiState.Saver.run {
            SaverScope { true }.save(uiState)
        }
        val saverRestored = requireNotNull(ServerUiState.Saver.restore(requireNotNull(saved)))
        assertEquals(paths, saverRestored.mppConfig.paths)
        assertEquals(advanced, saverRestored.mppConfig.advanced)
    }

    @Test
    fun explicitSummarySkipsMalformedPathAndDoesNotUseStaleTopLevelServer() {
        val original = ProfileItem(
            configType = EConfigType.MPP,
            server = "stale.example",
            serverPort = "1",
            mpp = MppProfileConfig(
                paths = listOf(
                    MppPathConfig("broken", "not-an-endpoint"),
                    MppPathConfig("quic-range", "udp://[2001:db8::42]:8000-8010"),
                )
            ),
        )

        val state = ServerUiState.fromProfileItem(original)
        assertEquals("2001:db8::42", state.address)
        assertEquals("8000", state.port)
        val saved = state.toProfileItem(original)
        assertEquals("2001:db8::42", saved.server)
        assertEquals("8000", saved.serverPort)
    }

    @Test
    fun legacySummaryFieldsRemainUnchanged() {
        val original = ProfileItem(
            configType = EConfigType.MPP,
            server = "legacy.example",
            serverPort = "7443",
            mpp = MppProfileConfig(
                paths = null,
                tcpEnabled = false,
                udpEnabled = true,
                udpPort = 9443,
            ),
        )

        val state = ServerUiState.fromProfileItem(original)
        assertEquals("legacy.example", state.address)
        assertEquals("7443", state.port)
        val saved = state.toProfileItem(original)
        assertEquals("legacy.example", saved.server)
        assertEquals("9443", saved.serverPort)
    }

    @Test
    fun explicitEmptyPathListDoesNotFallBackToLegacyPaths() {
        val original = MppProfileConfig(paths = emptyList())
        val restored = requireNotNull(
            JsonUtil.fromJsonSafe(JsonUtil.toJson(original), MppProfileConfig::class.java)
        )

        assertEquals(emptyList<MppPathConfig>(), restored.paths)
        assertEquals(emptyList<MppPathConfig>(), restored.effectivePaths("server.example"))
    }
}
