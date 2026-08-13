package com.v2ray.ang.mpp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v2ray.ang.dto.entities.MppProfileConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

@RunWith(AndroidJUnit4::class)
class MptunnelNativeInstrumentedTest {

    @After
    fun stopNativeRuntime() {
        if (MptunnelNative.isRunning()) MptunnelNative.stop()
    }

    @Test
    fun cdylibStartsMixedListenerAndStops() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = ProfileItem(
            configType = EConfigType.MPP,
            remarks = "instrumentation",
            server = "127.0.0.1",
            mpp = MppProfileConfig(
                tcpEnabled = true,
                tcpPort = 9,
                tcpCarrierCount = 1,
                udpEnabled = false,
                credentialId = "android-client",
                principalId = "android",
                credentialSecret = "0123456789abcdef0123456789abcdef",
                tlsServerName = "mptunnel.example",
                pinnedCertificatePem = TEST_CERTIFICATE,
            ),
        )

        val expectedNativeVersion = InstrumentationRegistry.getArguments()
            .getString(EXPECTED_NATIVE_VERSION_ARGUMENT)
        assertTrue(
            "missing dynamic $EXPECTED_NATIVE_VERSION_ARGUMENT instrumentation argument",
            !expectedNativeVersion.isNullOrBlank(),
        )
        assertEquals(expectedNativeVersion, MptunnelNative.version())
        repeat(5) { generation ->
            val profileId = "instrumentation-mixed-$generation"
            val port = ServerSocket(0).use { it.localPort }
            val proxyUsername = "local"
            val proxyPassword = "instrumentation-secret"
            assertTrue(
                MptunnelNative.start(
                    context = context,
                    profileId = profileId,
                    profile = profile,
                    socksPort = port,
                    proxyUsername = proxyUsername,
                    proxyPassword = proxyPassword,
                    protector = SocketProtector { true },
                )
            )
            assertTrue(MptunnelNative.isRunning())

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
            val privateProfile = context.noBackupFilesDir.resolve("mptunnel/$profileId")
            assertFalse(privateProfile.exists())
        }
    }

    private companion object {
        const val EXPECTED_NATIVE_VERSION_ARGUMENT = "mptunnelNativeVersion"

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
