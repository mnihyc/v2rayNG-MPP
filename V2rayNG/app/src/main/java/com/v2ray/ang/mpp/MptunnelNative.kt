package com.v2ray.ang.mpp

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.OutboundTrafficStat
import com.v2ray.ang.dto.entities.MppProfileConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.JsonUtil
import org.json.JSONObject

/** Host callback used by Rust before an MPTUNNEL egress socket is connected. */
fun interface SocketProtector {
    fun protect(fd: Int): Boolean
}

/**
 * Small Kotlin boundary around the embedded MPTUNNEL cdylib.
 *
 * The editable document contains only managed placeholders. Rust finalizes those placeholders
 * syntax-aware from Base64 bindings, then starts from the resulting self-contained TOML.
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
    private var legacyMaterialCleanupComplete = false

    @JvmStatic
    private external fun nativeStart(
        configToml: String,
        protector: SocketProtector,
        readyTimeoutMs: Long,
    ): Boolean

    @JvmStatic
    private external fun nativeProjectEditor(configToml: String): String

    @JvmStatic
    private external fun nativeMigrateEditor(configToml: String): String

    @JvmStatic
    private external fun nativePatchEditor(configToml: String, projectionJson: String): String

    @JvmStatic
    private external fun nativeFinalizeEditor(configToml: String, bindingsJson: String): String

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

    @Synchronized
    fun start(
        context: Context,
        profile: ProfileItem,
        socksPort: Int,
        proxyUsername: String?,
        proxyPassword: String?,
        protector: SocketProtector,
    ): Boolean {
        if (!legacyMaterialCleanupComplete) {
            cleanupLegacyMaterialRoot(context)
            legacyMaterialCleanupComplete = true
        }
        requireLoaded()
        val mpp = requireNotNull(profile.mpp) { "MPP profile data is missing" }
        MppProfileValidator.validate(mpp)?.let { error("Invalid MPP profile: ${it.name}") }

        val username = proxyUsername.orEmpty()
        val password = proxyPassword.orEmpty()
        val localAuthEnabled = username.isNotBlank() && password.isNotBlank()
        val editorToml = canonicalEditorDocument(profile)
        val bindings = finalizeBindings(
            config = mpp,
            socksPort = socksPort,
            localAuth = if (localAuthEnabled) {
                MppFinalizeBindings.LocalAuth(
                    username = username,
                    passwordBase64 = MppMaterialCodec.encodeStored(
                        password.toByteArray(Charsets.UTF_8)
                    ),
                )
            } else {
                null
            },
        )
        val configToml = nativeFinalizeEditor(editorToml, MppEditorJson.encode(bindings))

        lastToPeerBytes = 0L
        lastFromPeerBytes = 0L
        return try {
            nativeStart(
                configToml,
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
        }
    }

    fun projectEditor(configToml: String): MppEditorProjection {
        requireLoaded()
        val projection = JsonUtil.fromJsonSafe(
            nativeProjectEditor(configToml),
            MppEditorProjection::class.java,
        ) ?: error("MPTUNNEL returned an invalid editor projection")
        require(projection.schemaVersion == MppEditorProjection.SCHEMA_VERSION) {
            "unsupported MPTUNNEL editor projection"
        }
        return projection
    }

    fun migrateEditor(configToml: String): String {
        requireLoaded()
        return nativeMigrateEditor(configToml)
    }

    fun patchEditor(configToml: String, projection: MppEditorProjection): String {
        requireLoaded()
        require(projection.schemaVersion == MppEditorProjection.SCHEMA_VERSION) {
            "unsupported MPTUNNEL editor projection"
        }
        return nativePatchEditor(configToml, MppEditorJson.encode(projection))
    }

    /** Syntax-aware raw-save validation without forcing the document into a guided projection. */
    fun validateEditor(config: MppProfileConfig) {
        requireLoaded()
        require(config.editorSchemaVersion == MppProfileConfig.CURRENT_EDITOR_SCHEMA_VERSION)
        nativeFinalizeEditor(
            config.editorToml,
            MppEditorJson.encode(
                finalizeBindings(
                    config = config,
                    socksPort = VALIDATION_SOCKS_PORT,
                    localAuth = null,
                )
            ),
        )
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

    private fun requireLoaded() {
        loadFailure?.let {
            throw IllegalStateException("MPTUNNEL native library is unavailable", it)
        }
    }

    /** Removes only the private material tree used by pre-inline Android bridge releases. */
    internal fun cleanupLegacyMaterialRoot(context: Context) {
        val root = context.noBackupFilesDir.resolve(LEGACY_MATERIAL_DIRECTORY)
        if (legacyNodeType(root) == LegacyNodeType.MISSING) return
        removeLegacyNode(root)
        check(legacyNodeType(root) == LegacyNodeType.MISSING) {
            LEGACY_CLEANUP_FAILURE
        }
    }

    private fun removeLegacyNode(node: java.io.File) {
        if (legacyNodeType(node) == LegacyNodeType.DIRECTORY) {
            val children = node.listFiles() ?: error(LEGACY_CLEANUP_FAILURE)
            children.forEach(::removeLegacyNode)
        }
        if (!node.delete() && legacyNodeType(node) != LegacyNodeType.MISSING) {
            error(LEGACY_CLEANUP_FAILURE)
        }
    }

    /** lstat keeps a legacy symlink confined to deleting the link itself. */
    private fun legacyNodeType(node: java.io.File): LegacyNodeType = try {
        if (OsConstants.S_ISDIR(Os.lstat(node.absolutePath).st_mode)) {
            LegacyNodeType.DIRECTORY
        } else {
            LegacyNodeType.OTHER
        }
    } catch (failure: ErrnoException) {
        if (failure.errno == OsConstants.ENOENT) {
            LegacyNodeType.MISSING
        } else {
            // Never propagate an OS exception whose message contains the private path.
            error(LEGACY_CLEANUP_FAILURE)
        }
    } catch (_: SecurityException) {
        error(LEGACY_CLEANUP_FAILURE)
    }

    private fun canonicalEditorDocument(profile: ProfileItem): String {
        val config = requireNotNull(profile.mpp)
        if (config.editorSchemaVersion == MppProfileConfig.CURRENT_EDITOR_SCHEMA_VERSION &&
            config.editorToml.isNotBlank()
        ) {
            return config.editorToml
        }
        if (config.useRawToml && config.rawToml.isNotBlank()) {
            return migrateEditor(config.rawToml)
        }
        val legacyDocument = MppConfigRenderer.renderEditableTemplate(
            profile.server.orEmpty(),
            config,
        )
        val projection = projectEditor(legacyDocument)
        return patchEditor(legacyDocument, projection)
    }

    private fun canonicalMaterial(
        value: String,
        editorSchemaVersion: Int,
        acceptedLegacyBinaryPrefix: Boolean = false,
    ): String = if (editorSchemaVersion == MppProfileConfig.CURRENT_EDITOR_SCHEMA_VERSION) {
        MppMaterialCodec.encodeStored(MppMaterialCodec.decodeStored(value))
    } else {
        MppMaterialCodec.encodeStored(
            MppMaterialCodec.decodeLegacy(value, acceptedLegacyBinaryPrefix)
        )
    }

    private fun finalizeBindings(
        config: MppProfileConfig,
        socksPort: Int,
        localAuth: MppFinalizeBindings.LocalAuth?,
    ) = MppFinalizeBindings(
        socksPort = socksPort,
        credentialBase64 = canonicalMaterial(
            config.credentialSecret,
            config.editorSchemaVersion,
        ),
        pinnedCertificateBase64 = canonicalMaterial(
            config.pinnedCertificatePem,
            config.editorSchemaVersion,
        ),
        transportSecretBase64 = config.transportSecret.takeIf(String::isNotBlank)?.let {
            canonicalMaterial(
                it,
                config.editorSchemaVersion,
                acceptedLegacyBinaryPrefix = true,
            )
        },
        localAuth = localAuth,
    )

    private const val VALIDATION_SOCKS_PORT = 10808
    private const val LEGACY_MATERIAL_DIRECTORY = "mptunnel"
    private const val LEGACY_CLEANUP_FAILURE =
        "Unable to remove legacy MPTUNNEL material storage"

    private enum class LegacyNodeType {
        MISSING,
        DIRECTORY,
        OTHER,
    }
}
