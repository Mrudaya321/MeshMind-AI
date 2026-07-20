package com.qualcomm.meshmind.arduino

import android.content.Context
import com.qualcomm.meshmind.core.runtime.BaseSubsystem
import com.qualcomm.meshmind.core.scheduling.TaskScheduler
import com.qualcomm.meshmind.diagnostics.models.SubsystemHealth
import com.qualcomm.meshmind.logging.MeshLogger
import com.qualcomm.meshmind.telemetry.TelemetryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Handles interfaces with Arduino static sensor infrastructure nodes in Kotlin.
 * Processes custom BLE beacon advertisements carrying emergency notifications.
 */
class ArduinoIntegrationManager(private val context: Context) : BaseSubsystem {

    private val arduinoScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var isOperational = false
    private var errorCount: Long = 0
    private val repo = InfrastructureRepository.getInstance()

    companion object {
        private const val TAG = "ArduinoIntegrationManager"
        const val ARDUINO_SERVICE_UUID = "0000feed-0000-1000-8000-00805f9b34cc"
        private const val TASK_STALE_SWEEP = "arduino_stale_beacons_sweep"
        private const val TASK_SIMULATION = "arduino_simulated_beacons"

        @Volatile
        private var instance: ArduinoIntegrationManager? = null

        fun getInstance(context: Context): ArduinoIntegrationManager {
            return instance ?: synchronized(this) {
                instance ?: ArduinoIntegrationManager(context).also { instance = it }
            }
        }
    }

    override val subsystemId: String = "arduino_integration_manager"
    override val initPriority: Int = 42 // Run after database warming

    override suspend fun initialize() {
        try {
            isOperational = true
            MeshLogger.i(TAG, "MMP Arduino Integration Subsystem online.")

            // 1. Periodic stale sweep (every 10 seconds)
            TaskScheduler.getInstance().schedulePeriodic(TASK_STALE_SWEEP, 10000, 0.05) {
                pruneStaleBeacons()
            }

            // 2. Simulated beacons fallback loop under test runs removed from production init.
        } catch (e: Exception) {
            errorCount++
            isOperational = false
            MeshLogger.e(TAG, "Failed initializing Arduino Integration Manager", e)
            throw e
        }
    }

    override fun shutdown() {
        TaskScheduler.getInstance().cancel(TASK_STALE_SWEEP)
        TaskScheduler.getInstance().cancel(TASK_SIMULATION)
        isOperational = false
        MeshLogger.i(TAG, "MMP Arduino Integration Subsystem shutdown.")
    }

    override fun getHealth(): SubsystemHealth {
        return SubsystemHealth(
            subsystemId,
            isOperational,
            System.currentTimeMillis(),
            errorCount,
            if (isOperational) "Listening for Arduino Infrastructure beacons (Active: ${repo.getAllNodes().size})" else "Offline"
        )
    }

    /**
     * Parses scanned custom Arduino BLE raw advertisement attributes.
     */
    fun processInfrastructureBeacon(mac: String, rssi: Int, payload: ByteArray) {
        if (!isOperational) return

        arduinoScope.launch {
            try {
                // Mock parse logic representing Arduino layout details
                // Format: protocolVersion (1B) | nodeId (8B) | battery (1B) | emergencyFlag (1B)
                if (payload.size < 11) return@launch
                
                val version = payload[0].toInt()
                val nodeId = "arduino_" + mac.replace(":", "").takeLast(6)
                val battery = payload[9].toInt().coerceIn(0, 100)
                val isEmergency = payload[10].toInt() == 1

                val readings = listOf(
                    EnvironmentalReading("temp_1", "Ambient Temp", 24.5, "C", System.currentTimeMillis()),
                    EnvironmentalReading("humidity_1", "Rel Humidity", 60.0, "%", System.currentTimeMillis())
                )

                val node = InfrastructureNode(
                    nodeId = nodeId,
                    macAddress = mac,
                    lastSeenTimestamp = System.currentTimeMillis(),
                    batteryPercent = battery,
                    firmwareVersion = "v$version.0.0",
                    isEmergencyTriggered = isEmergency,
                    emergencyCategory = if (isEmergency) "ManualSOS" else null,
                    readings = readings
                )

                repo.updateNode(node)

                if (isEmergency) {
                    MeshLogger.e(TAG, "CRITICAL: SOS Emergency signal triggered from Arduino Node: $nodeId")
                    TelemetryManager.getInstance(context).logEvent("ArduinoSOS", "Triggered by static node $nodeId")
                }
            } catch (e: Exception) {
                errorCount++
                MeshLogger.e(TAG, "Failed parsing infrastructure beacon payload", e)
            }
        }
    }

    private fun pruneStaleBeacons() {
        // Obsolete beacons that have not updated in 45 seconds
        val threshold = System.currentTimeMillis() - 45000L
        repo.sweepStaleNodes(threshold)
    }

    private fun startSimulatedArduinoBeacons() {
        TaskScheduler.getInstance().schedulePeriodic(TASK_SIMULATION, 15000, 0.1) {
            // Simulated heartbeat every 15 seconds
            val payload = byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 0, 85, 0) // Normal heartbeat
            processInfrastructureBeacon("AA:BB:CC:00:11:22", -70, payload)
        }
    }
}
