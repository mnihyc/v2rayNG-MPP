package com.v2ray.ang.mpp

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.OutboundTrafficStat
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.util.LogUtil
import org.json.JSONObject

/** Host callback used by Rust before an MPTUNNEL egress socket is connected. */
fun interface SocketProtector {
    fun protect(fd: Int): Boolean
}

/**
 * Small Kotlin boundary around the embedded MPTUNNEL cdylib.
 *
 * Profile materials remain first-class values in MMKV and are passed as bytes. Rust owns any
 * short-lived, app-private materialization required by the shared TOML loader; no path is exposed
 * through the profile model or editor.
 */
object MptunnelNative {
    private const val START_TIMEOUT_MS = 15_000L
    private const val STOP_TIMEOUT_MS = 5_000L

    private val loadFailure: Throwable? = try {
        System.loadLibrary("mptunnel")
        null
    } catch (failure: Throwable) {
        failure
    }

    private var lastToPeerBytes = 0L
    private var lastFromPeerBytes = 0L

    @JvmStatic
    private external fun nativeStart(
        noBackupRoot: String,
        profileId: String,
        configTemplate: String,
        materials: Array<ByteArray>,
        protector: SocketProtector,
        readyTimeoutMs: Long,
    ): Boolean

    @JvmStatic
    private external fun nativeStop(timeoutMs: Long): Boolean

    @JvmStatic
    private external fun nativeIsRunning(): Boolean

    @JvmStatic
    private external fun nativeState(): String

    @JvmStatic
    private external fun nativeVersion(): String

    @JvmStatic
    private external fun nativeStatsJson(): String

    @JvmStatic
    private external fun nativeDeleteProfile(noBackupRoot: String, profileId: String): Boolean

    @Synchronized
    fun start(
        context: Context,
        profileId: String,
        profile: ProfileItem,
        socksPort: Int,
        proxyUsername: String?,
        proxyPassword: String?,
        protector: SocketProtector,
    ): Boolean {
        requireLoaded()
        val mpp = requireNotNull(profile.mpp) { "MPP profile data is missing" }
        MppProfileValidator.validate(mpp)?.let { error("Invalid MPP profile: ${it.name}") }

        val username = proxyUsername.orEmpty()
        val password = proxyPassword.orEmpty()
        val localAuthEnabled = username.isNotBlank() && password.isNotBlank()
        val configTemplate = MppConfigRenderer.renderRuntime(
            profile = profile,
            socksPort = socksPort,
            proxyUsername = if (localAuthEnabled) username else "",
            hasProxyPassword = localAuthEnabled,
        )
        val materials = arrayOf(
            mpp.credentialSecret.toByteArray(Charsets.UTF_8),
            mpp.pinnedCertificatePem.toByteArray(Charsets.UTF_8),
            mpp.transportSecret.takeIf(String::isNotBlank)
                ?.let(MppMaterialCodec::decode)
                ?: byteArrayOf(),
            if (localAuthEnabled) password.toByteArray(Charsets.UTF_8) else byteArrayOf(),
        )

        lastToPeerBytes = 0L
        lastFromPeerBytes = 0L
        return try {
            nativeStart(
                context.noBackupFilesDir.absolutePath,
                profileId,
                configTemplate,
                materials,
                protector,
                START_TIMEOUT_MS,
            )
        } catch (failure: Throwable) {
            // A readiness timeout still requests cooperative shutdown on the Rust side. Finish
            // that teardown before Android closes the service which owns the protect callback.
            runCatching { nativeStop(STOP_TIMEOUT_MS) }
            throw IllegalStateException(
                failure.message?.takeUnless(String::isBlank) ?: "MPTUNNEL failed to start",
                failure,
            )
        } finally {
            materials.forEach { material -> material.fill(0) }
        }
    }

    @Synchronized
    fun stop(): Boolean {
        requireLoaded()
        return nativeStop(STOP_TIMEOUT_MS)
    }

    fun isRunning(): Boolean = runCatching {
        requireLoaded()
        nativeIsRunning()
    }.getOrDefault(false)

    fun state(): String = runCatching {
        requireLoaded()
        nativeState()
    }.getOrDefault("unavailable")

    fun version(): String {
        requireLoaded()
        return nativeVersion()
    }

    /** Returns deltas because v2rayNG's notification expects reset-on-query counters. */
    @Synchronized
    fun queryOutboundTrafficStats(): List<OutboundTrafficStat> {
        if (!isRunning()) return emptyList()
        return try {
            val io = JSONObject(nativeStatsJson()).getJSONObject("io")
            val toPeer = io.optLong("to_peer_bytes", lastToPeerBytes)
            val fromPeer = io.optLong("from_peer_bytes", lastFromPeerBytes)
            val uplink = (toPeer - lastToPeerBytes).coerceAtLeast(0L)
            val downlink = (fromPeer - lastFromPeerBytes).coerceAtLeast(0L)
            lastToPeerBytes = toPeer
            lastFromPeerBytes = fromPeer
            listOf(
                OutboundTrafficStat(AppConfig.TAG_PROXY, AppConfig.UPLINK, uplink),
                OutboundTrafficStat(AppConfig.TAG_PROXY, AppConfig.DOWNLINK, downlink),
            )
        } catch (failure: Exception) {
            LogUtil.e(AppConfig.TAG, "MPTUNNEL stats query failed", failure)
            emptyList()
        }
    }

    fun deleteProfile(context: Context, profileId: String): Boolean {
        requireLoaded()
        return nativeDeleteProfile(context.noBackupFilesDir.absolutePath, profileId)
    }

    private fun requireLoaded() {
        loadFailure?.let {
            throw IllegalStateException("MPTUNNEL native library is unavailable", it)
        }
    }
}
