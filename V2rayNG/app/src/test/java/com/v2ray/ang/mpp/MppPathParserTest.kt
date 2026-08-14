package com.v2ray.ang.mpp

import com.v2ray.ang.dto.entities.MppPathConfig
import com.v2ray.ang.dto.entities.MppProfileConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MppPathParserTest {

    @Test
    fun parsesCompleteCanonicalTcpAndQuicGrammar() {
        val parsedTcp = MppPathParser.parse(
            "tcp://edge.example:7000-7999?source-address=192.0.2.10&" +
                    "initial-srtt-ms=20&initial-rttvar-ms=5&initial-rate-mbps=30&" +
                    "max-tcp-carriers=5&port-rotation-interval-ms=45000&" +
                    "backup=true&expensive=false&allow-bulk=false&control-only=true&" +
                    "allow-datagrams=false"
        )
        assertNotNull(parsedTcp)
        val tcp = parsedTcp!!
        assertEquals(MppPathUnderlay.TCP, tcp.underlay)
        assertEquals("edge.example", tcp.host)
        assertEquals(7000, tcp.firstPort)
        assertEquals(7999, tcp.lastPort)
        assertEquals(5, tcp.tcpCarrierMax)
        assertEquals(5, tcp.carrierSlots)
        assertEquals("source-address", tcp.options.first().key)
        assertEquals("allow-datagrams", tcp.options.last().key)

        val parsedQuic = MppPathParser.parse(
            "quic://[2001:db8::10]:7443?initial-rttvar-ms=0&" +
                    "initial-rate=unlimited&max-datagram-payload-bytes=65000&" +
                    "backup=false&expensive=true&allow-bulk=true&control-only=false"
        )
        assertNotNull(parsedQuic)
        val quic = parsedQuic!!
        assertEquals(MppPathUnderlay.QUIC, quic.underlay)
        assertEquals("2001:db8::10", quic.host)
        assertEquals(7443, quic.firstPort)
        assertEquals(1, quic.carrierSlots)
    }

    @Test
    fun queryVocabularyMatchesTheNativeFifteenKeys() {
        assertEquals(
            listOf(
                "source-address",
                "initial-srtt-ms",
                "initial-rttvar-ms",
                "initial-rate-bps",
                "initial-rate-kbps",
                "initial-rate-mbps",
                "initial-rate",
                "max-datagram-payload-bytes",
                "max-tcp-carriers",
                "port-rotation-interval-ms",
                "backup",
                "expensive",
                "allow-bulk",
                "control-only",
                "allow-datagrams",
            ),
            MppPathParser.QUERY_KEYS.toList(),
        )
    }

    @Test
    fun rejectsNoncanonicalSchemesHostsPortsAndRanges() {
        val invalid = listOf(
            "edge.example:443",
            "udp://edge.example:443",
            "TCP://edge.example:443",
            "tcp://edge.example:0",
            "tcp://edge.example:0443",
            "tcp://edge.example:+443",
            "tcp://edge.example:443-443",
            "tcp://edge.example:444-443",
            "tcp://edge.example:443-0444",
            "tcp://edge.example:443-",
            "tcp://edge.example:-444",
            "tcp://edge.example:443-444-445",
            "quic://2001:db8::1:443",
            "quic://[edge.example]:443",
            "quic://[2001:db8::1:443",
            " tcp://edge.example:443",
            "tcp://edge.example:443 ",
        )
        invalid.forEach { endpoint -> assertNull(endpoint, MppPathParser.parse(endpoint)) }
    }

    @Test
    fun rejectsMissingUnknownDuplicateAndInapplicableOptions() {
        val invalid = listOf(
            "tcp://edge.example:443?",
            "tcp://edge.example:443?&backup=true",
            "tcp://edge.example:443?unknown=true",
            "tcp://edge.example:443?source-address=01.2.3.4",
            "tcp://edge.example:443?source-address=not-an-ip",
            "tcp://edge.example:443?initial-srtt-ms=0",
            "tcp://edge.example:443?initial-srtt-ms=1&initial-srtt-ms=2",
            "tcp://edge.example:443?initial-rate-bps=1&initial-rate=unknown",
            "tcp://edge.example:443?initial-rate-mbps=18446744073710",
            "tcp://edge.example:443?max-tcp-carriers=0",
            "tcp://edge.example:443?max-tcp-carriers=65536",
            "tcp://edge.example:443?max-tcp-carriers=1-3",
            "tcp://edge.example:443?max-datagram-payload-bytes=1400",
            "quic://edge.example:443?max-tcp-carriers=3",
            "quic://edge.example:443?allow-datagrams=true",
            "quic://edge.example:443?allow-datagrams=false",
            "quic://edge.example:443?max-datagram-payload-bytes=511",
            "quic://edge.example:443?max-datagram-payload-bytes=65001",
            "tcp://edge.example:443?port-rotation-interval-ms=5000",
            "quic://edge.example:443?port-rotation-interval-ms=5000",
            "quic://edge.example:443-444?port-rotation-interval-ms=4999",
        )
        invalid.forEach { endpoint -> assertNull(endpoint, MppPathParser.parse(endpoint)) }

        for (boolean in listOf(
            "backup",
            "expensive",
            "allow-bulk",
            "control-only",
            "allow-datagrams",
        )) {
            assertNull(MppPathParser.parse("tcp://edge.example:443?$boolean"))
            assertNull(MppPathParser.parse("tcp://edge.example:443?$boolean=yes"))
            assertNull(
                MppPathParser.parse(
                    "tcp://edge.example:443?$boolean=true&$boolean=false"
                )
            )
        }
    }

    @Test
    fun acceptsNativeNumericBoundariesAndDefaults() {
        assertNotNull(MppPathParser.parse("tcp://edge.example:443?initial-rate-bps=+1"))
        assertNotNull(MppPathParser.parse("tcp://edge.example:443?initial-rttvar-ms=000"))
        assertNotNull(
            MppPathParser.parse(
                "tcp://edge.example:443?initial-srtt-ms=4294967295&" +
                        "initial-rate-bps=18446744073709551615&max-tcp-carriers=65535"
            )
        )
        assertNotNull(
            MppPathParser.parse(
                "quic://edge.example:443-444?max-datagram-payload-bytes=512&" +
                        "port-rotation-interval-ms=4294967295"
            )
        )
        assertEquals(
            MppPathParser.DEFAULT_TCP_CARRIER_MAX,
            MppPathParser.parse("tcp://edge.example:443")!!.tcpCarrierMax,
        )
    }

    @Test
    fun rejectsEveryPreviousOptionSpelling() {
        val previousKeys = listOf(
            "source-ip",
            "srtt-ms",
            "jitter-ms",
            "rate-bps",
            "rate-kbps",
            "rate-mbps",
            "rate",
            "datagram-payload-limit",
            "tcp-carriers",
            "port-hop-interval-ms",
            "bulk-allowed",
            "probe-only",
            "no-udp",
        )
        previousKeys.forEach { key ->
            assertNull(key, MppPathParser.parse("tcp://edge.example:443?$key=true"))
        }
    }

    @Test
    fun validatorAcceptsOrderedPathsAndRejectsIdentityOrOldUriErrors() {
        val paths = listOf(
            MppPathConfig("wifi", "tcp://wifi.example:7443?max-tcp-carriers=4"),
            MppPathConfig("mobile", "quic://mobile.example:7443?expensive=true"),
            MppPathConfig(
                "backup",
                "tcp://backup.example:8443?backup=true&max-tcp-carriers=1",
            ),
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
                validConfig(listOf(MppPathConfig("old-uri", "udp://edge.example:7443")))
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
            MppPathConfig("quic-$index", "quic://edge-$index.example:7443")
        }
        assertNull(MppProfileValidator.validate(validConfig(sixtyFourQuicPaths)))
        assertEquals(
            MppValidationError.PATH_COUNT,
            MppProfileValidator.validate(
                validConfig(
                    sixtyFourQuicPaths + MppPathConfig(
                        "quic-65",
                        "quic://edge-65.example:7443",
                    )
                )
            ),
        )

        assertNull(
            MppProfileValidator.validate(
                validConfig(
                    listOf(
                        MppPathConfig("tcp", "tcp://edge.example:7443?max-tcp-carriers=63"),
                        MppPathConfig("quic", "quic://edge.example:7443"),
                    )
                )
            )
        )
        assertEquals(
            MppValidationError.PATH_CARRIER_LIMIT,
            MppProfileValidator.validate(
                validConfig(
                    listOf(
                        MppPathConfig("tcp", "tcp://edge.example:7443?max-tcp-carriers=64"),
                        MppPathConfig("quic", "quic://edge.example:7443"),
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
