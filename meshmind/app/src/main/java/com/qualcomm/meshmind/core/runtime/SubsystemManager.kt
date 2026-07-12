package com.qualcomm.meshmind.core.runtime

import com.qualcomm.meshmind.logging.MeshLogger
import com.qualcomm.meshmind.diagnostics.models.SubsystemHealth
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Manages the registration, initialization, and shutdown sequence of all MeshMind subsystems.
 * Ensures dependencies are initialized in priority order.
 */
class SubsystemManager private constructor() {

    private val subsystems = CopyOnWriteArrayList<BaseSubsystem>()
    private val subsystemLookup = ConcurrentHashMap<String, BaseSubsystem>()
    private val initializedStatus = ConcurrentHashMap<String, Boolean>()
    
    @Volatile
    var isInitialized = false
        private set

    companion object {
        private const val TAG = "SubsystemManager"
        
        @Volatile
        private var instance: SubsystemManager? = null

        fun getInstance(): SubsystemManager {
            return instance ?: synchronized(this) {
                instance ?: SubsystemManager().also { instance = it }
            }
        }
    }

    /**
     * Registers a new subsystem into the platform.
     */
    @Synchronized fun registerSubsystem(subsystem: BaseSubsystem) {
        if (isInitialized) {
            throw IllegalStateException("Cannot register subsystems after initialization has completed.")
        }
        if (!subsystemLookup.containsKey(subsystem.subsystemId)) {
            subsubsystemsListAdd(subsystem)
            subsystemLookup[subsystem.subsystemId] = subsystem
            initializedStatus[subsystem.subsystemId] = false
            MeshLogger.i(TAG, "Subsystem registered: ${subsystem.subsystemId} (Priority: ${subsystem.initPriority})")
        }
    }

    private fun subsubsystemsListAdd(subsystem: BaseSubsystem) {
        subsystems.add(subsystem)
    }

    /**
     * Executes the bootstrap initialization sequence. Sorts subsystems by initPriority.
     */
    suspend fun initializeAll() {
        if (isInitialized) return
        
        MeshLogger.i(TAG, "Starting MeshMind Subsystem Initialization Sequence...")

        // Sort by priority (ascending: lower numbers run first)
        val sortedList = subsystems.sortedBy { it.initPriority }

        for (subsystem in sortedList) {
            val id = subsystem.subsystemId
            MeshLogger.i(TAG, "Initializing subsystem: $id")
            try {
                val start = System.currentTimeMillis()
                subsystem.initialize()
                initializedStatus[id] = true
                val elapsed = System.currentTimeMillis() - start
                MeshLogger.i(TAG, "Subsystem $id initialized successfully in ${elapsed}ms")
            } catch (e: Exception) {
                MeshLogger.e(TAG, "CRITICAL: Subsystem $id failed initialization: ${e.message}", e)
                initializedStatus[id] = false
            }
        }
        isInitialized = true
        MeshLogger.i(TAG, "Subsystem Initialization completed.")
    }

    /**
     * Gracefully shuts down all subsystems in reverse priority order.
     */
    @Synchronized fun shutdownAll() {
        MeshLogger.i(TAG, "Starting MeshMind Subsystem Shutdown Sequence...")
        
        // Reverse order of initialization (descending priority)
        val reverseSubsystems = subsystems.sortedByDescending { it.initPriority }

        for (subsystem in reverseSubsystems) {
            val id = subsystem.subsystemId
            if (initializedStatus[id] == true) {
                try {
                    MeshLogger.i(TAG, "Stopping subsystem: $id")
                    subsystem.shutdown()
                    initializedStatus[id] = false
                } catch (e: Exception) {
                    MeshLogger.e(TAG, "Error shutting down subsystem $id: ${e.message}", e)
                }
            }
        }
        isInitialized = false
        MeshLogger.i(TAG, "Subsystem Shutdown completed.")
    }

    fun getSubsystems(): List<BaseSubsystem> = subsystems.toList()

    fun getSubsystemsHealth(): List<SubsystemHealth> = subsystems.map { it.getHealth() }

    @Suppress("UNCHECKED_CAST")
    fun <T : BaseSubsystem> getSubsystem(id: String): T? = subsystemLookup[id] as? T

    fun isSubsystemInitialized(id: String): Boolean = initializedStatus[id] ?: false
}
