package com.v2ray.ang.mpp

import com.google.gson.Gson
import com.v2ray.ang.dto.entities.MppProfileConfig
import com.v2ray.ang.dto.entities.MppPathConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MppConfigRendererTest {

    @Test
    fun structuredTemplateUsesOneMixedListenerAndOpaqueMaterials() {
        val config = validConfig().copy(
            credentialSecret = "credential-material-that-must-not-leak",
            pinnedCertificatePem = "certificate-material-that-must-not-leak",
            transportSecret = "0123456789abcdef0123456789abcdef",
        )

        val template = MppConfigRenderer.renderEditableTemplate("2001:db8::10", config)

        assertEquals(1, Regex("\\[\\[inbounds]]").findAll(template).count())
        assertTrue(template.contains("protocol = \"mixed\""))
        assertTrue(template.contains("127.0.0.1:${MppConfigRenderer.SOCKS_PORT_TOKEN}"))
        assertTrue(template.contains("tcp://[2001:db8::10]:7443?tcp-carriers=1-3"))
        assertTrue(template.contains("udp://[2001:db8::10]:7443"))
        assertTrue(template.contains(MppConfigRenderer.CREDENTIAL_MATERIAL_TOKEN))
        assertTrue(template.contains(MppConfigRenderer.CERTIFICATE_MATERIAL_TOKEN))
        assertTrue(template.contains(MppConfigRenderer.TRANSPORT_SECRET_MATERIAL_TOKEN))
        assertTrue(template.contains(MppConfigRenderer.LOCAL_USER_DEFINITION_TOKEN))
        assertTrue(template.contains(MppConfigRenderer.LOCAL_USER_BINDING_TOKEN))
        assertFalse(template.contains(config.credentialSecret))
        assertFalse(template.contains(config.pinnedCertificatePem))
        assertFalse(template.contains(config.transportSecret))
        assertFalse(template.contains("transport = \"system\""))
    }

    @Test
    fun runtimeRendererReplacesOnlyTheLocalPortToken() {
        val profile = ProfileItem(
            configType = EConfigType.MPP,
            server = "server.example.com",
            mpp = validConfig(),
        )

        val rendered = MppConfigRenderer.renderRuntime(
            profile = profile,
            socksPort = 10808,
            proxyUsername = "local-user",
            hasProxyPassword = true,
        )

        assertTrue(rendered.contains("listen = [\"127.0.0.1:10808\"]"))
        assertFalse(rendered.contains(MppConfigRenderer.SOCKS_PORT_TOKEN))
        assertTrue(rendered.contains(MppConfigRenderer.CREDENTIAL_MATERIAL_TOKEN))
        assertTrue(rendered.contains(MppConfigRenderer.CERTIFICATE_MATERIAL_TOKEN))
        assertTrue(rendered.contains("username = \"local-user\""))
        assertTrue(rendered.contains("local_users = [\"v2rayng-local\"]"))
        assertTrue(rendered.contains(MppConfigRenderer.LOCAL_PROXY_PASSWORD_MATERIAL_TOKEN))
        assertFalse(rendered.contains(MppConfigRenderer.LOCAL_USER_DEFINITION_TOKEN))
        assertFalse(rendered.contains(MppConfigRenderer.LOCAL_USER_BINDING_TOKEN))
    }

    @Test
    fun validStructuredProfilePassesPreSaveValidation() {
        assertNull(MppProfileValidator.validate(validConfig()))
    }

    @Test
    fun generatedRawTemplatePassesManagedTokenValidation() {
        val structured = validConfig()
        val template = MppConfigRenderer.renderEditableTemplate("server.example.com", structured)

        assertNull(
            MppProfileValidator.validate(
                structured.copy(useRawToml = true, rawToml = template)
            )
        )
    }

    @Test
    fun structuredTemplatePreservesEveryExplicitPathInOrderAndExactly() {
        val paths = listOf(
            MppPathConfig(
                name = "wifi-primary",
                endpoint = "tcp://wifi.example:7000-7999?tcp-carriers=1-4&port-hop-interval-ms=45000",
            ),
            MppPathConfig(
                name = "mobile-quic",
                endpoint = "udp://[2001:db8::10]:7443?expensive=true&backup=false",
            ),
            MppPathConfig(
                name = "fallback",
                endpoint = "tcp://backup.example:8443?backup&tcp-carriers=1-1",
            ),
        )
        val template = MppConfigRenderer.renderEditableTemplate(
            server = "legacy.example",
            config = validConfig().copy(paths = paths),
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
    fun nestedProfileValuesRoundTripThroughGson() {
        val original = ProfileItem(
            configType = EConfigType.MPP,
            remarks = "MPP profile",
            server = "server.example.com",
            serverPort = "7443",
            mpp = validConfig().copy(tcpCarrierCount = 5, udpEnabled = false),
        )

        val restored = Gson().fromJson(Gson().toJson(original), ProfileItem::class.java)

        assertEquals(original.mpp, restored.mpp)
        assertEquals(EConfigType.MPP, restored.configType)
        assertFalse(restored.mpp.toString().contains(original.mpp!!.credentialSecret))
    }

    private fun validConfig() = MppProfileConfig(
        credentialSecret = "0123456789abcdef0123456789abcdef",
        pinnedCertificatePem = """
            -----BEGIN CERTIFICATE-----
            ZHVtbXk=
            -----END CERTIFICATE-----
        """.trimIndent(),
    )
}
