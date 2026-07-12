package com.qualcomm.meshmind.diagnostics

import com.qualcomm.meshmind.core.runtime.BaseSubsystem
import com.qualcomm.meshmind.core.runtime.SubsystemManager
import com.qualcomm.meshmind.core.scheduling.TaskScheduler
import com.qualcomm.meshmind.diagnostics.models.SubsystemHealth
import com.qualcomm.meshmind.logging.MeshLogger

/**
 * Health Monitor Subsystem in Kotlin.
 * Periodically polls all registered subsystems and prints diagnostic health snapshots.
 */
class HealthMonitor : BaseSubsystem {

    private var isOperational = false
    private var errorCount: Long = 0

    override val subsystemId: String = "health_monitor"
    override val initPriority: Int = 95

    companion object {
        private const val TAG = "HealthMonitor"
        private const val TASK_ID_POLL = "subsystem_health_poll"
    }

    override suspend fun initialize() {
        isOperational = true
        MeshLogger.i(subsystemId, "Health monitor subsystem initialized.")
        
        // Start polling subsystem health (every 30 seconds)
        TaskScheduler.getInstance().schedulePeriodic(TASK_ID_POLL, 30000, 0.05) {
            evaluateSubsystemsHealth()
        }
    }

    override fun shutdown() {
        TaskScheduler.getInstance().cancel(TASK_ID_POLL)
        isOperational = false
        MeshLogger.i(subsystemId, "Health monitor subsystem shutdown.")
    }

    override fun getHealth(): SubsystemHealth {
        return SubsystemHealth(
            subsystemId,
            isOperational,
            System.currentTimeMillis(),
            errorCount,
            if (isOperational) "Health monitoring active" else "Health monitoring disabled"
        )
    }

    private fun evaluateSubsystemsHealth() {
        if (!isOperational) return

        val subsystems = SubsystemManager.getInstance().getSubsystems()
        MeshLogger.i(TAG, "--- Subsystem Health Report ---")
        for (subsystem in subsystems) {
            if (subsystem.subsystemId == subsystemId) continue
            try {
                val health = subsystem.getHealth()
                val status = if (health.isOperational) "ONLINE" else "OFFLINE"
                MeshLogger.i(TAG, "[${subsystem.subsystemId}] Status: $status, Message: ${health.diagnosticMessage}")
            } catch (e: Exception) {
                MeshLogger.e(TAG, "Failed to read health state for ${subsystem.subsystemId}", e)
            }
        }
        MeshLogger.i(TAG, "--------------------------------")
    }
}
