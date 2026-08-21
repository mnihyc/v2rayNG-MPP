package com.v2ray.ang.ui.server

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import com.v2ray.ang.AppConfig.DEFAULT_PORT
import com.v2ray.ang.AppConfig.REALITY
import com.v2ray.ang.AppConfig.WIREGUARD_LOCAL_ADDRESS_V4
import com.v2ray.ang.AppConfig.WIREGUARD_LOCAL_MTU
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.MppProfileConfig
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.NetworkType
import com.v2ray.ang.extension.nullIfBlank
import com.v2ray.ang.mpp.MppParsedPath
import com.v2ray.ang.mpp.MppPathParser
import com.v2ray.ang.mpp.MppMaterialCodec
import com.v2ray.ang.util.JsonUtil

class ServerUiState(
    configType: EConfigType,
    remarks: String = "",
    address: String = "",
    port: String = DEFAULT_PORT.toString(),
    password: String = "",
    method: String = "",
    flow: String = "",
    encryption: String = "",
    username: String = "",
    secretKey: String = "",
    publicKey: String = "",
    preSharedKey: String = "",
    reserved: String = "0,0,0",
    localAddress: String = WIREGUARD_LOCAL_ADDRESS_V4,
    mtu: String = WIREGUARD_LOCAL_MTU,
    obfsPassword: String = "",
    portHopping: String = "",
    portHoppingInterval: String = "",
    bandwidthDown: String = "",
    bandwidthUp: String = "",
    network: String = NetworkType.TCP.type,
    headerType: String = "none",
    mode: String = "",
    xhttpMode: String = "",
    serviceName: String = "",
    authority: String = "",
    host: String = "",
    path: String = "",
    xhttpExtra: String = "",
    finalMask: String = "",
    seed: String = "",
    kcpMtu: String = "",
    kcpTti: String = "",
    browserDialerMode: String = "",
    streamSecurity: String = "",
    sni: String = "",
    allowInsecure: Boolean = false,
    fingerPrint: String = "",
    alpn: String = "",
    publicKeyReality: String = "",
    shortId: String = "",
    spiderX: String = "",
    mldsa65Verify: String = "",
    echConfigList: String = "",
    verifyPeerCertByName: String = "",
    pinnedCA256: String = "",
    isFetchingCert: Boolean = false,
    mppConfig: MppProfileConfig = MppProfileConfig(),
    mppCredentialHex: String = "",
    mppCertificatePem: String = "",
    mppTransportHex: String = "",
    mppCredentialDecodeFailed: Boolean = false,
    mppCertificateDecodeFailed: Boolean = false,
    mppTransportDecodeFailed: Boolean = false,
    mppGuidedDraftInvalid: Boolean = false,
) {
    var configType by mutableStateOf(configType)
    var remarks by mutableStateOf(remarks)
    var address by mutableStateOf(address)
    var port by mutableStateOf(port)
    var password by mutableStateOf(password)
    var method by mutableStateOf(method)
    var flow by mutableStateOf(flow)
    var encryption by mutableStateOf(encryption)
    var username by mutableStateOf(username)
    var secretKey by mutableStateOf(secretKey)
    var publicKey by mutableStateOf(publicKey)
    var preSharedKey by mutableStateOf(preSharedKey)
    var reserved by mutableStateOf(reserved)
    var localAddress by mutableStateOf(localAddress)
    var mtu by mutableStateOf(mtu)
    var obfsPassword by mutableStateOf(obfsPassword)
    var portHopping by mutableStateOf(portHopping)
    var portHoppingInterval by mutableStateOf(portHoppingInterval)
    var bandwidthDown by mutableStateOf(bandwidthDown)
    var bandwidthUp by mutableStateOf(bandwidthUp)
    var network by mutableStateOf(network)
    var headerType by mutableStateOf(headerType)
    var mode by mutableStateOf(mode)
    var xhttpMode by mutableStateOf(xhttpMode)
    var serviceName by mutableStateOf(serviceName)
    var authority by mutableStateOf(authority)
    var host by mutableStateOf(host)
    var path by mutableStateOf(path)
    var xhttpExtra by mutableStateOf(xhttpExtra)
    var finalMask by mutableStateOf(finalMask)
    var seed by mutableStateOf(seed)
    var kcpMtu by mutableStateOf(kcpMtu)
    var kcpTti by mutableStateOf(kcpTti)
    var browserDialerMode by mutableStateOf(browserDialerMode)
    var streamSecurity by mutableStateOf(streamSecurity)
    var sni by mutableStateOf(sni)
    var allowInsecure by mutableStateOf(allowInsecure)
    var fingerPrint by mutableStateOf(fingerPrint)
    var alpn by mutableStateOf(alpn)
    var publicKeyReality by mutableStateOf(publicKeyReality)
    var shortId by mutableStateOf(shortId)
    var spiderX by mutableStateOf(spiderX)
    var mldsa65Verify by mutableStateOf(mldsa65Verify)
    var echConfigList by mutableStateOf(echConfigList)
    var verifyPeerCertByName by mutableStateOf(verifyPeerCertByName)
    var pinnedCA256 by mutableStateOf(pinnedCA256)
    var isFetchingCert by mutableStateOf(isFetchingCert)
    var mppConfig by mutableStateOf(mppConfig)
    var mppCredentialHex by mutableStateOf(mppCredentialHex)
    var mppCertificatePem by mutableStateOf(mppCertificatePem)
    var mppTransportHex by mutableStateOf(mppTransportHex)
    var mppCredentialDecodeFailed by mutableStateOf(mppCredentialDecodeFailed)
    var mppCertificateDecodeFailed by mutableStateOf(mppCertificateDecodeFailed)
    var mppTransportDecodeFailed by mutableStateOf(mppTransportDecodeFailed)
    var mppGuidedDraftInvalid by mutableStateOf(mppGuidedDraftInvalid)

    /** Keep a non-projectable, migrated document authoritative in the full TOML editor. */
    internal fun keepMppCustomTomlAsRawAuthority(document: String) {
        mppConfig = mppConfig.copy(
            editorSchemaVersion = MppProfileConfig.CURRENT_EDITOR_SCHEMA_VERSION,
            editorToml = document,
            useRawToml = true,
            rawToml = "",
        )
    }

    /** A document outside the guided shape remains editable; Save checks only its TOML syntax. */
    internal fun migrateMppEditorOrKeepRaw(
        document: String,
        migrate: (String) -> String,
    ): String? = runCatching { migrate(document) }.getOrElse {
        keepMppCustomTomlAsRawAuthority(document)
        null
    }

    fun toProfileItem(initialConfig: ProfileItem): ProfileItem {
        val isVmess = configType == EConfigType.VMESS
        val isVless = configType == EConfigType.VLESS
        val isShadowsocks = configType == EConfigType.SHADOWSOCKS
        val isSocksOrHttp = configType == EConfigType.SOCKS || configType == EConfigType.HTTP
        val isWireguard = configType == EConfigType.WIREGUARD
        val isHysteria2 = configType == EConfigType.HYSTERIA2
        val isMpp = configType == EConfigType.MPP
        val hasExplicitMppPaths = isMpp && mppConfig.paths != null
        val explicitMppSummary = if (hasExplicitMppPaths) {
            firstValidExplicitMppPath(mppConfig)
        } else {
            null
        }

        return initialConfig.copy(
            configType = configType,
            remarks = remarks,
            server = if (hasExplicitMppPaths) explicitMppSummary?.host.orEmpty() else address,
            serverPort = when {
                hasExplicitMppPaths -> explicitMppSummary?.firstPort?.toString().orEmpty()
                isMpp -> mppConfig.primaryPort().toString()
                else -> port
            },
            password = password,
            method = when {
                isVmess || isShadowsocks -> method
                isVless -> encryption
                else -> null
            },
            flow = if (isVless) flow else null,
            username = if (isSocksOrHttp) username else null,
            secretKey = if (isWireguard) secretKey else null,
            publicKey = when {
                isWireguard -> publicKey
                streamSecurity == REALITY -> publicKeyReality
                else -> null
            },
            preSharedKey = if (isWireguard) preSharedKey else null,
            reserved = if (isWireguard) reserved else null,
            localAddress = if (isWireguard) localAddress else null,
            mtu = if (isWireguard) mtu.toIntOrNull() else null,
            obfsPassword = if (isHysteria2) obfsPassword else null,
            portHopping = if (isHysteria2) portHopping else null,
            portHoppingInterval = if (isHysteria2) portHoppingInterval else null,
            bandwidthDown = if (isHysteria2) bandwidthDown else null,
            bandwidthUp = if (isHysteria2) bandwidthUp else null,
            network = network,
            headerType = headerType,
            mode = mode.nullIfBlank(),
            xhttpMode = xhttpMode.nullIfBlank(),
            serviceName = serviceName.nullIfBlank(),
            authority = authority.nullIfBlank(),
            host = host,
            path = path,
            xhttpExtra = xhttpExtra.nullIfBlank(),
            finalMask = finalMask.nullIfBlank(),
            seed = seed.nullIfBlank(),
            kcpMtu = kcpMtu.toIntOrNull(),
            kcpTti = kcpTti.toIntOrNull(),
            browserDialerMode = if (network in listOf(NetworkType.WS.type, NetworkType.XHTTP.type)) {
                browserDialerMode.nullIfBlank()
            } else {
                null
            },
            security = streamSecurity,
            sni = sni,
            insecure = allowInsecure,
            fingerPrint = fingerPrint,
            alpn = alpn,
            shortId = shortId,
            spiderX = spiderX,
            mldsa65Verify = mldsa65Verify,
            echConfigList = echConfigList,
            verifyPeerCertByName = verifyPeerCertByName,
            pinnedCA256 = pinnedCA256,
            mpp = if (isMpp) canonicalMppConfig() else null,
        )
    }

    private fun canonicalMppConfig(): MppProfileConfig {
        val credential = runCatching { MppMaterialCodec.decodeHex(mppCredentialHex) }.getOrNull()
        val transport = runCatching { MppMaterialCodec.decodeHex(mppTransportHex) }.getOrNull()
        return mppConfig.copy(
            editorSchemaVersion = MppProfileConfig.CURRENT_EDITOR_SCHEMA_VERSION,
            credentialSecret = if (mppCredentialDecodeFailed) {
                mppConfig.credentialSecret
            } else {
                credential?.let(MppMaterialCodec::encodeStored).orEmpty()
            },
            pinnedCertificatePem = if (mppCertificateDecodeFailed) {
                mppConfig.pinnedCertificatePem
            } else {
                MppMaterialCodec.encodeStored(MppMaterialCodec.encodeUtf8(mppCertificatePem))
            },
            transportSecret = when {
                mppTransportDecodeFailed -> mppConfig.transportSecret
                mppTransportHex.isBlank() -> ""
                transport != null -> MppMaterialCodec.encodeStored(transport)
                // Preserve an invalid, non-empty draft as an invalid canonical value. Treating it
                // as an absent optional secret would silently discard a user's malformed edit.
                else -> INVALID_CANONICAL_MATERIAL
            },
            rawToml = "",
        )
    }

    companion object {
        fun fromProfileItem(
            initialConfig: ProfileItem
        ): ServerUiState {
            val mpp = initialConfig.mpp
            val hasExplicitMppPaths = initialConfig.configType == EConfigType.MPP && mpp?.paths != null
            val explicitMppSummary = if (hasExplicitMppPaths) {
                firstValidExplicitMppPath(mpp)
            } else {
                null
            }
            val resolvedMpp = mpp ?: MppProfileConfig(
                editorSchemaVersion = MppProfileConfig.CURRENT_EDITOR_SCHEMA_VERSION,
                targetResolution = MppProfileConfig.TARGET_RESOLUTION_AS_IS,
            )
            val materialDraft = decodeMppMaterialDraft(resolvedMpp)
            return ServerUiState(
                configType = initialConfig.configType,
                remarks = initialConfig.remarks,
                address = if (hasExplicitMppPaths) {
                    explicitMppSummary?.host.orEmpty()
                } else {
                    initialConfig.server ?: ""
                },
                port = if (hasExplicitMppPaths) {
                    explicitMppSummary?.firstPort?.toString().orEmpty()
                } else {
                    initialConfig.serverPort ?: DEFAULT_PORT.toString()
                },
                password = initialConfig.password ?: "",
                method = initialConfig.method ?: "",
                flow = initialConfig.flow ?: "",
                encryption = initialConfig.method ?: "",
                username = initialConfig.username ?: "",
                secretKey = initialConfig.secretKey ?: "",
                publicKey = initialConfig.publicKey ?: "",
                preSharedKey = initialConfig.preSharedKey ?: "",
                reserved = initialConfig.reserved ?: "0,0,0",
                localAddress = initialConfig.localAddress ?: WIREGUARD_LOCAL_ADDRESS_V4,
                mtu = initialConfig.mtu?.toString() ?: WIREGUARD_LOCAL_MTU,
                obfsPassword = initialConfig.obfsPassword ?: "",
                portHopping = initialConfig.portHopping ?: "",
                portHoppingInterval = initialConfig.portHoppingInterval ?: "",
                bandwidthDown = initialConfig.bandwidthDown ?: "",
                bandwidthUp = initialConfig.bandwidthUp ?: "",
                network = initialConfig.network ?: NetworkType.TCP.type,
                headerType = initialConfig.headerType ?: "none",
                mode = initialConfig.mode ?: "",
                xhttpMode = initialConfig.xhttpMode ?: "",
                serviceName = initialConfig.serviceName ?: "",
                authority = initialConfig.authority ?: "",
                host = initialConfig.host ?: "",
                path = initialConfig.path ?: "",
                xhttpExtra = initialConfig.xhttpExtra ?: "",
                finalMask = initialConfig.finalMask ?: "",
                seed = initialConfig.seed ?: "",
                kcpMtu = initialConfig.kcpMtu?.toString() ?: "",
                kcpTti = initialConfig.kcpTti?.toString() ?: "",
                browserDialerMode = initialConfig.browserDialerMode ?: "",
                streamSecurity = initialConfig.security ?: "",
                sni = initialConfig.sni ?: "",
                allowInsecure = initialConfig.insecure == true,
                fingerPrint = initialConfig.fingerPrint ?: "",
                alpn = initialConfig.alpn ?: "",
                publicKeyReality = initialConfig.publicKey ?: "",
                shortId = initialConfig.shortId ?: "",
                spiderX = initialConfig.spiderX ?: "",
                mldsa65Verify = initialConfig.mldsa65Verify ?: "",
                echConfigList = initialConfig.echConfigList ?: "",
                verifyPeerCertByName = initialConfig.verifyPeerCertByName ?: "",
                pinnedCA256 = initialConfig.pinnedCA256 ?: "",
                mppConfig = resolvedMpp,
                mppCredentialHex = materialDraft.credentialHex,
                mppCertificatePem = materialDraft.certificatePem,
                mppTransportHex = materialDraft.transportHex,
                mppCredentialDecodeFailed = materialDraft.credentialFailed,
                mppCertificateDecodeFailed = materialDraft.certificateFailed,
                mppTransportDecodeFailed = materialDraft.transportFailed,
            )
        }

        fun from(
            initialConfig: ProfileItem
        ): ServerUiState = fromProfileItem(initialConfig)

        val Saver: Saver<ServerUiState, String> = Saver(
            save = { state ->
                val profile = state.toProfileItem(ProfileItem.create(state.configType))
                val mpp = state.mppConfig
                if (mpp.editorSchemaVersion != MppProfileConfig.CURRENT_EDITOR_SCHEMA_VERSION &&
                    mpp.useRawToml && mpp.rawToml.isNotBlank()
                ) {
                    // Native migration is the only operation allowed to replace legacy raw
                    // authority. A lifecycle save can run before that migration completes.
                    profile.mpp = mpp
                }
                JsonUtil.toJson(profile)
            },
            restore = { saved ->
                JsonUtil.fromJsonSafe(saved, ProfileItem::class.java)?.let {
                    fromProfileItem(it)
                }
            }
        )

        private fun firstValidExplicitMppPath(config: MppProfileConfig): MppParsedPath? =
            config.paths
                ?.asSequence()
                ?.mapNotNull { path -> MppPathParser.parse(path.endpoint) }
                ?.firstOrNull()

        private fun decodeMppMaterialDraft(config: MppProfileConfig): MaterialDraft {
            fun bytes(value: String, legacyBinaryPrefix: Boolean = false): DecodedMaterial =
                runCatching {
                    if (config.editorSchemaVersion == MppProfileConfig.CURRENT_EDITOR_SCHEMA_VERSION) {
                        MppMaterialCodec.decodeStored(value)
                    } else {
                        MppMaterialCodec.decodeLegacy(value, legacyBinaryPrefix)
                    }
                }.fold(
                    onSuccess = { DecodedMaterial(it, failed = false) },
                    onFailure = { DecodedMaterial(byteArrayOf(), failed = true) },
                )

            val credential = bytes(config.credentialSecret)
            val certificate = bytes(config.pinnedCertificatePem)
            val transport = config.transportSecret.takeIf(String::isNotBlank)
                ?.let { bytes(it, legacyBinaryPrefix = true) }
                ?: DecodedMaterial(byteArrayOf(), failed = false)
            var certificateFailed = certificate.failed
            val certificatePem = runCatching { MppMaterialCodec.decodeUtf8(certificate.bytes) }
                .getOrElse {
                    certificateFailed = true
                    ""
                }
            return MaterialDraft(
                credentialHex = MppMaterialCodec.encodeHex(credential.bytes),
                certificatePem = certificatePem,
                transportHex = MppMaterialCodec.encodeHex(transport.bytes),
                credentialFailed = credential.failed,
                certificateFailed = certificateFailed,
                transportFailed = transport.failed,
            )
        }

        private data class DecodedMaterial(
            val bytes: ByteArray,
            val failed: Boolean,
        )

        private data class MaterialDraft(
            val credentialHex: String,
            val certificatePem: String,
            val transportHex: String,
            val credentialFailed: Boolean,
            val certificateFailed: Boolean,
            val transportFailed: Boolean,
        )

        private const val INVALID_CANONICAL_MATERIAL = "!"
    }
}
