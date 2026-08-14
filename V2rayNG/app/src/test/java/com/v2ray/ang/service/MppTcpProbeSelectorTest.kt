package com.v2ray.ang.service

import com.v2ray.ang.dto.entities.MppPathConfig
import com.v2ray.ang.dto.entities.MppProfileConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Test

class MppTcpProbeSelectorTest {

    @Test
    fun explicitPathsUseFirstFixedTcpAndIgnoreTopLevelServer() {
        val profile = profile(
            MppProfileConfig(
                paths = listOf(
                    MppPathConfig("quic", "quic://quic.example:7443"),
                    MppPathConfig("tcp-range", "tcp://range.example:7000-7099"),
                    MppPathConfig("tcp-fixed", "tcp://fixed.example:8443?backup=true"),
                    MppPathConfig("tcp-later", "tcp://later.example:9443"),
                )
            )
        )

        assertEquals(
            MppTcpProbeSelection.Endpoint("fixed.example", 8443),
            MppTcpProbeSelector.select(profile),
        )
    }

    @Test
    fun rangedTcpAndUdpOnlyRemainUntested() {
        val profile = profile(
            MppProfileConfig(
                paths = listOf(
                    MppPathConfig("tcp-range", "tcp://range.example:7000-7099"),
                    MppPathConfig("quic", "quic://quic.example:7443"),
                )
            )
        )

        assertEquals(MppTcpProbeSelection.Untested, MppTcpProbeSelector.select(profile))
    }

    @Test
    fun malformedExplicitPathIsInvalidInsteadOfUntested() {
        val profile = profile(
            MppProfileConfig(
                paths = listOf(MppPathConfig("broken", "tcp://server.example:not-a-port"))
            )
        )

        assertEquals(MppTcpProbeSelection.Invalid, MppTcpProbeSelector.select(profile))
    }

    @Test
    fun explicitEmptyListIsInvalid() {
        val profile = profile(MppProfileConfig(paths = emptyList()))

        assertEquals(MppTcpProbeSelection.Invalid, MppTcpProbeSelector.select(profile))
    }

    @Test
    fun rawTomlIsUntestedWithoutTopLevelAddress() {
        val profile = profile(
            MppProfileConfig(
                paths = null,
                useRawToml = true,
                rawToml = "# endpoint lives in the full document",
            )
        ).copy(server = null, serverPort = null)

        assertEquals(MppTcpProbeSelection.Untested, MppTcpProbeSelector.select(profile))
    }

    @Test
    fun legacyProfilesKeepTcpAndUdpOnlyBehavior() {
        assertEquals(
            MppTcpProbeSelection.Endpoint("legacy.example", 7443),
            MppTcpProbeSelector.select(profile(MppProfileConfig(paths = null))),
        )
        assertEquals(
            MppTcpProbeSelection.Untested,
            MppTcpProbeSelector.select(
                profile(
                    MppProfileConfig(paths = null, tcpEnabled = false, udpEnabled = true)
                )
            ),
        )
    }

    private fun profile(config: MppProfileConfig) = ProfileItem(
        configType = EConfigType.MPP,
        server = "legacy.example",
        serverPort = "7443",
        mpp = config,
    )
}
