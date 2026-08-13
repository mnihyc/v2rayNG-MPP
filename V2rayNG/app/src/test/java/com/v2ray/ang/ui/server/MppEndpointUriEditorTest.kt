package com.v2ray.ang.ui.server

import com.v2ray.ang.mpp.MppPathParser
import com.v2ray.ang.mpp.MppPathUnderlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MppEndpointUriEditorTest {

    @Test
    fun parseAndRenderPreserveRawPortsAndOrderedOptions() {
        val uri = "tcp://edge.example:07000-07999?backup=true&rate-bps=+1000&" +
                "expensive=false&probe-only"

        val parsed = MppEndpointUriEditor.parse(uri)!!

        assertEquals(MppPathUnderlay.TCP, parsed.underlay)
        assertEquals("edge.example", parsed.host)
        assertEquals("07000-07999", parsed.ports)
        assertEquals(
            listOf(
                MppEditableEndpointOption("backup", "true"),
                MppEditableEndpointOption("rate-bps", "+1000"),
                MppEditableEndpointOption("expensive", "false"),
                MppEditableEndpointOption("probe-only", null),
            ),
            parsed.options,
        )
        assertEquals(uri, MppEndpointUriEditor.render(parsed))
    }

    @Test
    fun scalarEditCanonicalizesOnlyItsTarget() {
        val uri = "tcp://edge.example:443?backup=true&srtt-ms=+20&" +
                "rate-bps=+1000&expensive=false&probe-only"

        assertEquals(
            "tcp://edge.example:443?backup=true&srtt-ms=30&" +
                    "rate-bps=+1000&expensive=false&probe-only",
            MppEndpointUriEditor.withScalarOption(uri, "srtt-ms", "30"),
        )
        assertEquals(
            "tcp://edge.example:443?backup=true&rate-bps=+1000&" +
                    "expensive=false&probe-only",
            MppEndpointUriEditor.withScalarOption(uri, "srtt-ms", null),
        )
        assertEquals(
            "$uri&jitter-ms=5",
            MppEndpointUriEditor.withScalarOption(uri, "jitter-ms", "5"),
        )
        assertEquals(uri, MppEndpointUriEditor.withScalarOption(uri, "jitter-ms", null))
    }

    @Test
    fun tcpToUdpDropsOnlyTransportIncompatibleOptions() {
        val uri = "tcp://edge.example:7000-7999?backup=true&tcp-carriers=1-5&" +
                "rate-mbps=25&no-udp=false&port-hop-interval-ms=45000&probe-only"

        val rewritten = MppEndpointUriEditor.withUnderlay(uri, MppPathUnderlay.UDP)

        assertEquals(
            "udp://edge.example:7000-7999?backup=true&rate-mbps=25&" +
                    "port-hop-interval-ms=45000&probe-only",
            rewritten,
        )
        assertEquals(uri, MppEndpointUriEditor.withUnderlay(uri, MppPathUnderlay.TCP))
        assertEquals(MppPathUnderlay.UDP, MppPathParser.parse(rewritten!!)!!.underlay)
    }

    @Test
    fun booleanEditUsesBareFlagAndRemovesEveryTargetOccurrence() {
        val uri = "tcp://edge.example:443?backup=false&rate-mbps=25&" +
                "backup=true&expensive=false&probe-only"

        assertEquals(
            "tcp://edge.example:443?backup&rate-mbps=25&expensive=false&probe-only",
            MppEndpointUriEditor.withBooleanOption(uri, "backup", true),
        )
        assertEquals(
            "tcp://edge.example:443?rate-mbps=25&expensive=false&probe-only",
            MppEndpointUriEditor.withBooleanOption(uri, "backup", false),
        )
        assertEquals(
            "$uri&bulk-allowed",
            MppEndpointUriEditor.withBooleanOption(uri, "bulk-allowed", true),
        )
    }

    @Test
    fun rateEditTreatsEveryRateSpellingAsOneOptionGroup() {
        val variants = listOf(
            "rate=unknown",
            "rate-bps=+1",
            "rate-kbps=2",
            "rate-mbps=3",
        )

        variants.forEach { variant ->
            val uri = "tcp://edge.example:443?backup&$variant&probe-only"
            assertEquals(
                "tcp://edge.example:443?backup&rate-mbps=42&probe-only",
                MppEndpointUriEditor.withRateOption(uri, "rate-mbps", "42"),
            )
            assertEquals(
                "tcp://edge.example:443?backup&probe-only",
                MppEndpointUriEditor.withRateOption(uri, null, null),
            )
        }

        val withoutRate = "udp://edge.example:443?backup"
        assertEquals(
            "$withoutRate&rate=unlimited",
            MppEndpointUriEditor.withRateOption(withoutRate, "rate", "unlimited"),
        )
    }

    @Test
    fun hostAndPortEditsBracketIpv6AndMayExposeInvalidTargetText() {
        val uri = "udp://[2001:db8::1]:00443-00444?rate=unlimited"
        val parsed = MppEndpointUriEditor.parse(uri)!!

        assertEquals("2001:db8::1", parsed.host)
        assertEquals("00443-00444", parsed.ports)
        assertEquals(
            "udp://[2001:db8::2]:00443-00444?rate=unlimited",
            MppEndpointUriEditor.withHost(uri, "2001:db8::2"),
        )
        assertEquals(
            "udp://[2001:db8::1]:5000-6000?rate=unlimited",
            MppEndpointUriEditor.withPorts(uri, "5000-6000"),
        )

        val invalidTarget = MppEndpointUriEditor.withPorts(uri, "6000-5000")
        assertEquals("udp://[2001:db8::1]:6000-5000?rate=unlimited", invalidTarget)
        assertNull(MppPathParser.parse(invalidTarget!!))
    }

    @Test
    fun everyRewriteReturnsNullForInvalidSourceSoCallerCanKeepIt() {
        val invalid = "tcp://edge.example:0?backup"

        assertNull(MppEndpointUriEditor.parse(invalid))
        assertNull(MppEndpointUriEditor.withUnderlay(invalid, MppPathUnderlay.UDP))
        assertNull(MppEndpointUriEditor.withHost(invalid, "other.example"))
        assertNull(MppEndpointUriEditor.withPorts(invalid, "443"))
        assertNull(MppEndpointUriEditor.withScalarOption(invalid, "srtt-ms", "10"))
        assertNull(MppEndpointUriEditor.withBooleanOption(invalid, "backup", true))
        assertNull(MppEndpointUriEditor.withRateOption(invalid, "rate-mbps", "10"))
        assertEquals(
            invalid,
            MppEndpointUriEditor.withHost(invalid, "other.example") ?: invalid,
        )
    }

    @Test
    fun optedInDraftSourceAllowsIncrementalRangeAndScalarTyping() {
        val validPort = "tcp://edge.example:7443?backup"
        val partialRange = MppEndpointUriEditor.withPorts(validPort, "7443-")!!
        assertEquals("tcp://edge.example:7443-?backup", partialRange)
        assertNull(MppPathParser.parse(partialRange))
        assertNull(MppEndpointUriEditor.withPorts(partialRange, "7443-75"))

        val continuedRange = MppEndpointUriEditor.withPorts(
            partialRange,
            "7443-75",
            allowDraftSource = true,
        )!!
        assertEquals("tcp://edge.example:7443-75?backup", continuedRange)
        assertNull(MppPathParser.parse(continuedRange))

        val finishedRange = MppEndpointUriEditor.withPorts(
            continuedRange,
            "7443-7543",
            allowDraftSource = true,
        )!!
        assertEquals("tcp://edge.example:7443-7543?backup", finishedRange)
        assertEquals("7443-7543", MppEndpointUriEditor.parse(finishedRange)!!.ports)

        val partialIp = MppEndpointUriEditor.withScalarOption(
            validPort,
            "source-ip",
            "1",
        )!!
        assertEquals("tcp://edge.example:7443?backup&source-ip=1", partialIp)
        assertNull(MppPathParser.parse(partialIp))

        val continuedIp = MppEndpointUriEditor.withScalarOption(
            partialIp,
            "source-ip",
            "192.",
            allowDraftSource = true,
        )!!
        assertEquals("tcp://edge.example:7443?backup&source-ip=192.", continuedIp)
        assertNull(MppPathParser.parse(continuedIp))

        val finishedIp = MppEndpointUriEditor.withScalarOption(
            continuedIp,
            "source-ip",
            "192.0.2.1",
            allowDraftSource = true,
        )!!
        assertEquals("tcp://edge.example:7443?backup&source-ip=192.0.2.1", finishedIp)
        assertEquals("192.0.2.1", MppEndpointUriEditor.parse(finishedIp)!!.options.last().value)
    }
}
