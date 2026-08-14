package com.v2ray.ang.mpp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MppMaterialCodecTest {

    @Test
    fun canonicalBase64AndHexRoundTripExactBytes() {
        val bytes = byteArrayOf(0x00, 0x01, 0x7f, 0x80.toByte(), 0xff.toByte())

        assertEquals("AAF/gP8=", MppMaterialCodec.encodeStored(bytes))
        assertArrayEquals(bytes, MppMaterialCodec.decodeStored("AAF/gP8="))
        assertEquals("00017f80ff", MppMaterialCodec.encodeHex(bytes))
        assertArrayEquals(bytes, MppMaterialCodec.decodeHex("00017F80fF"))
    }

    @Test
    fun strictDecodersRejectAmbiguousOrMalformedInputs() {
        listOf("0", "0x00", "00 01", "00\n").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                MppMaterialCodec.decodeHex(value)
            }
        }
        listOf("Zg", "Zg=", "Z g==", "Zg==\n", "DDD===").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                MppMaterialCodec.decodeStored(value)
            }
        }
    }

    @Test
    fun explicitHexAndUtf8InterpretationsNeverAutoDetect() {
        val input = "deadbeef"
        assertEquals("deadbeef", MppMaterialCodec.encodeHex(MppMaterialCodec.decodeHex(input)))
        assertEquals(
            "6465616462656566",
            MppMaterialCodec.encodeHex(MppMaterialCodec.encodeUtf8(input)),
        )

        val legacy = MppMaterialCodec.decodeLegacy(input)
        assertEquals("6465616462656566", MppMaterialCodec.encodeHex(legacy))
    }

    @Test
    fun legacyBinaryPrefixAndPemTextPreserveExactBytes() {
        assertArrayEquals(
            byteArrayOf(0x00, 0xff.toByte()),
            MppMaterialCodec.decodeLegacy("base64:AP8=", acceptedLegacyBinaryPrefix = true),
        )
        val pem = "-----BEGIN CERTIFICATE-----\r\nZHVtbXk=\r\n" +
                "-----END CERTIFICATE-----\r\n"
        assertEquals(pem, MppMaterialCodec.decodeUtf8(MppMaterialCodec.encodeUtf8(pem)))
    }
}
