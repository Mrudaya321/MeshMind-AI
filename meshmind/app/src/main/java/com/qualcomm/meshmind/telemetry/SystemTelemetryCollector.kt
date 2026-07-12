package com.qualcomm.meshmind.telemetry

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import com.qualcomm.meshmind.core.runtime.BaseSubsystem
import com.qualcomm.meshmind.diagnostics.models.SubsystemHealth
import com.qualcomm.meshmind.logging.MeshLogger

/**
 * System Telemetry Collector subsystem in Kotlin.
 */
class SystemTelemetryCollector(context: Context) : BaseSubsystem, TelemetryCollector {

    private val appContext = context.applicationContext
    
    private var isOperational = false
    private var errorCount: Long = 0

    override val subsystemId: String = "telemetry_collector"
    override val initPriority: Int = 20

    override suspend fun initialize() {
        isOperational = true
        MeshLogger.i(subsystemId, "System telemetry collector subsystem initialized.")
    }

    override fun shutdown() {
        isOperational = false
        MeshLogger.i(subsystemId, "System telemetry collector subsystem shutdown.")
    }

    override fun getHealth(): SubsystemHealth {
        return SubsystemHealth(
            subsystemId,
            isOperational,
            System.currentTimeMillis(),
            errorCount,
            if (isOperational) "Telemetry collector is running" else "Telemetry collector is offline"
        )
    }

    override fun collectCurrentTelemetry(): RawTelemetry {
        if (!isOperational) {
            errorCount++
            return RawTelemetry(System.currentTimeMillis(), 0, -100, false, 0, 0)
        }

        val batteryLevel = getBatteryPercent()
        val wifiRssi = getWifiSignalStrength()
        val wifiConnected = wifiRssi != null && wifiRssi > -100
        
        val neighborsCount = com.qualcomm.meshmind.state.NeighborStateRepository.getInstance().getAllNeighbors().size
        
        return RawTelemetry(
            System.currentTimeMillis(),
            batteryLevel,
            wifiRssi,
            wifiConnected,
            neighborsCount,
            0L // Real task delay to be measured if necessary
        )
    }

    private fun getBatteryPercent(): Int? {
        return try {
            val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = appContext.registerReceiver(null, ifilter)
            if (batteryStatus != null) {
                val level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (scale <= 0) null else ((level / scale.toFloat()) * 100).toInt()
            } else {
                null
            }
        } catch (e: Exception) {
            MeshLogger.e(subsystemId, "Failed to read battery percentage", e)
            null
        }
    }

    private fun getWifiSignalStrength(): Int? {
        return try {
            val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val rssi = wifiManager?.connectionInfo?.rssi
            if (rssi == null || rssi == -127) null else rssi
        } catch (e: Exception) {
            MeshLogger.e(subsystemId, "Failed to read Wi-Fi RSSI", e)
            null
        }
    }
}
