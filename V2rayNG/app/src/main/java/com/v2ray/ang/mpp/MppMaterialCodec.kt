package com.v2ray.ang.mpp

import android.util.Base64

/** Lossless persistence encoding for MPP material which may not be UTF-8 text. */
object MppMaterialCodec {
    const val BASE64_PREFIX = "base64:"

    fun encodeImportedBinary(content: ByteArray): String =
        BASE64_PREFIX + Base64.encodeToString(content, Base64.NO_WRAP)

    fun decode(value: String): ByteArray = if (value.startsWith(BASE64_PREFIX)) {
        Base64.decode(value.removePrefix(BASE64_PREFIX), Base64.DEFAULT)
    } else {
        value.toByteArray(Charsets.UTF_8)
    }
}
