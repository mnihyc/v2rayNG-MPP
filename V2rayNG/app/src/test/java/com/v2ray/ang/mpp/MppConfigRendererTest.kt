package com.v2ray.ang.mpp

import com.google.gson.Gson
import com.v2ray.ang.dto.entities.MppProfileConfig
import com.v2ray.ang.dto.entities.MppPathConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.JsonUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MppConfigRendererTest {

    @Test
    fun editableDocumentIsSyntaxValidShapeWithOnlyManagedPlaceholders() {
        val config = legacyConfig().copy(
            credentialSecret = "credential-material-that-must-not-leak",
            pinnedCertificatePem = "certificate-material-that-must-not-leak",
            transportSecret = "0123456789abcdef0123456789abcdef",
        )

        val template = MppConfigRenderer.renderEditableTemplate("2001:db8::10", config)

        assertEquals(1, Regex("\\[\\[inbounds]]").findAll(template).count())
        assertTrue(template.contains("protocol = \"mixed\""))
        assertTrue(template.contains("127.0.0.1:${MppConfigRenderer.SOCKS_PORT_TOKEN}"))
        assertTrue(template.contains("tcp://[2001:db8::10]:7443?max-tcp-carriers=3"))
        assertTrue(template.contains("quic://[2001:db8::10]:7443"))
        assertTrue(template.contains("from = \"managed\""))
        assertTrue(template.contains("id = \"${MppConfigRenderer.CREDENTIAL_MATERIAL_ID}\""))
        assertTrue(template.contains("id = \"${MppConfigRenderer.CERTIFICATE_MATERIAL_ID}\""))
        assertTrue(template.contains("id = \"${MppConfigRenderer.TRANSPORT_SECRET_MATERIAL_ID}\""))
        assertTrue(template.contains("# ${MppConfigRenderer.LOCAL_USER_DEFINITION_TOKEN}"))
        assertTrue(template.contains("# ${MppConfigRenderer.LOCAL_USER_BINDING_TOKEN}"))
        assertFalse(template.contains(config.credentialSecret))
        assertFalse(template.contains(config.pinnedCertificatePem))
        assertFalse(template.contains(config.transportSecret))
        assertFalse(template.contains("_file"))
    }

    @Test
    fun editableDocumentUsesCanonicalGroupedDnsSchema() {
        val template = MppConfigRenderer.renderEditableTemplate(
            "server.example.com",
            legacyConfig(),
        )

        assertTrue(template.contains("[dns]\ndefault = \"mpp-doh\""))
        assertTrue(template.contains("[[dns.servers]]"))
        assertTrue(template.contains("protocol = \"doh\""))
        assertTrue(template.contains("address = \"1.1.1.1:443\""))
        assertTrue(template.contains("tls_name = \"cloudflare-dns.com\""))
        assertTrue(template.contains("path = \"/dns-query\""))
        assertTrue(template.contains("[[dns.policies]]"))
        assertTrue(template.contains("servers = [\"mpp-doh\"]"))
        assertTrue(template.contains("family = \"ipv4-and-ipv6\""))
        assertTrue(template.contains("security = \"require-encrypted\""))
        assertTrue(template.contains("strategy = \"ordered\""))
        assertTrue(template.contains("answer_cidrs = []"))
        assertTrue(template.contains("query = { timeout_ms = 5000, inflight = 64, answers = 64 }"))
        assertTrue(template.contains("cache = { entries = 4096"))
        assertFalse(template.contains("default_dns_plan"))
        assertFalse(template.contains("dns.upstreams"))
        assertFalse(template.contains("dns.plans"))
    }

    @Test
    fun canonicalProfileUsesBase64PersistenceAndPassesValidation() {
        val legacy = legacyConfig()
        val document = MppConfigRenderer.renderEditableTemplate("server.example.com", legacy)
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

        assertNull(MppProfileValidator.validate(canonical))
        assertFalse(canonical.credentialSecret.contains(legacy.credentialSecret))
        assertFalse(canonical.pinnedCertificatePem.contains("BEGIN CERTIFICATE"))
    }

    @Test
    fun canonicalRawDocumentDoesNotRequireARepresentableGuidedProjection() {
        val legacy = legacyConfig()
        val raw = legacy.copy(
            editorSchemaVersion = MppProfileConfig.CURRENT_EDITOR_SCHEMA_VERSION,
            editorToml = "# advanced raw document validated by the native finalizer",
            useRawToml = true,
            paths = emptyList(),
            credentialId = "not valid guided id!",
            credentialSecret = MppMaterialCodec.encodeStored(
                MppMaterialCodec.encodeUtf8(legacy.credentialSecret)
            ),
            pinnedCertificatePem = MppMaterialCodec.encodeStored(
                MppMaterialCodec.encodeUtf8(legacy.pinnedCertificatePem)
            ),
        )

        assertNull(MppProfileValidator.validate(raw))
    }

    @Test
    fun structuredTemplatePreservesEveryExplicitPathInOrderAndExactly() {
        val paths = listOf(
            MppPathConfig(
                name = "wifi-primary",
                endpoint = "tcp://wifi.example:7000-7999?max-tcp-carriers=4&" +
                        "port-rotation-interval-ms=45000",
            ),
            MppPathConfig(
                name = "mobile-quic",
                endpoint = "quic://[2001:db8::10]:7443?expensive=true&backup=false",
            ),
            MppPathConfig(
                name = "fallback",
                endpoint = "tcp://backup.example:8443?backup=true&max-tcp-carriers=1",
            ),
        )
        val template = MppConfigRenderer.renderEditableTemplate(
            server = "legacy.example",
            config = legacyConfig().copy(paths = paths),
        )

        val renderedLines = template.lineSequence()
            .filter { it.trimStart().startsWith("{ name =") }
            .toList()
        assertEquals(3, renderedLines.size)
        assertTrue(renderedLines[0].contains(paths[0].endpoint))
        assertTrue(renderedLines[1].contains(paths[1].endpoint))
        assertTrue(renderedLines[2].contains(paths[2].endpoint))
        assertFalse(template.contains("legacy.example"))
    }

    @Test
    fun projectionJsonUsesTheVersionedNativeContract() {
        val projection = MppEditorProjection.from(legacyConfig(), "server.example.com")
        val json = MppEditorJson.encode(projection)
        assertTrue(json.contains("\"schema_version\":1"))
        assertTrue(json.contains("\"credential_id\":"))
        assertTrue(json.contains("\"tls_server_name\":"))
        assertTrue(json.contains("\"advanced\":null"))
        assertFalse(json.contains("credentialSecret"))
    }

    @Test
    fun finalizeJsonKeepsExplicitOptionalNulls() {
        val bindings = MppFinalizeBindings(
            socksPort = 10808,
            credentialBase64 = "Y3JlZGVudGlhbA==",
            pinnedCertificateBase64 = "Y2VydGlmaWNhdGU=",
            transportSecretBase64 = null,
            localAuth = MppFinalizeBindings.LocalAuth(
                username = "local-user",
                passwordBase64 = "cGFzc3dvcmQ=",
            ),
        )
        val json = MppEditorJson.encode(bindings.copy(localAuth = null))

        assertTrue(json.contains("\"transport_secret_base64\":null"))
        assertTrue(json.contains("\"local_auth\":null"))
        assertFalse(bindings.toString().contains("Y3JlZGVudGlhbA=="))
        assertFalse(bindings.toString().contains("Y2VydGlmaWNhdGU="))
        assertFalse(bindings.toString().contains("cGFzc3dvcmQ="))
    }

    @Test
    fun nestedCanonicalProfileRoundTripsThroughGsonWithoutMaterialDisclosure() {
        val legacy = legacyConfig()
        val original = ProfileItem(
            configType = EConfigType.MPP,
            remarks = "MPP profile",
            server = "server.example.com",
            serverPort = "7443",
            mpp = legacy.copy(
                editorSchemaVersion = MppProfileConfig.CURRENT_EDITOR_SCHEMA_VERSION,
                editorToml = MppConfigRenderer.renderEditableTemplate(
                    "server.example.com",
                    legacy,
                ),
                credentialSecret = MppMaterialCodec.encodeStored(
                    MppMaterialCodec.encodeUtf8(legacy.credentialSecret)
                ),
                pinnedCertificatePem = MppMaterialCodec.encodeStored(
                    MppMaterialCodec.encodeUtf8(legacy.pinnedCertificatePem)
                ),
            ),
        )

        val json = Gson().toJson(original)
        val restored = Gson().fromJson(json, ProfileItem::class.java)

        assertEquals(original.mpp, restored.mpp)
        assertEquals(EConfigType.MPP, restored.configType)
        assertFalse(json.contains(legacy.credentialSecret))
        assertFalse(json.contains("BEGIN CERTIFICATE"))
        assertFalse(restored.mpp.toString().contains(original.mpp!!.credentialSecret))
    }

    private fun legacyConfig() = MppProfileConfig(
        credentialSecret = "0123456789abcdef0123456789abcdef",
        pinnedCertificatePem = """
            -----BEGIN CERTIFICATE-----
            ZHVtbXk=
            -----END CERTIFICATE-----
        """.trimIndent(),
    )
}
