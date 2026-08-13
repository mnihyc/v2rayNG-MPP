package com.v2ray.ang.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RootProxyManagerTest {

    @Test
    fun hevYamlCredentialsEscapeSingleQuotes() {
        assertEquals("user''name", RootProxyManager.escapeHevYamlCredential("user'name"))
        assertEquals("pass''word", RootProxyManager.escapeHevYamlCredential("pass'word"))
    }

    @Test
    fun hevYamlCredentialsRejectLineBreaks() {
        assertNull(RootProxyManager.escapeHevYamlCredential("user\nname"))
        assertNull(RootProxyManager.escapeHevYamlCredential("pass\rword"))
    }
}
