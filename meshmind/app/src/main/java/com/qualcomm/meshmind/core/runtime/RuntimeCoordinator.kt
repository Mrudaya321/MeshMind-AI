package com.qualcomm.meshmind.core.runtime

import android.content.Context
import com.qualcomm.meshmind.core.dependency.ServiceLocator
import com.qualcomm.meshmind.logging.MeshLogger
import com.qualcomm.meshmind.state.ApplicationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Centrally orchestrates the startup lifecycle and priority-based initialization of all subsystems.
 */
class RuntimeCoordinator private constructor(private val context: Context) {

    enum class InitializationState {
        NOT_STARTED,
        INITIALIZING,
        SUCCESS,
        FAILED
    }

    private val coordinatorScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _initProgress = MutableStateFlow(0)
    val initProgress: StateFlow<Int> = _initProgress.asStateFlow()

    private val _initState = MutableStateFlow(InitializationState.NOT_STARTED)
    val initState: StateFlow<InitializationState> = _initState.asStateFlow()

    private val _currentInitializingSubsystem = MutableStateFlow("")
    val currentInitializingSubsystem: StateFlow<String> = _currentInitializingSubsystem.asStateFlow()

    companion object {
        private const val TAG = "RuntimeCoordinator"

        @Volatile
        private var instance: RuntimeCoordinator? = null

        fun getInstance(context: Context): RuntimeCoordinator {
            return instance ?: synchronized(this) {
                instance ?: RuntimeCoordinator(context.applicationContext).also { instance = it }
            }
        }
    }

    fun startInitialization() {
        if (_initState.value == InitializationState.INITIALIZING || _initState.value == InitializationState.SUCCESS) {
            return
        }

        _initState.value = InitializationState.INITIALIZING
        coordinatorScope.launch {
            try {
                val manager = SubsystemManager.getInstance()
                val subsystems = manager.getSubsystems().sortedBy { it.initPriority }
                val totalSubsystems = subsystems.size

                MeshLogger.i(TAG, "Starting orchestrated boot sequence for $totalSubsystems subsystems...")

                for ((index, subsystem) in subsystems.withIndex()) {
                    val progress = ((index.toFloat() / totalSubsystems.toFloat()) * 100).toInt()
                    _initProgress.value = progress
                    _currentInitializingSubsystem.value = subsystem.subsystemId
                    MeshLogger.i(TAG, "Initializing: ${subsystem.subsystemId} (priority ${subsystem.initPriority})")

                    try {
                        subsystem.initialize()
                    } catch (e: Exception) {
                        MeshLogger.e(TAG, "Subsystem failed initialization: ${subsystem.subsystemId}", e)
                        // Allow non-critical subsystems to fail/degrade gracefully rather than halting execution
                    }
                }

                // Configuration Linkage Setup
                val configManager = ServiceLocator.get(com.qualcomm.meshmind.configuration.ConfigManager::class.java)
                val storageManager = ServiceLocator.get(com.qualcomm.meshmind.storage.StorageManager::class.java)
                configManager.setStorageProvider(storageManager.getSecureStorage())

                // Warm up node identities
                val identityManager = ServiceLocator.get(com.qualcomm.meshmind.identity.DeviceIdentityManager::class.java)
                identityManager.resolveNodeId()

                // Load initial state caches
                com.qualcomm.meshmind.state.NeighborStateRepository.getInstance().preloadFromDatabase()

                _initProgress.value = 100
                _initState.value = InitializationState.SUCCESS
                ApplicationState.getInstance().setSubsystemsInitialized(true)
                MeshLogger.i(TAG, "Runtime initialization boot sweep completed successfully.")
            } catch (e: Exception) {
                _initState.value = InitializationState.FAILED
                MeshLogger.e(TAG, "Critical coordinator failure during runtime startup sequence", e)
            }
        }
    }
}
