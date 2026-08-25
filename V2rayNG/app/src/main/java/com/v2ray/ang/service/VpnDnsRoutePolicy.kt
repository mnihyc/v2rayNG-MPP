package com.v2ray.ang.service

internal data class VpnDnsExactRoute(
    val address: String,
    val prefixLength: Int,
)

/**
 * Supplies exact routes only when bypass-LAN's broad route list may omit a configured DNS address.
 * [dnsServers] must contain pure IPv4 or IPv6 addresses accepted by VpnService.Builder.
 */
internal fun vpnDnsExactRoutes(
    dnsServers: List<String>,
    bypassLan: Boolean,
): List<VpnDnsExactRoute> {
    if (!bypassLan) return emptyList()

    return dnsServers.map { address ->
        VpnDnsExactRoute(
            address = address,
            prefixLength = if (':' in address) 128 else 32,
        )
    }
}
