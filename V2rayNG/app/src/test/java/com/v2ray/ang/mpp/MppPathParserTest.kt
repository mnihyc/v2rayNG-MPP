package com.v2ray.ang.mpp

import com.v2ray.ang.dto.entities.MppPathConfig
import com.v2ray.ang.dto.entities.MppProfileConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MppPathParserTest {

    @Test
    fun parsesCompleteCurrentTcpAndQuicGrammar() {
        val parsedTcp = MppPathParser.parse(
                "tcp://edge.example:7000-7999?source-ip=192.0.2.10&srtt-ms=20&" +
                        "jitter-ms=5&rate-mbps=30&datagram-payload-limit=1400&" +
                        "tcp-carriers=2-5&port-hop-interval-ms=45000&backup&" +
                        "expensive=false&bulk-allowed=true&probe-only=false&no-udp=true"
        )
        assertNotNull(parsedTcp)
        val tcp = parsedTcp!!
        assertEquals(MppPathUnderlay.TCP, tcp.underlay)
        assertEquals("edge.example", tcp.host)
        assertEquals(7000, tcp.firstPort)
        assertEquals(7999, tcp.lastPort)
        assertEquals(5, tcp.tcpCarrierMax)
        assertEquals(5, tcp.carrierSlots)
        assertEquals("source-ip", tcp.options.first().key)
        assertEquals("no-udp", tcp.options.last().key)

        val parsedQuic = MppPathParser.parse(
                "udp://[2001:db8::10]:7443?rate=unlimited&" +
                        "datagram-payload-limit=65000&no-udp=false"
        )
        assertNotNull(parsedQuic)
        val quic = parsedQuic!!
        assertEquals(MppPathUnderlay.UDP, quic.underlay)
        assertEquals("2001:db8::10", quic.host)
        assertEquals(7443, quic.firstPort)
        assertEquals(1, quic.carrierSlots)
    }

    @Test
    fun matchesNativeCrossFieldAndCanonicalRangeRules() {
        val invalid = listOf(
            "edge.example:443",
            "TCP://edge.example:443",
            "tcp://edge.example:0",
            "tcp://edge.example:443-443",
            "tcp://edge.example:444-443",
            "tcp://edge.example:443?",
            "tcp://edge.example:443?unknown=true",
            "tcp://edge.example:443?srtt-ms=1&srtt-ms=2",
            "tcp://edge.example:443?rate-bps=1&rate=unknown",
            "tcp://edge.example:443?datagram-payload-limit=511",
            "tcp://edge.example:443?tcp-carriers=0-3",
            "tcp://edge.example:443?tcp-carriers=4-3",
            "udp://edge.example:443?tcp-carriers=1-3",
            "udp://edge.example:443?no-udp=true",
            "udp://edge.example:443-444?port-hop-interval-ms=4999",
            "udp://edge.example:443?port-hop-interval-ms=5000",
            "tcp://edge.example:443?source-ip=01.2.3.4",
        )
        invalid.forEach { endpoint -> assertNull(endpoint, MppPathParser.parse(endpoint)) }

        // Native policy flags are last-value-wins, unlike scalar path options.
        assertNotNull(MppPathParser.parse("tcp://edge.example:443?backup&backup=false"))
        assertNotNull(MppPathParser.parse("tcp://edge.example:443?rate-bps=+1"))
    }

    @Test
    fun validatorAcceptsArbitraryOrderedPathsAndRejectsIdentityErrors() {
        val paths = listOf(
            MppPathConfig("wifi", "tcp://wifi.example:7443?tcp-carriers=1-4"),
            MppPathConfig("mobile", "udp://mobile.example:7443?expensive=true"),
            MppPathConfig("backup", "tcp://backup.example:8443?backup&tcp-carriers=1-1"),
        )
        assertNull(MppProfileValidator.validate(validConfig(paths)))
        assertEquals(
            MppValidationError.PATH_NAME,
            MppProfileValidator.validate(validConfig(paths + paths.first())),
        )
        assertEquals(
            MppValidationError.PATH_NAME,
            MppProfileValidator.validate(
                validConfig(listOf(MppPathConfig("Not-Canonical", "tcp://edge.example:7443")))
            ),
        )
        assertEquals(
            MppValidationError.PATH_ENDPOINT,
            MppProfileValidator.validate(
                validConfig(listOf(MppPathConfig("broken", "quic://edge.example:7443")))
            ),
        )
        assertEquals(
            MppValidationError.PATH_REQUIRED,
            MppProfileValidator.validate(validConfig(emptyList())),
        )
    }

    @Test
    fun validatorEnforcesEntryAndEffectiveCarrierSlotLimits() {
        val sixtyFourQuicPaths = (1..64).map { index ->
            MppPathConfig("quic-$index", "udp://edge-$index.example:7443")
        }
        assertNull(MppProfileValidator.validate(validConfig(sixtyFourQuicPaths)))
        assertEquals(
            MppValidationError.PATH_COUNT,
            MppProfileValidator.validate(
                validConfig(
                    sixtyFourQuicPaths + MppPathConfig("quic-65", "udp://edge-65.example:7443")
                )
            ),
        )

        assertNull(
            MppProfileValidator.validate(
                validConfig(
                    listOf(
                        MppPathConfig("tcp", "tcp://edge.example:7443?tcp-carriers=1-63"),
                        MppPathConfig("quic", "udp://edge.example:7443"),
                    )
                )
            )
        )
        assertEquals(
            MppValidationError.PATH_CARRIER_LIMIT,
            MppProfileValidator.validate(
                validConfig(
                    listOf(
                        MppPathConfig("tcp", "tcp://edge.example:7443?tcp-carriers=1-64"),
                        MppPathConfig("quic", "udp://edge.example:7443"),
                    )
                )
            ),
        )
    }

    private fun validConfig(paths: List<MppPathConfig>) = MppProfileConfig(
        paths = paths,
        credentialSecret = "0123456789abcdef0123456789abcdef",
        pinnedCertificatePem = """
            -----BEGIN CERTIFICATE-----
            ZHVtbXk=
            -----END CERTIFICATE-----
        """.trimIndent(),
    )
}
