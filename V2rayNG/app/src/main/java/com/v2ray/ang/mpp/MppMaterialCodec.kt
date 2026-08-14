package com.v2ray.ang.mpp

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

/** Exact, non-secret persistence and presentation codecs for MPP material bytes. */
object MppMaterialCodec {
    /** Prefix emitted by v2rayNG-MPP versions before canonical Base64-only persistence. */
    const val LEGACY_BASE64_PREFIX = "base64:"

    private val canonicalBase64 = Regex(
        "^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$"
    )
    private val hexadecimal = Regex("^[0-9A-Fa-f]*$")

    /** Stores exact bytes as padded, standard RFC 4648 Base64 without line wrapping. */
    fun encodeStored(content: ByteArray): String = Base64.getEncoder().encodeToString(content)

    /** Decodes only the canonical persisted representation; no whitespace or missing padding. */
    fun decodeStored(value: String): ByteArray {
        require(canonicalBase64.matches(value) && value.length % 4 == 0) {
            "MPP material is not canonical standard Base64"
        }
        val decoded = Base64.getDecoder().decode(value)
        require(encodeStored(decoded) == value) { "MPP material is not canonical standard Base64" }
        return decoded
    }

    /**
     * Preserves exact bytes stored by older profiles. Hex-looking text remains UTF-8 text; it is
     * deliberately never auto-detected as hexadecimal.
     */
    fun decodeLegacy(value: String, acceptedLegacyBinaryPrefix: Boolean = false): ByteArray {
        if (acceptedLegacyBinaryPrefix && value.startsWith(LEGACY_BASE64_PREFIX)) {
            return decodeStored(value.removePrefix(LEGACY_BASE64_PREFIX))
        }
        return value.toByteArray(Charsets.UTF_8)
    }

    fun encodeHex(content: ByteArray): String = buildString(content.size * 2) {
        content.forEach { byte -> append((byte.toInt() and 0xff).toString(16).padStart(2, '0')) }
    }

    /** Decodes strict contiguous hexadecimal. Whitespace, prefixes, separators, and odd lengths fail. */
    fun decodeHex(value: String): ByteArray {
        require(value.length % 2 == 0 && hexadecimal.matches(value)) {
            "MPP material is not strict hexadecimal"
        }
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    fun encodeUtf8(value: String): ByteArray = value.toByteArray(Charsets.UTF_8)

    /** Certificates are textual PEM. Reject malformed UTF-8 instead of replacing bytes. */
    fun decodeUtf8(content: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(content))
        .toString()
}
