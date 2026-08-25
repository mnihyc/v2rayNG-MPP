package com.v2ray.ang.service

import org.junit.Assert.assertEquals
import org.junit.Test

class MppVpnDnsRoutePolicyTest {

    @Test
    fun defaultRoutesNeedNoDnsHostRoutes() {
        assertEquals(
            emptyList<VpnDnsExactRoute>(),
            vpnDnsExactRoutes(listOf("10.1.2.3", "fd00::53"), bypassLan = false),
        )
    }

    @Test
    fun bypassLanRoutesPrivateIpv4DnsExactly() {
        assertEquals(
            listOf(VpnDnsExactRoute("10.1.2.3", 32)),
            vpnDnsExactRoutes(listOf("10.1.2.3"), bypassLan = true),
        )
    }

    @Test
    fun bypassLanRoutesIpv6DnsExactly() {
        assertEquals(
            listOf(VpnDnsExactRoute("fd00::53", 128)),
            vpnDnsExactRoutes(listOf("fd00::53"), bypassLan = true),
        )
    }
}
