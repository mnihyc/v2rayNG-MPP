package com.v2ray.ang.ui.server

import com.v2ray.ang.mpp.MppPathParser
import com.v2ray.ang.mpp.MppPathUnderlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MppEndpointUriEditorTest {

    @Test
    fun parseAndRenderPreserveCanonicalPortsAndOrderedExplicitOptions() {
        val uri = "tcp://edge.example:7000-7999?backup=true&initial-rate-bps=+1000&" +
                "expensive=false&control-only=true"

        val parsed = MppEndpointUriEditor.parse(uri)!!

        assertEquals(MppPathUnderlay.TCP, parsed.underlay)
        assertEquals("edge.example", parsed.host)
        assertEquals("7000-7999", parsed.ports)
        assertEquals(
            listOf(
                MppEditableEndpointOption("backup", "true"),
                MppEditableEndpointOption("initial-rate-bps", "+1000"),
                MppEditableEndpointOption("expensive", "false"),
                MppEditableEndpointOption("control-only", "true"),
            ),
            parsed.options,
        )
        assertEquals(uri, MppEndpointUriEditor.render(parsed))
    }

    @Test
    fun scalarEditChangesOnlyItsCanonicalTarget() {
        val uri = "tcp://edge.example:443?backup=true&initial-srtt-ms=+20&" +
                "initial-rate-bps=+1000&expensive=false&control-only=true"

        assertEquals(
            "tcp://edge.example:443?backup=true&initial-srtt-ms=30&" +
                    "initial-rate-bps=+1000&expensive=false&control-only=true",
            MppEndpointUriEditor.withScalarOption(uri, "initial-srtt-ms", "30"),
        )
        assertEquals(
            "tcp://edge.example:443?backup=true&initial-rate-bps=+1000&" +
                    "expensive=false&control-only=true",
            MppEndpointUriEditor.withScalarOption(uri, "initial-srtt-ms", null),
        )
        assertEquals(
            "$uri&initial-rttvar-ms=5",
            MppEndpointUriEditor.withScalarOption(uri, "initial-rttvar-ms", "5"),
        )
        assertEquals(
            uri,
            MppEndpointUriEditor.withScalarOption(uri, "initial-rttvar-ms", null),
        )
    }

    @Test
    fun transportChangeDropsOnlyOptionsInapplicableToItsTarget() {
        val tcp = "tcp://edge.example:7000-7999?backup=true&max-tcp-carriers=5&" +
                "initial-rate-mbps=25&allow-datagrams=false&" +
                "port-rotation-interval-ms=45000&control-only=true"

        val quic = MppEndpointUriEditor.withUnderlay(tcp, MppPathUnderlay.QUIC)

        assertEquals(
            "quic://edge.example:7000-7999?backup=true&initial-rate-mbps=25&" +
                    "port-rotation-interval-ms=45000&control-only=true",
            quic,
        )
        assertEquals(tcp, MppEndpointUriEditor.withUnderlay(tcp, MppPathUnderlay.TCP))
        assertEquals(MppPathUnderlay.QUIC, MppPathParser.parse(quic!!)!!.underlay)

        val quicWithPayload =
            "quic://edge.example:7443?max-datagram-payload-bytes=1400&backup=false"
        assertEquals(
            "tcp://edge.example:7443?backup=false",
            MppEndpointUriEditor.withUnderlay(quicWithPayload, MppPathUnderlay.TCP),
        )
    }

    @Test
    fun editorRefusesTransportAndRangeInapplicableOptions() {
        val tcp = "tcp://edge.example:443"
        val quic = "quic://edge.example:443"

        assertNull(
            MppEndpointUriEditor.withScalarOption(
                tcp,
                "max-datagram-payload-bytes",
                "1400",
            )
        )
        assertNull(
            MppEndpointUriEditor.withScalarOption(quic, "max-tcp-carriers", "3")
        )
        assertNull(
            MppEndpointUriEditor.withScalarOption(
                quic,
                "port-rotation-interval-ms",
                "300000",
            )
        )
        assertNull(
            MppEndpointUriEditor.withBooleanOption(quic, "allow-datagrams", false)
        )
    }

    @Test
    fun booleanEditAlwaysRendersExplicitValueAndRepairsTargetDuplicates() {
        val uri = "tcp://edge.example:443?backup=false&initial-rate-mbps=25&" +
                "expensive=false&control-only=true"

        assertEquals(
            "tcp://edge.example:443?backup=true&initial-rate-mbps=25&" +
                    "expensive=false&control-only=true",
            MppEndpointUriEditor.withBooleanOption(uri, "backup", true),
        )
        assertEquals(
            "tcp://edge.example:443?backup=false&initial-rate-mbps=25&" +
                    "expensive=false&control-only=true",
            MppEndpointUriEditor.withBooleanOption(uri, "backup", false),
        )
        assertEquals(
            "$uri&allow-bulk=false",
            MppEndpointUriEditor.withBooleanOption(uri, "allow-bulk", false),
        )

        val duplicateDraft = "tcp://edge.example:443?backup=false&backup=true"
        assertEquals(
            "tcp://edge.example:443?backup=true",
            MppEndpointUriEditor.withBooleanOption(
                duplicateDraft,
                "backup",
                true,
                allowDraftSource = true,
            ),
        )
    }

    @Test
    fun rateEditTreatsEveryCanonicalRateFormAsOneOptionGroup() {
        val variants = listOf(
            "initial-rate=unknown",
            "initial-rate-bps=+1",
            "initial-rate-kbps=2",
            "initial-rate-mbps=3",
        )

        variants.forEach { variant ->
            val uri = "tcp://edge.example:443?backup=true&$variant&control-only=true"
            assertEquals(
                "tcp://edge.example:443?backup=true&initial-rate-mbps=42&control-only=true",
                MppEndpointUriEditor.withRateOption(uri, "initial-rate-mbps", "42"),
            )
            assertEquals(
                "tcp://edge.example:443?backup=true&control-only=true",
                MppEndpointUriEditor.withRateOption(uri, null, null),
            )
        }

        val withoutRate = "quic://edge.example:443?backup=true"
        assertEquals(
            "$withoutRate&initial-rate=unlimited",
            MppEndpointUriEditor.withRateOption(
                withoutRate,
                "initial-rate",
                "unlimited",
            ),
        )
    }

    @Test
    fun hostAndPortEditsBracketIpv6AndMayExposeInvalidTargetText() {
        val uri = "quic://[2001:db8::1]:443-444?initial-rate=unlimited"
        val parsed = MppEndpointUriEditor.parse(uri)!!

        assertEquals("2001:db8::1", parsed.host)
        assertEquals("443-444", parsed.ports)
        assertEquals(
            "quic://[2001:db8::2]:443-444?initial-rate=unlimited",
            MppEndpointUriEditor.withHost(uri, "2001:db8::2"),
        )
        assertEquals(
            "quic://[2001:db8::1]:5000-6000?initial-rate=unlimited",
            MppEndpointUriEditor.withPorts(uri, "5000-6000"),
        )

        val invalidTarget = MppEndpointUriEditor.withPorts(uri, "6000-5000")
        assertEquals(
            "quic://[2001:db8::1]:6000-5000?initial-rate=unlimited",
            invalidTarget,
        )
        assertNull(MppPathParser.parse(invalidTarget!!))
    }

    @Test
    fun everyRewriteReturnsNullForInvalidOrPreviousGrammarSource() {
        val invalid = "tcp://edge.example:0?backup=true"

        assertNull(MppEndpointUriEditor.parse(invalid))
        assertNull(MppEndpointUriEditor.withUnderlay(invalid, MppPathUnderlay.QUIC))
        assertNull(MppEndpointUriEditor.withHost(invalid, "other.example"))
        assertNull(MppEndpointUriEditor.withPorts(invalid, "443"))
        assertNull(MppEndpointUriEditor.withScalarOption(invalid, "initial-srtt-ms", "10"))
        assertNull(MppEndpointUriEditor.withBooleanOption(invalid, "backup", true))
        assertNull(MppEndpointUriEditor.withRateOption(invalid, "initial-rate-mbps", "10"))

        val previous = "udp://edge.example:443?backup=true"
        assertNull(MppEndpointUriEditor.parse(previous))
        assertNull(MppEndpointUriEditor.parseDraft(previous))
        assertNull(MppEndpointUriEditor.withHost(previous, "other.example"))
    }

    @Test
    fun optedInDraftSourceAllowsIncrementalRangeAndScalarTyping() {
        val validPort = "tcp://edge.example:7443?backup=true"
        val partialRange = MppEndpointUriEditor.withPorts(validPort, "7443-")!!
        assertEquals("tcp://edge.example:7443-?backup=true", partialRange)
        assertNull(MppPathParser.parse(partialRange))
        assertNull(MppEndpointUriEditor.withPorts(partialRange, "7443-75"))

        val continuedRange = MppEndpointUriEditor.withPorts(
            partialRange,
            "7443-75",
            allowDraftSource = true,
        )!!
        assertEquals("tcp://edge.example:7443-75?backup=true", continuedRange)
        assertNull(MppPathParser.parse(continuedRange))

        val finishedRange = MppEndpointUriEditor.withPorts(
            continuedRange,
            "7443-7543",
            allowDraftSource = true,
        )!!
        assertEquals("tcp://edge.example:7443-7543?backup=true", finishedRange)
        assertEquals("7443-7543", MppEndpointUriEditor.parse(finishedRange)!!.ports)

        val partialAddress = MppEndpointUriEditor.withScalarOption(
            validPort,
            "source-address",
            "1",
        )!!
        assertEquals("tcp://edge.example:7443?backup=true&source-address=1", partialAddress)
        assertNull(MppPathParser.parse(partialAddress))

        val continuedAddress = MppEndpointUriEditor.withScalarOption(
            partialAddress,
            "source-address",
            "192.",
            allowDraftSource = true,
        )!!
        assertEquals(
            "tcp://edge.example:7443?backup=true&source-address=192.",
            continuedAddress,
        )
        assertNull(MppPathParser.parse(continuedAddress))

        val finishedAddress = MppEndpointUriEditor.withScalarOption(
            continuedAddress,
            "source-address",
            "192.0.2.1",
            allowDraftSource = true,
        )!!
        assertEquals(
            "tcp://edge.example:7443?backup=true&source-address=192.0.2.1",
            finishedAddress,
        )
        assertEquals(
            "192.0.2.1",
            MppEndpointUriEditor.parse(finishedAddress)!!.options.last().value,
        )
    }
}
