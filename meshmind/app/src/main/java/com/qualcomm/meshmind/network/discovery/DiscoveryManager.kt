package com.qualcomm.meshmind.network.discovery

/**
 * Interface defining peer discovery mechanisms in Kotlin.
 */
interface DiscoveryManager {
    val isScanning: Boolean
    val isAdvertising: Boolean


    /**
     * Commences active discovery scanning and advertising.
     */
    fun startDiscovery()

    /**
     * Halts peer scanning and advertisements.
     */
    fun stopDiscovery()
}
