package com.v2ray.ang.mpp

import com.v2ray.ang.dto.entities.MppAdvancedConfig
import com.v2ray.ang.dto.entities.MppProfileConfig
import java.util.UUID

enum class MppValidationError {
    PATH_REQUIRED,
    PATH_COUNT,
    PATH_NAME,
    PATH_ENDPOINT,
    PATH_CARRIER_LIMIT,
    ADVANCED_TUNING,
    TCP_PORT,
    UDP_PORT,
    TCP_CARRIER_COUNT,
    CREDENTIAL_ID,
    PRINCIPAL_ID,
    CREDENTIAL_SECRET,
    CERTIFICATE,
    TRANSPORT_SECRET,
    RAW_TOML,
    RAW_CREDENTIAL_TOKEN,
    RAW_CERTIFICATE_TOKEN,
    RAW_TRANSPORT_TOKEN,
    RAW_SOCKS_PORT_TOKEN,
    RAW_LOCAL_AUTH_TOKENS,
}

object MppProfileValidator {
    private val policyId = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

    fun validate(config: MppProfileConfig): MppValidationError? {
        if (config.useRawToml) return validateRaw(config)
        validatePaths(config)?.let { return it }
        if (!isValidAdvancedTuning(config.advanced)) {
            return MppValidationError.ADVANCED_TUNING
        }
        if (!policyId.matches(config.credentialId)) return MppValidationError.CREDENTIAL_ID
        if (!policyId.matches(config.principalId)) return MppValidationError.PRINCIPAL_ID
        if (!isValidCredentialSecret(config.credentialSecret)) {
            return MppValidationError.CREDENTIAL_SECRET
        }
        if (!isSinglePemCertificate(config.pinnedCertificatePem)) {
            return MppValidationError.CERTIFICATE
        }
        if (config.transportSecret.isNotBlank() && !isValidTransportSecret(config.transportSecret)) {
            return MppValidationError.TRANSPORT_SECRET
        }
        return null
    }

    private fun validatePaths(config: MppProfileConfig): MppValidationError? {
        val explicit = config.paths
        if (explicit == null) {
            if (!config.tcpEnabled && !config.udpEnabled) return MppValidationError.PATH_REQUIRED
            if (config.tcpEnabled && config.tcpPort !in 1..65535) {
                return MppValidationError.TCP_PORT
            }
            if (config.udpEnabled && config.udpPort !in 1..65535) {
                return MppValidationError.UDP_PORT
            }
            if (config.tcpEnabled && config.tcpCarrierCount !in 1..64) {
                return MppValidationError.TCP_CARRIER_COUNT
            }
            val slots = (if (config.tcpEnabled) config.tcpCarrierCount else 0) +
                    (if (config.udpEnabled) 1 else 0)
            return MppValidationError.PATH_CARRIER_LIMIT.takeIf { slots > MAX_CARRIER_SLOTS }
        }

        if (explicit.isEmpty()) return MppValidationError.PATH_REQUIRED
        if (explicit.size > MAX_PATH_ENTRIES) return MppValidationError.PATH_COUNT
        val names = HashSet<String>(explicit.size)
        var slots = 0
        for (path in explicit) {
            if (!MppPathParser.isCanonicalName(path.name) || !names.add(path.name)) {
                return MppValidationError.PATH_NAME
            }
            val parsed = MppPathParser.parse(path.endpoint)
                ?: return MppValidationError.PATH_ENDPOINT
            slots += parsed.carrierSlots
            if (slots > MAX_CARRIER_SLOTS) return MppValidationError.PATH_CARRIER_LIMIT
        }
        return null
    }

    private fun validateRaw(config: MppProfileConfig): MppValidationError? {
        val raw = config.rawToml
        if (raw.isBlank()) return MppValidationError.RAW_TOML
        if (raw.tokenCount(MppConfigRenderer.CREDENTIAL_MATERIAL_TOKEN) != 1) {
            return MppValidationError.RAW_CREDENTIAL_TOKEN
        }
        if (raw.tokenCount(MppConfigRenderer.CERTIFICATE_MATERIAL_TOKEN) != 1) {
            return MppValidationError.RAW_CERTIFICATE_TOKEN
        }
        val transportTokenCount = raw.tokenCount(
            MppConfigRenderer.TRANSPORT_SECRET_MATERIAL_TOKEN
        )
        if ((config.transportSecret.isNotBlank() && transportTokenCount != 1) ||
            (config.transportSecret.isBlank() && transportTokenCount != 0)
        ) {
            return MppValidationError.RAW_TRANSPORT_TOKEN
        }
        if (raw.tokenCount(MppConfigRenderer.SOCKS_PORT_TOKEN) != 1) {
            return MppValidationError.RAW_SOCKS_PORT_TOKEN
        }
        if (raw.tokenCount(MppConfigRenderer.LOCAL_USER_DEFINITION_TOKEN) != 1 ||
            raw.tokenCount(MppConfigRenderer.LOCAL_USER_BINDING_TOKEN) != 1 ||
            MppConfigRenderer.LOCAL_PROXY_PASSWORD_MATERIAL_TOKEN in raw
        ) {
            return MppValidationError.RAW_LOCAL_AUTH_TOKENS
        }
        if (!isValidCredentialSecret(config.credentialSecret)) {
            return MppValidationError.CREDENTIAL_SECRET
        }
        if (!isSinglePemCertificate(config.pinnedCertificatePem)) {
            return MppValidationError.CERTIFICATE
        }
        if (config.transportSecret.isNotBlank() && !isValidTransportSecret(config.transportSecret)) {
            return MppValidationError.TRANSPORT_SECRET
        }
        return null
    }

    private fun isValidAdvancedTuning(value: MppAdvancedConfig?): Boolean {
        if (value == null) return true
        return value.pathProbeIntervalMs > 0L &&
                value.pathProbeTimeoutMs > 0L &&
                value.extraTrafficHintPercent in
                0..MppAdvancedConfig.MAX_EXTRA_TRAFFIC_HINT_PERCENT &&
                value.authFreshnessWindowSeconds > 0L &&
                value.sessionRetentionTimeoutMs > 0L &&
                value.tcpHeartbeatIntervalMs > 0L &&
                value.tcpHeartbeatTimeoutMs >= value.tcpHeartbeatIntervalMs &&
                value.quicKeepAliveIntervalMs > 0L &&
                value.quicIdleTimeoutMs > value.quicKeepAliveIntervalMs &&
                value.quicIdleTimeoutMs <= MppAdvancedConfig.MAX_QUIC_IDLE_TIMEOUT_MS
    }

    private fun isValidCredentialSecret(value: String): Boolean {
        if (value.toByteArray(Charsets.UTF_8).size >= 32) return true
        return runCatching { UUID.fromString(value.trim()) }.isSuccess
    }

    private fun isSinglePemCertificate(value: String): Boolean {
        val beginCount = Regex("-----BEGIN CERTIFICATE-----").findAll(value).count()
        val endCount = Regex("-----END CERTIFICATE-----").findAll(value).count()
        return beginCount == 1 && endCount == 1
    }

    private fun isValidTransportSecret(value: String): Boolean =
        runCatching { MppMaterialCodec.decode(value).size == 32 }.getOrDefault(false)

    private fun String.tokenCount(token: String): Int = split(token).size - 1

    private const val MAX_PATH_ENTRIES = 64
    private const val MAX_CARRIER_SLOTS = 64
}
