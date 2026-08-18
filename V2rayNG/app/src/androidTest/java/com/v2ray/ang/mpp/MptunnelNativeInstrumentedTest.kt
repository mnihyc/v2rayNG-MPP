package com.v2ray.ang.mpp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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

    companion object {
        private const val EXPECTED_NATIVE_VERSION_ARGUMENT = "mptunnelNativeVersion"

        private fun defaultProfile(remarks: String): ProfileItem {
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

        internal val TEST_CERTIFICATE = """
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
