package com.qualcomm.meshmind.telemetry

/**
 * Immutable data class containing raw telemetry readings collected from Android System APIs in Kotlin.
 */
data class RawTelemetry(
    val timestamp: Long,
    val batteryLevel: Int?,
    val wifiRssi: Int?,
    val isWifiConnected: Boolean,
    val bluetoothNeighborCount: Int,
    val cpuExecutionDelayMs: Long
)
