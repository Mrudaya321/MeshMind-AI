package com.qualcomm.meshmind.diagnostics

import com.qualcomm.meshmind.core.runtime.BaseSubsystem
import com.qualcomm.meshmind.diagnostics.models.SubsystemHealth
import com.qualcomm.meshmind.logging.MeshLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * Diagnostic Manager Subsystem in Kotlin.
 * Profiles task executions, latency, and memory utilization across the node.
 */
class DiagnosticsManager : BaseSubsystem {

    private val latencyMetrics = ConcurrentHashMap<String, Long>()
    
    private var isOperational = false
    private var errorCount: Long = 0

    override val subsystemId: String = "diagnostics_manager"
    override val initPriority: Int = 5

    override suspend fun initialize() {
        isOperational = true
        MeshLogger.i(subsystemId, "Diagnostics manager subsystem initialized.")
    }

    override fun shutdown() {
        isOperational = false
        latencyMetrics.clear()
        MeshLogger.i(subsystemId, "Diagnostics manager subsystem shutdown.")
    }

    override fun getHealth(): SubsystemHealth {
        return SubsystemHealth(
            subsystemId,
            isOperational,
            System.currentTimeMillis(),
            errorCount,
            if (isOperational) "Diagnostics engine online" else "Diagnostics engine failed"
        )
    }

    /**
     * Records elapsed latency for a specific subsystem activity.
     */
    fun recordLatency(taskKey: String, elapsedMs: Long) {
        if (!isOperational) return
        latencyMetrics[taskKey] = elapsedMs
        MeshLogger.d(subsystemId, "Diagnostic Profile [$taskKey]: ${elapsedMs}ms")
    }

    fun getLatency(taskKey: String): Long {
        return latencyMetrics[taskKey] ?: -1L
    }
}
