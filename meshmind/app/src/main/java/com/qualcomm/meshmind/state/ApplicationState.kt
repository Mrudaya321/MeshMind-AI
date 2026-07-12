package com.qualcomm.meshmind.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks global application runtime states reactively using StateFlow.
 */
class ApplicationState private constructor() {

    private val _isEmergencyMode = MutableStateFlow(false)
    val isEmergencyMode: StateFlow<Boolean> = _isEmergencyMode.asStateFlow()

    private val _isDeveloperMode = MutableStateFlow(false)
    val isDeveloperMode: StateFlow<Boolean> = _isDeveloperMode.asStateFlow()

    private val _isSubsystemsInitialized = MutableStateFlow(false)
    val isSubsystemsInitialized: StateFlow<Boolean> = _isSubsystemsInitialized.asStateFlow()

    companion object {
        @Volatile
        private var instance: ApplicationState? = null

        fun getInstance(): ApplicationState {
            return instance ?: synchronized(this) {
                instance ?: ApplicationState().also { instance = it }
            }
        }
    }

    fun setEmergencyMode(active: Boolean) {
        _isEmergencyMode.value = active
    }

    fun setDeveloperMode(enabled: Boolean) {
        _isDeveloperMode.value = enabled
    }

    fun setSubsystemsInitialized(initialized: Boolean) {
        _isSubsystemsInitialized.value = initialized
    }
}
