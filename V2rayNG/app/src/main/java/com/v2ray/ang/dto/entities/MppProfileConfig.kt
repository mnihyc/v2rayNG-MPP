package com.v2ray.ang.dto.entities

/**
 * Android-facing MPP profile values.
 *
 * Material fields contain user-managed values, never filesystem paths. The runtime bridge is
 * responsible for materializing them in private app storage immediately before MPTunnel starts.
 */
data class MppProfileConfig(
    /**
     * Explicit native carrier paths. `null` identifies profiles saved before arbitrary path
     * support and keeps their legacy TCP/UDP fields authoritative; an empty list is intentional.
     */
    val paths: List<MppPathConfig>? = null,
    /** `null` preserves profiles created before expert native tuning was exposed. */
    val advanced: MppAdvancedConfig? = null,
    val tcpEnabled: Boolean = true,
    val tcpPort: Int = DEFAULT_SERVER_PORT,
    val tcpCarrierCount: Int = DEFAULT_TCP_CARRIER_COUNT,
    val udpEnabled: Boolean = true,
    val udpPort: Int = DEFAULT_SERVER_PORT,
    val credentialId: String = DEFAULT_CREDENTIAL_ID,
    val principalId: String = DEFAULT_PRINCIPAL_ID,
    val credentialSecret: String = "",
    val tlsServerName: String = DEFAULT_TLS_SERVER_NAME,
    val pinnedCertificatePem: String = "",
    val transportSecret: String = "",
    val useRawToml: Boolean = false,
    val rawToml: String = "",
) {
    /** Returns explicit native paths, or the exact paths represented by the legacy fields. */
    fun effectivePaths(server: String): List<MppPathConfig> = paths ?: buildList {
        val endpointHost = endpointHost(server)
        if (tcpEnabled) {
            add(
                MppPathConfig(
                    name = "path-tcp",
                    endpoint = "tcp://$endpointHost:$tcpPort?tcp-carriers=1-$tcpCarrierCount",
                )
            )
        }
        if (udpEnabled) {
            add(
                MppPathConfig(
                    name = "path-udp",
                    endpoint = "udp://$endpointHost:$udpPort",
                )
            )
        }
    }

    fun primaryPort(): Int = when {
        tcpEnabled -> tcpPort
        udpEnabled -> udpPort
        else -> DEFAULT_SERVER_PORT
    }

    /** Avoid accidentally disclosing first-class material if a profile is ever stringified. */
    override fun toString(): String =
        "MppProfileConfig(" +
                "paths=${paths?.size ?: "<legacy>"}, " +
                "advanced=${advanced ?: "<native-defaults>"}, " +
                "tcpEnabled=$tcpEnabled, tcpPort=$tcpPort, " +
                "tcpCarrierCount=$tcpCarrierCount, udpEnabled=$udpEnabled, udpPort=$udpPort, " +
                "credentialId=$credentialId, principalId=$principalId, " +
                "credentialSecret=<redacted>, tlsServerName=$tlsServerName, " +
                "pinnedCertificatePem=<redacted>, transportSecret=<redacted>, " +
                "useRawToml=$useRawToml, rawToml=<redacted>)"

    companion object {
        const val DEFAULT_SERVER_PORT = 7443
        const val DEFAULT_TCP_CARRIER_COUNT = 3
        const val DEFAULT_CREDENTIAL_ID = "android-client"
        const val DEFAULT_PRINCIPAL_ID = "android"
        const val DEFAULT_TLS_SERVER_NAME = "mptunnel.example"

        private fun endpointHost(server: String): String {
            val host = server.trim().removeSurrounding("[", "]")
            return if (':' in host) "[$host]" else host
        }
    }
}
