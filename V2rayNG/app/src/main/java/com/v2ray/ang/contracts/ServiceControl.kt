package com.v2ray.ang.contracts

import android.app.Service
import android.net.Network

interface ServiceControl {
    /**
     * Gets the service instance.
     * @return The service instance.
     */
    fun getService(): Service

    /**
     * Starts the service.
     */
    fun startService()

    /**
     * Stops the service.
     */
    fun stopService()

    /**
     * Declares the networks the tunnel runs on top of.
     * Only meaningful for the VPN service, the other run modes have no interface to report.
     *
     * @param networks The upstream networks, null to let the system pick.
     * @return True if the networks were accepted.
     */
    fun setUnderlyingNetworks(networks: Array<Network>?): Boolean = false
}
