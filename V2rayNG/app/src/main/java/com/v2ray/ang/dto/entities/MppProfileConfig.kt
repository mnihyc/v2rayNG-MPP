package com.v2ray.ang.dto.entities

/**
 * Android-facing MPP profile values.
 *
 * Editor-schema v2 profiles have one authoritative, syntax-preserving TOML document. Guided
 * values below are its cached native projection and must never be patched independently from the
 * document. Material fields contain padded standard Base64 in v1/v2; schema-zero profiles retain
 * their historical encodings until migrated without guessing their presentation.
 */
data class MppProfileConfig(
    /** Missing/zero identifies the legacy structured-or-raw dual representation. */
    val editorSchemaVersion: Int = LEGACY_EDITOR_SCHEMA_VERSION,
    /**
     * Syntax-valid editable TOML. Proxy bindings and remote credential material remain managed
     * placeholders; profile-local settings such as the management token persist here directly.
     */
    val editorToml: String = "",
    /**
     * Explicit native carrier paths. `null` identifies profiles saved before arbitrary path
     * support and keeps their legacy structured fields authoritative; an empty list is intentional.
     */
    val paths: List<MppPathConfig>? = null,
    /** `null` preserves profiles created before expert native tuning was exposed. */
    val advanced: MppAdvancedConfig? = null,
    /** Authoritative MPTUNNEL record threshold rendered into `[logging]`. */
    val logLevel: String = DEFAULT_LOG_LEVEL,
    /** Cached projection of `[routing].target_resolution`; null preserves compatibility omission. */
    val targetResolution: String? = null,
    val tcpEnabled: Boolean = true,
    val tcpPort: Int = DEFAULT_SERVER_PORT,
    val tcpCarrierCount: Int = DEFAULT_TCP_CARRIER_COUNT,
    val udpEnabled: Boolean = true,
    val udpPort: Int = DEFAULT_SERVER_PORT,
    val credentialId: String = DEFAULT_CREDENTIAL_ID,
    val principalId: String = DEFAULT_PRINCIPAL_ID,
    /** Padded standard Base64 for editor schemas v1/v2; literal schema-zero bytes otherwise. */
    val credentialSecret: String = "",
    val tlsServerName: String = DEFAULT_TLS_SERVER_NAME,
    /** Padded standard Base64 for editor schemas v1/v2; literal schema-zero PEM otherwise. */
    val pinnedCertificatePem: String = "",
    /** Padded standard Base64 for editor schemas v1/v2; schema-zero text or `base64:` otherwise. */
    val transportSecret: String = "",
    /** Editor view preference only. It never selects a second configuration authority. */
    val useRawToml: Boolean = false,
    /** Legacy raw document, read only while migrating editor-schema zero profiles. */
    val rawToml: String = "",
) {
    /** Returns explicit native paths, or the exact paths represented by the legacy fields. */
    fun effectivePaths(server: String): List<MppPathConfig> = paths ?: buildList {
        val endpointHost = endpointHost(server)
        if (tcpEnabled) {
            add(
                MppPathConfig(
                    name = "path-tcp",
                    endpoint = "tcp://$endpointHost:$tcpPort?max-tcp-carriers=$tcpCarrierCount",
                )
            )
        }
        if (udpEnabled) {
            add(
                MppPathConfig(
                    name = "path-quic",
                    endpoint = "quic://$endpointHost:$udpPort",
                )
            )
        }
    }

    fun primaryPort(): Int = when {
        tcpEnabled -> tcpPort
        udpEnabled -> udpPort
        else -> DEFAULT_SERVER_PORT
    }

    /** Editor-schema 1 remains an authoritative full-TOML document under v0.4.4. */
    fun withRawTomlMode(enabled: Boolean): MppProfileConfig = copy(
        useRawToml = enabled || editorSchemaVersion == PREVIOUS_EDITOR_SCHEMA_VERSION,
    )

    /** Avoid accidentally disclosing first-class material if a profile is ever stringified. */
    override fun toString(): String =
        "MppProfileConfig(" +
                "paths=${paths?.size ?: "<legacy>"}, " +
                "advanced=${advanced ?: "<native-defaults>"}, " +
                "logLevel=$logLevel, targetResolution=${targetResolution ?: "<compatibility>"}, " +
                "tcpEnabled=$tcpEnabled, tcpPort=$tcpPort, " +
                "tcpCarrierCount=$tcpCarrierCount, udpEnabled=$udpEnabled, udpPort=$udpPort, " +
                "credentialId=$credentialId, principalId=$principalId, " +
                "credentialSecret=<redacted>, tlsServerName=$tlsServerName, " +
                "pinnedCertificatePem=<redacted>, transportSecret=<redacted>, " +
                "editorSchemaVersion=$editorSchemaVersion, editorToml=<redacted>, " +
                "useRawToml=$useRawToml, rawToml=<redacted>)"

    companion object {
        const val DEFAULT_SERVER_PORT = 7443
        const val DEFAULT_TCP_CARRIER_COUNT = 3
        const val DEFAULT_CREDENTIAL_ID = "android-client"
        const val DEFAULT_PRINCIPAL_ID = "android"
        const val DEFAULT_TLS_SERVER_NAME = "mptunnel.example"
        const val DEFAULT_LOG_LEVEL = "info"
        const val TARGET_RESOLUTION_AS_IS = "as-is"
        const val TARGET_RESOLUTION_ROUTE_ONLY = "route-only"
        const val TARGET_RESOLUTION_FULL_RESOLVE = "full-resolve"
        const val LEGACY_EDITOR_SCHEMA_VERSION = 0
        const val PREVIOUS_EDITOR_SCHEMA_VERSION = 1
        const val CURRENT_EDITOR_SCHEMA_VERSION = 2

        fun usesCanonicalMaterialEncoding(schemaVersion: Int): Boolean =
            schemaVersion == PREVIOUS_EDITOR_SCHEMA_VERSION ||
                    schemaVersion == CURRENT_EDITOR_SCHEMA_VERSION

        val SUPPORTED_LOG_LEVELS = listOf("off", "error", "warn", "info", "debug")
        val SUPPORTED_TARGET_RESOLUTIONS = listOf(
            null,
            TARGET_RESOLUTION_AS_IS,
            TARGET_RESOLUTION_ROUTE_ONLY,
            TARGET_RESOLUTION_FULL_RESOLVE,
        )

        private fun endpointHost(server: String): String {
            val host = server.trim().removeSurrounding("[", "]")
            return if (':' in host) "[$host]" else host
        }
    }
}
