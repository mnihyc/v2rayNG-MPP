package com.v2ray.ang.dto.entities

/** One native MPTUNNEL carrier path, preserving the complete endpoint URI verbatim. */
data class MppPathConfig(
    val name: String = "",
    val endpoint: String = "",
)
