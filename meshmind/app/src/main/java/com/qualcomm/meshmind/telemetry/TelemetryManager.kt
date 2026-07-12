package com.qualcomm.meshmind.telemetry

import android.content.Context
import com.qualcomm.meshmind.core.dependency.ServiceLocator
import com.qualcomm.meshmind.core.runtime.BaseSubsystem
import com.qualcomm.meshmind.core.runtime.SubsystemManager
import com.qualcomm.meshmind.core.scheduling.TaskScheduler
import com.qualcomm.meshmind.diagnostics.models.SubsystemHealth
import com.qualcomm.meshmind.digitaltwin.communication.DigitalTwinClient
import com.qualcomm.meshmind.identity.DeviceIdentityManager
import com.qualcomm.meshmind.logging.MeshLogger
import com.qualcomm.meshmind.network.routing.RoutingEngine
import com.qualcomm.meshmind.state.NeighborStateRepository
import com.qualcomm.meshmind.state.RoutingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Aggregates on-device operational state snapshots and events in Kotlin.
 * Feeds structured summaries asynchronously into the passive DigitalTwinClient.
 */
class TelemetryManager(private val context: Context) : BaseSubsystem {

    private val telemetryScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var isOperational = false
    private var errorCount: Long = 0
    private var startupTime: Long = 0
    private var localNodeId: String = "unassigned_node"

    companion object {
        private const val TAG = "TelemetryManager"
        private const val TASK_TELEMETRY_SNAP = "telemetry_periodic_snapshot"
        
        @Volatile
        private var instance: TelemetryManager? = null

        fun getInstance(context: Context): TelemetryManager {
            return instance ?: synchronized(this) {
                instance ?: TelemetryManager(context).also { instance = it }
            }
        }
    }

    override val subsystemId: String = "telemetry_manager"
    override val initPriority: Int = 85

    override suspend fun initialize() {
        try {
            startupTime = System.currentTimeMillis()
            val identityMgr = ServiceLocator.get(DeviceIdentityManager::class.java)
            localNodeId = identityMgr.resolveNodeId()

            isOperational = true
            MeshLogger.i(TAG, "MMP Telemetry Manager online.")

            // Schedule periodic status summaries every 12 seconds
            TaskScheduler.getInstance().schedulePeriodic(TASK_TELEMETRY_SNAP, 12000, 0.05) {
                captureAndStreamSnapshot()
            }
        } catch (e: Exception) {
            errorCount++
            isOperational = false
            MeshLogger.e(TAG, "Failed initializing Telemetry Manager", e)
            throw e
        }
    }

    override fun shutdown() {
        TaskScheduler.getInstance().cancel(TASK_TELEMETRY_SNAP)
        isOperational = false
        MeshLogger.i(TAG, "MMP Telemetry Manager shutdown.")
    }

    override fun getHealth(): SubsystemHealth {
        return SubsystemHealth(
            subsystemId,
            isOperational,
            System.currentTimeMillis(),
            errorCount,
            if (isOperational) "Aggregating telemetry logs" else "Offline"
        )
    }

    /**
     * Publishes incremental/spontaneous events (e.g. neighbor discovery, route invalidation).
     */
    fun logEvent(name: String, description: String) {
        if (!isOperational) return

        telemetryScope.launch {
            try {
                val payload = "EVENT:$localNodeId:$name:$description:${System.currentTimeMillis()}"
                val client = ServiceLocator.get(DigitalTwinClient::class.java)
                client.pushTelemetrySnapshot(payload)
            } catch (ignored: Exception) {}
        }
    }

    private fun captureAndStreamSnapshot() {
        if (!isOperational) return

        telemetryScope.launch {
            try {
                val sb = StringBuilder()
                val now = System.currentTimeMillis()
                val uptime = now - startupTime
                
                // Aggregated stats
                val neighborsCount = NeighborStateRepository.getInstance().getAllNeighbors().size
                val routesCount = RoutingState.getInstance().getAllRoutes().size
                
                sb.append("SNAPSHOT\n")
                sb.append("nodeId:$localNodeId\n")
                sb.append("uptimeMs:$uptime\n")
                sb.append("timestamp:$now\n")
                sb.append("neighbors:$neighborsCount\n")
                sb.append("routes:$routesCount\n")
                
                val infraNodes = com.qualcomm.meshmind.arduino.InfrastructureRepository.getInstance().getAllNodes()
                sb.append("infraCount:${infraNodes.size}\n")
                for (infra in infraNodes) {
                    sb.append("infra:${infra.nodeId}:${infra.batteryPercent}:${infra.isEmergencyTriggered}\n")
                }
                
                // Append Subsystems Health Reports
                val manager = SubsystemManager.getInstance()
                val healthReports = manager.getSubsystemsHealth()
                sb.append("HEALTH_START\n")
                for (health in healthReports) {
                    sb.append("${health.subsystemName}:${health.isOperational}:${health.errorCount}:${health.diagnosticMessage}\n")
                }
                sb.append("HEALTH_END\n")

                val client = ServiceLocator.get(DigitalTwinClient::class.java)
                client.pushTelemetrySnapshot(sb.toString())

                // Also persist telemetry to local database
                val telemetryRepo = ServiceLocator.get(com.qualcomm.meshmind.repository.TelemetryRepository::class.java)
                val collector = ServiceLocator.get(com.qualcomm.meshmind.telemetry.SystemTelemetryCollector::class.java)
                val rawStats = collector.collectCurrentTelemetry()

                telemetryRepo.saveTelemetry(
                    batteryLevel = rawStats.batteryLevel,
                    wifiRssi = rawStats.wifiRssi,
                    isWifiConnected = rawStats.isWifiConnected,
                    bluetoothNeighborCount = neighborsCount,
                    cpuDelayMs = rawStats.cpuExecutionDelayMs
                )
            } catch (e: Exception) {
                errorCount++
                MeshLogger.e(TAG, "Failed aggregating telemetry snapshot", e)
            }
        }
    }
}
