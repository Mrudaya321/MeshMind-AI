package com.qualcomm.meshmind.telemetry

import android.content.Context
import com.qualcomm.meshmind.core.dependency.ServiceLocator
import com.qualcomm.meshmind.core.runtime.BaseSubsystem
import com.qualcomm.meshmind.core.runtime.SubsystemManager
import com.qualcomm.meshmind.core.scheduling.TaskScheduler
import com.qualcomm.meshmind.diagnostics.models.SubsystemHealth
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
                // DigitalTwinClient removed in Phase 13
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
