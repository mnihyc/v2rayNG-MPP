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
    fun editableDocumentIsSyntaxValidShapeWithManagedRemoteMaterial() {
        val config = legacyConfig().copy(
            credentialSecret = "credential-material-that-must-not-leak",
            pinnedCertificatePem = "certificate-material-that-must-not-leak",
            transportSecret = "0123456789abcdef0123456789abcdef",
            targetResolution = MppProfileConfig.TARGET_RESOLUTION_AS_IS,
        )

        val template = MppConfigRenderer.renderEditableTemplate("2001:db8::10", config)

        assertEquals(1, Regex("\\[\\[inbounds]]").findAll(template).count())
        assertTrue(template.startsWith("[logging]\nlevel = \"info\"\n"))
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
        assertTrue(template.contains("[routing]\ntarget_resolution = \"as-is\""))
        assertTrue(template.contains("[management]\nlisten = [\"127.0.0.1:7600\"]"))
        assertTrue(template.contains("dashboard = true"))
        assertTrue(template.contains("allow_peer_diagnostics = false"))
        assertTrue(template.contains("outbound = \"remote-mpp\""))
        assertFalse(template.contains("action ="))
    }

    @Test
    fun persistedTemplatesUseIndependentLowercaseManagementTokens() {
        val first = MppConfigRenderer.renderEditableTemplate("server.example.com", legacyConfig())
        val second = MppConfigRenderer.renderEditableTemplate("server.example.com", legacyConfig())
        val tokenPattern = Regex(
            """token = \{ from = "raw", value = "([0-9a-f]{48})" \}"""
        )
        val firstToken = requireNotNull(tokenPattern.find(first)?.groupValues?.get(1))
        val secondToken = requireNotNull(tokenPattern.find(second)?.groupValues?.get(1))

        assertEquals(48, firstToken.length)
        assertEquals(48, secondToken.length)
        assertFalse(firstToken == secondToken)
        assertEquals(1, tokenPattern.findAll(first).count())
    }

    @Test
    fun legacyRuntimeTemplateHasNoUnpersistedManagementCredential() {
        val template = MppConfigRenderer.renderLegacyRuntimeTemplate(
            "server.example.com",
            legacyConfig(),
        )

        assertFalse(template.contains("[management]"))
        assertFalse(template.contains("127.0.0.1:7600"))
        assertFalse(template.contains("dashboard = true"))
        assertFalse(template.contains("allow_peer_diagnostics"))
        assertFalse(template.contains("from = \"raw\""))
    }

    @Test
    fun ordinaryLoggingSelectionRendersCanonicalThreshold() {
        val template = MppConfigRenderer.renderEditableTemplate(
            "server.example.com",
            legacyConfig().copy(logLevel = "debug"),
        )

        assertTrue(template.startsWith("[logging]\nlevel = \"debug\"\n"))
        assertFalse(template.contains("level = \"info\""))
    }

    @Test
    fun guidedProfileRejectsAnUnknownLoggingThreshold() {
        assertEquals(
            MppValidationError.LOG_LEVEL,
            MppProfileValidator.validate(legacyConfig().copy(logLevel = "trace")),
        )
    }

    @Test
    fun guidedProfileRejectsAnUnknownTargetResolution() {
        assertEquals(
            MppValidationError.TARGET_RESOLUTION,
            MppProfileValidator.validate(
                legacyConfig().copy(targetResolution = "automatic")
            ),
        )
    }

    @Test
    fun targetResolutionRendersOnlyWhenExplicitlySelected() {
        val compatibility = MppConfigRenderer.renderEditableTemplate(
            "server.example.com",
            legacyConfig(),
        )
        assertTrue(compatibility.contains("[routing]\n\n# Complete V2Fly"))
        assertFalse(compatibility.contains("target_resolution"))

        for (mode in MppProfileConfig.SUPPORTED_TARGET_RESOLUTIONS.filterNotNull()) {
            val explicit = MppConfigRenderer.renderEditableTemplate(
                "server.example.com",
                legacyConfig().copy(targetResolution = mode),
            )
            assertTrue(explicit.contains("[routing]\ntarget_resolution = \"$mode\""))
        }
    }

    @Test
    fun editableDocumentLeavesVpnDnsToV2rayNg() {
        val template = MppConfigRenderer.renderEditableTemplate(
            "server.example.com",
            legacyConfig(),
        )

        assertFalse(template.contains("[dns]"))
        assertFalse(template.contains("[[dns."))
        assertFalse(template.contains("mpp-doh"))
        assertFalse(template.contains("cloudflare-dns.com"))
        assertFalse(template.contains("default_dns_plan"))
        assertFalse(template.contains("dns.upstreams"))
        assertFalse(template.contains("dns.plans"))
    }

    @Test
    fun editableDocumentOrdersCompletePrivateBypassBeforeLiteralDelegation() {
        val template = MppConfigRenderer.renderEditableTemplate(
            "server.example.com",
            legacyConfig().copy(targetResolution = MppProfileConfig.TARGET_RESOLUTION_AS_IS),
        )
        val expectedPrivateCidrs = listOf(
            "0.0.0.0/8",
            "10.0.0.0/8",
            "100.64.0.0/10",
            "127.0.0.0/8",
            "169.254.0.0/16",
            "172.16.0.0/12",
            "192.0.0.0/24",
            "192.0.2.0/24",
            "192.88.99.0/24",
            "192.168.0.0/16",
            "198.18.0.0/15",
            "198.51.100.0/24",
            "203.0.113.0/24",
            "224.0.0.0/4",
            "240.0.0.0/4",
            "255.255.255.255/32",
            "::/128",
            "::1/128",
            "fc00::/7",
            "fe80::/10",
            "ff00::/8",
        )
        val renderedPrivateCidrs = Regex("""(?m)^#   "([^"]+)",$""")
            .findAll(template)
            .map { match -> match.groupValues[1] }
            .toList()
        val privateOutbound = template.indexOf("# name = \"private-direct\"")
        val privateRule = template.indexOf("# name = \"bypass-v2ray-private-literals\"")
        val literalRule = template.indexOf("name = \"delegate-literal-targets\"")
        val defaultRule = template.indexOf("name = \"default\"")

        assertEquals(expectedPrivateCidrs, renderedPrivateCidrs)
        assertTrue(privateOutbound >= 0)
        assertTrue(
            template.contains(
                "# [[outbounds]]\n" +
                        "# name = \"private-direct\"\n" +
                        "# protocol = \"direct\""
            )
        )
        assertTrue(privateRule > privateOutbound)
        assertTrue(literalRule > privateRule)
        assertTrue(defaultRule > literalRule)
        assertTrue(template.contains("target_resolution = \"as-is\""))
        assertTrue(template.contains("as 10.1.2.3, through MPP"))
        assertTrue(
            template.contains(
                "name = \"delegate-literal-targets\"\n" +
                        "inbounds = [\"local-mixed\"]\n" +
                        "destination_cidrs = [\"0.0.0.0/0\", \"::/0\"]\n" +
                        "decision = \"allow-restricted\"\n" +
                        "outbound = \"remote-mpp\""
            )
        )
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
        assertTrue(json.contains("\"log_level\":\"info\""))
        assertTrue(json.contains("\"target_resolution\":null"))
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
