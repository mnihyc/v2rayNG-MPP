package com.v2ray.ang.mpp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.type
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.MppProfileConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.ui.server.ServerUiState
import com.v2ray.ang.util.LogUtil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.regex.Pattern
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MptunnelNativeInstrumentedTest {

    @After
    fun stopNativeRuntime() {
        if (MptunnelNative.isRunning()) MptunnelNative.stop()
    }

    @Test
    fun legacyRawMigrationPreservesUnknownTomlAndManagedSemantics() {
        val legacy = """
            # preserve-this-comment
            [[credentials]]
            credential_id = "android-client"
            principal_id = "android"
            secret = { from = "file", path = "@mptunnel-profile-credential@" }

            @mptunnel-local-user-definition@

            [[inbounds]]
            name = "local-mixed"
            protocol = "mixed"
            listen = ["127.0.0.1:@mptunnel-socks-port@"]
            @mptunnel-local-user-binding@

            [[outbounds]]
            name = "remote-mpp"
            protocol = "mpp"
            paths = [{ name = "primary", endpoint = "tcp://127.0.0.1:7443" }]

            [outbounds.security]
            credential_id = "android-client"
            tls_pinned_certificate_file = "@mptunnel-profile-certificate@"
            transport_secret_file = "@mptunnel-profile-transport-secret@"

            [custom_unknown]
            retained = 42
        """.trimIndent()

        val migrated = MptunnelNative.migrateEditor(legacy)

        assertTrue(migrated.contains("# preserve-this-comment"))
        assertTrue(migrated.contains("[custom_unknown]"))
        assertTrue(migrated.contains("retained = 42"))
        assertTrue(migrated.contains("from = \"managed\""))
        assertTrue(migrated.contains("id = \"credential\""))
        assertTrue(migrated.contains("id = \"pinned-certificate\""))
        assertTrue(migrated.contains("id = \"transport-secret\""))
        assertFalse(migrated.contains("_file"))
        assertFalse(migrated.contains("@mptunnel-profile-"))
    }

    @Test
    fun releasedMpp4RouteMigratesAndValidatesWithoutManualEditing() {
        val legacy = MppProfileConfig(
            credentialSecret = "0123456789abcdef0123456789abcdef",
            pinnedCertificatePem = TEST_CERTIFICATE,
        )
        val releasedDocument = MppConfigRenderer
            .renderEditableTemplate("127.0.0.1", legacy)
            .replace(
                "name = \"default\"\noutbound = \"remote-mpp\"",
                "name = \"default\"\naction = \"outbound\" # keep-release-comment\n" +
                        "outbound = \"remote-mpp\"",
            )
        assertTrue(releasedDocument.contains("action = \"outbound\""))

        val migrated = MptunnelNative.migrateEditor(releasedDocument)
        assertFalse(migrated.contains("action ="))
        assertTrue(migrated.contains("decision = \"allow\" # keep-release-comment"))
        assertEquals(migrated, MptunnelNative.migrateEditor(migrated))

        val canonical = legacy.copy(
            editorSchemaVersion = MppProfileConfig.CURRENT_EDITOR_SCHEMA_VERSION,
            editorToml = releasedDocument,
            useRawToml = true,
            credentialSecret = MppMaterialCodec.encodeStored(
                MppMaterialCodec.encodeUtf8(legacy.credentialSecret)
            ),
            pinnedCertificatePem = MppMaterialCodec.encodeStored(
                MppMaterialCodec.encodeUtf8(legacy.pinnedCertificatePem)
            ),
        )
        MptunnelNative.validateEditor(canonical)

        val releasedProfile = ProfileItem(configType = EConfigType.MPP, mpp = canonical)
        val rawEditorState = ServerUiState.fromProfileItem(releasedProfile)
        rawEditorState.keepMppCustomTomlAsRawAuthority(migrated)
        assertEquals(migrated, rawEditorState.mppConfig.editorToml)
        assertEquals(
            migrated,
            rawEditorState.toProfileItem(releasedProfile).mpp?.editorToml,
        )
    }

    @Test
    fun guidedPatchPreservesUnknownTomlAndComments() {
        val legacy = MppProfileConfig(
            credentialSecret = "0123456789abcdef0123456789abcdef",
            pinnedCertificatePem = TEST_CERTIFICATE,
        )
        val document = MppConfigRenderer.renderEditableTemplate("127.0.0.1", legacy) +
                "\n# keep-guided-comment\n[custom_unknown]\nretained = 42\n"
        val projection = MptunnelNative.projectEditor(document)

        val patched = MptunnelNative.patchEditor(
            document,
            projection.copy(
                logLevel = "debug",
                credentialId = "patched-client",
            ),
        )

        assertTrue(patched.contains("# keep-guided-comment"))
        assertTrue(patched.contains("[custom_unknown]"))
        assertTrue(patched.contains("retained = 42"))
        assertEquals(
            "patched-client",
            MptunnelNative.projectEditor(patched).credentialId,
        )
        assertEquals("debug", MptunnelNative.projectEditor(patched).logLevel)
        assertTrue(patched.startsWith("[logging]\nlevel = \"debug\"\n"))
    }

    @Test
    fun defaultProfilePersistsSelectedLogLevelThroughOrdinaryUiJourney() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val remarks = "mpp-ui-${SystemClock.uptimeMillis()}"
        val launcherIntent = requireNotNull(
            context.packageManager.getLaunchIntentForPackage(context.packageName)
        ) { "target package has no launcher activity" }.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        )

        try {
            // Start exactly as the launcher does; every profile edit below is a visible UI action.
            context.startActivity(launcherIntent)
            allowNotificationPermissionThroughVisibleUi(
                device,
                context.packageName,
                context.getString(R.string.acc_add),
            )

            clickVisibleLabel(
                device,
                context.getString(R.string.acc_add),
            )
            clickVisibleLabel(
                device,
                context.getString(R.string.menu_item_import_config_manually_mpp),
                scrollForwardWhenMissing = true,
            )

            typeIntoVisibleField(
                device,
                context.getString(R.string.server_lab_remarks),
                remarks,
            )
            assertOrdinaryLogLevelSelectorVisible(
                device = device,
                title = context.getString(R.string.server_mpp_log_level),
                selectedLevel = MppProfileConfig.DEFAULT_LOG_LEVEL,
            )
            clickVisibleLabel(
                device,
                context.getString(R.string.server_mpp_log_level),
            )
            clickVisibleLabel(device, "debug")
            assertOrdinaryLogLevelSelectorVisible(
                device = device,
                title = context.getString(R.string.server_mpp_log_level),
                selectedLevel = "debug",
            )

            pasteUtf8MaterialThroughVisibleAction(
                device,
                clipboard,
                context.getString(R.string.server_mpp_credential_secret),
                context.getString(R.string.server_mpp_paste_as_text),
                UI_CREDENTIAL_TEXT,
            )
            pasteIntoVisibleField(
                device,
                clipboard,
                context.getString(R.string.server_mpp_pinned_certificate),
                TEST_CERTIFICATE,
            )
            pasteUtf8MaterialThroughVisibleAction(
                device,
                clipboard,
                context.getString(R.string.server_mpp_transport_secret),
                context.getString(R.string.server_mpp_paste_as_text),
                UI_TRANSPORT_SECRET_TEXT,
            )

            clickVisibleLabel(device, context.getString(R.string.acc_save))
            waitForVisibleLabel(
                device,
                remarks,
                scrollForwardWhenMissing = true,
            )
            clickProfileEdit(device, remarks, context.getString(R.string.acc_edit))

            assertOrdinaryLogLevelSelectorVisible(
                device = device,
                title = context.getString(R.string.server_mpp_log_level),
                selectedLevel = "debug",
            )
        } finally {
            clipboard.clearPrimaryClip()
            repeat(2) {
                device.pressBack()
                SystemClock.sleep(100L)
            }
        }
    }

    @Test
    fun canonicalEditorValidationRejectsInvalidPreservedNativeField() {
        val legacy = MppProfileConfig(
            credentialSecret = "0123456789abcdef0123456789abcdef",
            pinnedCertificatePem = TEST_CERTIFICATE,
        )
        val document = MppConfigRenderer.renderEditableTemplate("127.0.0.1", legacy)
            .replace("level = \"info\"", "level = 42")
        val canonical = legacy.copy(
            editorSchemaVersion = MppProfileConfig.CURRENT_EDITOR_SCHEMA_VERSION,
            editorToml = document,
            credentialSecret = MppMaterialCodec.encodeStored(
                MppMaterialCodec.encodeUtf8(legacy.credentialSecret)
            ),
            pinnedCertificatePem = MppMaterialCodec.encodeStored(
                MppMaterialCodec.encodeUtf8(legacy.pinnedCertificatePem)
            ),
        )

        assertThrows(RuntimeException::class.java) {
            MptunnelNative.validateEditor(canonical)
        }
    }

    @Test
    fun legacyPrivateMaterialTreeIsRemovedWithoutFollowingLinks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = context.noBackupFilesDir.resolve("mptunnel")
        val sentinel = context.noBackupFilesDir.resolve("mptunnel-cleanup-sentinel")
        root.deleteRecursively()
        sentinel.deleteRecursively()
        try {
            assertTrue(root.resolve("profile/materials").mkdirs())
            root.resolve("profile/materials/credential").writeBytes(byteArrayOf(1, 2, 3))
            assertTrue(sentinel.mkdir())
            sentinel.resolve("keep").writeBytes(byteArrayOf(4, 5, 6))
            android.system.Os.symlink(
                sentinel.absolutePath,
                root.resolve("external-link").absolutePath,
            )

            MptunnelNative.cleanupLegacyMaterialRoot(context)

            assertFalse(root.exists())
            assertTrue(sentinel.resolve("keep").exists())
        } finally {
            root.deleteRecursively()
            sentinel.deleteRecursively()
        }
    }

    @Test
    fun defaultProfileCdylibStartsMixedListenerBridgesInfoLogsAndStops() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = defaultProfile("instrumentation")

        val expectedNativeVersion = InstrumentationRegistry.getArguments()
            .getString(EXPECTED_NATIVE_VERSION_ARGUMENT)
        assertTrue(
            "missing dynamic $EXPECTED_NATIVE_VERSION_ARGUMENT instrumentation argument",
            !expectedNativeVersion.isNullOrBlank(),
        )
        assertEquals(expectedNativeVersion, MptunnelNative.version())
        val observedLogs = LinkedBlockingQueue<Pair<String, String>>()
        val observingLogSink = MptunnelLogSink { level, message ->
            // Keep the production LogUtil path in this end-to-end callback test.
            LogUtil.mptunnel(level, message)
            if (message.contains("inbound.listening")) {
                observedLogs.offer(level to message)
            }
        }
        repeat(5) {
            observedLogs.clear()
            val port = ServerSocket(0).use { it.localPort }
            val proxyUsername = "local"
            val proxyPassword = "instrumentation-secret"
            assertTrue(
                MptunnelNative.startForTest(
                    context = context,
                    profile = profile,
                    socksPort = port,
                    proxyUsername = proxyUsername,
                    proxyPassword = proxyPassword,
                    protector = SocketProtector { true },
                    nativeLogSink = observingLogSink,
                )
            )
            assertTrue(MptunnelNative.isRunning())
            assertEquals("ready", MptunnelNative.state())
            assertEquals(2, MptunnelNative.queryOutboundTrafficStats().size)
            val callbackRecord = observedLogs.poll(2, TimeUnit.SECONDS)
            assertEquals("info", callbackRecord?.first)
            assertTrue(callbackRecord?.second?.contains("local-mixed") == true)

            Socket().use { socket ->
                socket.soTimeout = 2_000
                socket.connect(InetSocketAddress("127.0.0.1", port), 2_000)
                socket.getOutputStream().write(byteArrayOf(0x05, 0x01, 0x02))
                assertTrue(
                    socket.getInputStream().readNBytes(2)
                        .contentEquals(byteArrayOf(0x05, 0x02))
                )
                socket.getOutputStream().write(
                    byteArrayOf(0x01, proxyUsername.length.toByte()) +
                            proxyUsername.toByteArray() +
                            byteArrayOf(proxyPassword.length.toByte()) +
                            proxyPassword.toByteArray()
                )
                assertTrue(
                    socket.getInputStream().readNBytes(2)
                        .contentEquals(byteArrayOf(0x01, 0x00))
                )
            }

            Socket().use { socket ->
                socket.soTimeout = 2_000
                socket.connect(InetSocketAddress("127.0.0.1", port), 2_000)
                socket.getOutputStream().write(
                    "CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n"
                        .toByteArray()
                )
                assertEquals(
                    "HTTP/1.1 407",
                    socket.getInputStream().readNBytes(12).toString(Charsets.US_ASCII),
                )
            }

            assertTrue(MptunnelNative.stop())
            assertFalse(MptunnelNative.isRunning())
            assertFalse(context.noBackupFilesDir.resolve("mptunnel").exists())
        }
    }

    private companion object {
        const val EXPECTED_NATIVE_VERSION_ARGUMENT = "mptunnelNativeVersion"
        const val UI_RENDER_TIMEOUT_MS = 45_000L
        const val UI_POLL_INTERVAL_MS = 100L
        const val UI_CREDENTIAL_TEXT = "instrumentation-credential-secret"
        const val UI_TRANSPORT_SECRET_TEXT = "0123456789abcdef0123456789abcdef"

        fun defaultProfile(remarks: String): ProfileItem {
            val legacy = MppProfileConfig(
                credentialSecret = "0123456789abcdef0123456789abcdef",
                pinnedCertificatePem = TEST_CERTIFICATE,
            )
            return ProfileItem(
                configType = EConfigType.MPP,
                remarks = remarks,
                server = "127.0.0.1",
                mpp = legacy.copy(
                    editorSchemaVersion = MppProfileConfig.CURRENT_EDITOR_SCHEMA_VERSION,
                    editorToml = MppConfigRenderer.renderEditableTemplate("127.0.0.1", legacy),
                    credentialSecret = MppMaterialCodec.encodeStored(
                        MppMaterialCodec.encodeUtf8(legacy.credentialSecret)
                    ),
                    pinnedCertificatePem = MppMaterialCodec.encodeStored(
                        MppMaterialCodec.encodeUtf8(legacy.pinnedCertificatePem)
                    ),
                ),
            )
        }

        fun assertOrdinaryLogLevelSelectorVisible(
            device: UiDevice,
            title: String,
            selectedLevel: String,
        ) {
            val deadline = SystemClock.uptimeMillis() + UI_RENDER_TIMEOUT_MS
            while (SystemClock.uptimeMillis() < deadline) {
                if (findVisibleLabel(device, title) != null &&
                    findVisibleLabel(device, selectedLevel) != null
                ) {
                    return
                }
                SystemClock.sleep(UI_POLL_INTERVAL_MS)
            }
            throw AssertionError(
                "ordinary MPP editor did not visibly expose the expected log-level row; " +
                        "package=${device.currentPackageName}"
            )
        }

        fun allowNotificationPermissionThroughVisibleUi(
            device: UiDevice,
            targetPackage: String,
            addConfigurationLabel: String,
        ) {
            val deadline = SystemClock.uptimeMillis() + UI_RENDER_TIMEOUT_MS
            while (SystemClock.uptimeMillis() < deadline) {
                if (device.currentPackageName == targetPackage &&
                    findVisibleLabel(device, addConfigurationLabel) != null
                ) {
                    return
                }
                val allow = device.findObject(
                    By.res(Pattern.compile(".*:id/permission_allow_button"))
                ) ?: findVisibleLabel(device, "Allow")
                if (allow != null) {
                    allow.click()
                }
                SystemClock.sleep(UI_POLL_INTERVAL_MS)
            }
            throw AssertionError(
                "launcher UI did not become ready after visible permission handling; " +
                        "package=${device.currentPackageName}"
            )
        }

        fun clickVisibleLabel(
            device: UiDevice,
            label: String,
            scrollForwardWhenMissing: Boolean = false,
        ) {
            waitForVisibleLabel(device, label, scrollForwardWhenMissing).click()
            SystemClock.sleep(UI_POLL_INTERVAL_MS)
        }

        fun waitForVisibleLabel(
            device: UiDevice,
            label: String,
            scrollForwardWhenMissing: Boolean = false,
        ): UiObject2 {
            val deadline = SystemClock.uptimeMillis() + UI_RENDER_TIMEOUT_MS
            while (SystemClock.uptimeMillis() < deadline) {
                findVisibleLabel(device, label)?.let { return it }
                if (scrollForwardWhenMissing) scrollForward(device)
                SystemClock.sleep(UI_POLL_INTERVAL_MS)
            }
            throw AssertionError(
                "expected visible UI label was not found; package=${device.currentPackageName}"
            )
        }

        fun typeIntoVisibleField(device: UiDevice, label: String, value: String) {
            waitForVisibleEditableField(device, label).click()
            device.type(value)
            waitForVisibleFieldValue(device, label) { it == value }
        }

        fun pasteIntoVisibleField(
            device: UiDevice,
            clipboard: ClipboardManager,
            label: String,
            value: String,
        ) {
            clipboard.setPrimaryClip(ClipData.newPlainText("MPP test material", value))
            waitForVisibleEditableField(device, label).click()
            SystemClock.sleep(UI_POLL_INTERVAL_MS)
            if (!device.pressKeyCode(KeyEvent.KEYCODE_PASTE)) {
                throw AssertionError("paste key event was not handled by visible field: $label")
            }
            waitForVisibleFieldValue(device, label) { it == value }
        }

        fun pasteUtf8MaterialThroughVisibleAction(
            device: UiDevice,
            clipboard: ClipboardManager,
            fieldLabel: String,
            pasteLabel: String,
            value: String,
        ) {
            clipboard.setPrimaryClip(ClipData.newPlainText("MPP test material", value))
            waitForRelatedVisibleLabel(device, fieldLabel, pasteLabel).click()
            val expectedHex = value.toByteArray(Charsets.UTF_8)
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
            waitForVisibleFieldValue(device, fieldLabel) { it.isNotEmpty() }

            val showLabel = InstrumentationRegistry.getInstrumentation().targetContext
                .getString(R.string.acc_show_content)
            waitForRelatedVisibleLabel(device, fieldLabel, showLabel).click()
            waitForVisibleFieldValue(device, fieldLabel) { it == expectedHex }
        }

        fun waitForRelatedVisibleLabel(
            device: UiDevice,
            fieldLabel: String,
            actionLabel: String,
        ): UiObject2 {
            val deadline = SystemClock.uptimeMillis() + UI_RENDER_TIMEOUT_MS
            while (SystemClock.uptimeMillis() < deadline) {
                val field = findVisibleEditableField(device, fieldLabel)
                if (field != null) {
                    val fieldCenter = field.visibleCenter
                    findVisibleLabels(device, actionLabel)
                        .minByOrNull { action ->
                            kotlin.math.abs(action.visibleCenter.y - fieldCenter.y)
                        }
                        ?.let { return it }
                }
                scrollForward(device)
                SystemClock.sleep(UI_POLL_INTERVAL_MS)
            }
            throw AssertionError(
                "visible action $actionLabel not found near field $fieldLabel; " +
                        "package=${device.currentPackageName}"
            )
        }

        fun waitForVisibleEditableField(device: UiDevice, label: String): UiObject2 {
            val deadline = SystemClock.uptimeMillis() + UI_RENDER_TIMEOUT_MS
            while (SystemClock.uptimeMillis() < deadline) {
                findVisibleEditableField(device, label)?.let { return it }
                scrollForward(device)
                SystemClock.sleep(UI_POLL_INTERVAL_MS)
            }
            throw AssertionError(
                "visible editable field not found: $label; package=${device.currentPackageName}"
            )
        }

        fun waitForVisibleFieldValue(
            device: UiDevice,
            label: String,
            predicate: (String) -> Boolean,
        ) {
            val deadline = SystemClock.uptimeMillis() + UI_RENDER_TIMEOUT_MS
            while (SystemClock.uptimeMillis() < deadline) {
                val field = findVisibleEditableField(device, label)
                if (field != null && predicate(field.text.orEmpty())) return
                SystemClock.sleep(UI_POLL_INTERVAL_MS)
            }
            // Never include editable values or clipboard material in failure diagnostics.
            throw AssertionError(
                "visible field did not show the expected update: $label; " +
                        "package=${device.currentPackageName}"
            )
        }

        fun findVisibleEditableField(device: UiDevice, label: String): UiObject2? =
            device.findObjects(By.hint(label)).firstOrNull { !it.visibleBounds.isEmpty } ?:
            device.findObjects(By.clazz(EditText::class.java))
                .firstOrNull { !it.visibleBounds.isEmpty && it.hint == label }

        fun findVisibleLabel(device: UiDevice, label: String): UiObject2? =
            findVisibleLabels(device, label).firstOrNull()

        fun findVisibleLabels(device: UiDevice, label: String): List<UiObject2> =
            sequenceOf(By.text(label), By.desc(label), By.hint(label))
                .flatMap { selector -> device.findObjects(selector).asSequence() }
                .filter { !it.visibleBounds.isEmpty }
                .distinctBy { it.visibleCenter }
                .toList()

        fun scrollForward(device: UiDevice) {
            device.findObjects(By.scrollable(true))
                .filter { !it.visibleBounds.isEmpty }
                .sortedByDescending { it.visibleBounds.width() * it.visibleBounds.height() }
                .firstOrNull { scrollable ->
                    runCatching { scrollable.scroll(Direction.DOWN, 0.65f) }
                        .getOrDefault(false)
                }
        }

        fun clickProfileEdit(device: UiDevice, remarks: String, editLabel: String) {
            val remarksObject = waitForVisibleLabel(
                device,
                remarks,
                scrollForwardWhenMissing = true,
            )
            var ancestor: UiObject2? = remarksObject
            repeat(6) {
                val current = ancestor ?: return@repeat
                current.findObject(By.desc(editLabel))?.takeIf { !it.visibleBounds.isEmpty }?.let {
                    it.click()
                    SystemClock.sleep(UI_POLL_INTERVAL_MS)
                    return
                }
                ancestor = current.parent
            }
            val nearest = device.findObjects(By.desc(editLabel))
                .filter { !it.visibleBounds.isEmpty }
                .minByOrNull {
                    kotlin.math.abs(it.visibleCenter.y - remarksObject.visibleCenter.y)
                }
            requireNotNull(nearest) { "profile edit control was not visible" }.click()
            SystemClock.sleep(UI_POLL_INTERVAL_MS)
        }

        val TEST_CERTIFICATE = """
            -----BEGIN CERTIFICATE-----
            MIIDFzCCAf+gAwIBAgIUMuczDddmfxSLAv6NSP94JpjvSkMwDQYJKoZIhvcNAQEL
            BQAwGzEZMBcGA1UEAwwQbXB0dW5uZWwuZXhhbXBsZTAeFw0yNjA4MTIxNTQ1NDBa
            Fw0yNjA4MTMxNTQ1NDBaMBsxGTAXBgNVBAMMEG1wdHVubmVsLmV4YW1wbGUwggEi
            MA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQDM5SpRQtVhbIF6UfEx/y0wAC2o
            1PJPzEEbzt5rYn5qqvQYqMKBoHtpsVlJOJH+lS4XWSa3dulwPGCEu5zpimZ5Jdit
            amm91/1CzfKs0KczKj2z4lOPedO7p/dMxm3RxoT6sdiHza4XjNQGLqbZipXPt3DN
            e5Mzp8AKIp6AUPAz/zngojk1VJeJdijfnj3CwNDUUoYfZzF810UdmI+az8w2Zhhv
            03VYbbGrMN/27IvVrfq8nRKQPhXYP94y2GilTst4i/xzWXAe8eiXaKvrqd8gibE5
            RTYrncP580krTw74rFJye80W8wIiRGcgex41EWgim1zw/AEZX4QExoDrrP4NAgMB
            AAGjUzBRMB0GA1UdDgQWBBRDYaFnj0jnULZPE1d0M4iWxXtfHzAfBgNVHSMEGDAW
            gBRDYaFnj0jnULZPE1d0M4iWxXtfHzAPBgNVHRMBAf8EBTADAQH/MA0GCSqGSIb3
            DQEBCwUAA4IBAQAQIYji5izmFUZU7MOK+sMV9PRUk7c0p3eqtkA4Lt6FVU5jSAGm
            fKpxhitel+ORNpotQxMt1ne4c/WztxIY8T80QD+ibCL5FqSRsqFttjUznPIW/TvH
            UECmYVgdrylusQ/F07kGDRebiPzjUNN8yOIowrZ4MPKRH67H2qQ45s8qq7s62JZm
            nRo8pSnnd26/s1n/hG9dLHvSNmsgzO2TfyceMxMV/2Ww3+utO/AO6mIYaqK+YsWx
            53yBZ7nYc8TFQczypw2XNYHYjAG19mvxlwm2aAbKdv+dDKQHy2jAa/ywjw9llUz7
            s6WDZreeWD3xjyNi+be4je5xpx57+yWMSCRy
            -----END CERTIFICATE-----
        """.trimIndent()
    }
}
