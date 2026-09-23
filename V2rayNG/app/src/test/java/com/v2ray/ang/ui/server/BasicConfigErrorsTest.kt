package com.v2ray.ang.ui.server

import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BasicConfigErrorsTest {
    @Test
    fun `standard server marks blank remarks address and nonpositive port`() {
        val state = ServerUiState(
            configType = EConfigType.VMESS,
            remarks = " ",
            address = "",
            port = "0",
        )

        val errors = basicConfigErrors(state)
        assertEquals(
            BasicConfigErrors(remarksError = true, addressError = true, portError = true),
            errors,
        )
        assertTrue(errors.hasError)
    }

    @Test
    fun `standard server accepts populated remarks address and positive port`() {
        val state = ServerUiState(
            configType = EConfigType.VMESS,
            remarks = "server",
            address = "example.test",
            port = "443",
        )

        val errors = basicConfigErrors(state)
        assertEquals(
            BasicConfigErrors(remarksError = false, addressError = false, portError = false),
            errors,
        )
        assertFalse(errors.hasError)
    }

    @Test
    fun `hysteria2 does not require a numeric port`() {
        val state = ServerUiState(
            configType = EConfigType.HYSTERIA2,
            remarks = "server",
            address = "example.test",
            port = "invalid",
        )

        val errors = basicConfigErrors(state)
        assertEquals(
            BasicConfigErrors(remarksError = false, addressError = false, portError = false),
            errors,
        )
        assertFalse(errors.hasError)
    }

    @Test
    fun `mpp validates remarks while leaving endpoint and port to its protocol editor`() {
        val state = ServerUiState(
            configType = EConfigType.MPP,
            remarks = "",
            address = "",
            port = "invalid",
        )

        val errors = basicConfigErrors(state)
        assertEquals(
            BasicConfigErrors(remarksError = true, addressError = false, portError = false),
            errors,
        )
        assertTrue(errors.hasError)
    }
}
