package com.qualcomm.meshmind.configuration

import android.content.Context
import com.qualcomm.meshmind.core.runtime.BaseSubsystem
import com.qualcomm.meshmind.diagnostics.models.SubsystemHealth
import com.qualcomm.meshmind.logging.MeshLogger
import com.qualcomm.meshmind.storage.SecureStorageProvider

/**
 * Configuration Management Subsystem in Kotlin.
 * Centralizes configuration keys, default options, and updates.
 */
class ConfigManager(context: Context) : BaseSubsystem {

    private val appContext = context.applicationContext
    private var secureStorage: SecureStorageProvider? = null
    
    private var isOperational = false
    private var errorCount: Long = 0

    override val subsystemId: String = "config_manager"
    override val initPriority: Int = 12

    companion object {
        const val KEY_TELEMETRY_INTERVAL_MS = "cfg_telemetry_interval"
        const val KEY_INFERENCE_INTERVAL_MS = "cfg_inference_interval"
        const val KEY_TWIN_SYNC_INTERVAL_MS = "cfg_twin_sync_interval"
        const val KEY_MAX_TTL = "cfg_max_ttl"
        const val KEY_ROUTING_HEARTBEAT_INTERVAL_MS = "cfg_routing_heartbeat_interval"
        const val KEY_LOG_LEVEL = "cfg_log_level"
    }

    override suspend fun initialize() {
        try {
            isOperational = true
            MeshLogger.i(subsystemId, "ConfigManager parameters loaded successfully.")
        } catch (e: Exception) {
            errorCount++
            isOperational = false
            MeshLogger.e(subsystemId, "Failed loading ConfigManager properties", e)
            throw e
        }
    }

    override fun shutdown() {
        isOperational = false
        MeshLogger.i(subsystemId, "ConfigManager subsystem shutdown.")
    }

    override fun getHealth(): SubsystemHealth {
        return SubsystemHealth(
            subsystemId,
            isOperational,
            System.currentTimeMillis(),
            errorCount,
            if (isOperational) "ConfigManager loaded" else "ConfigManager load failed"
        )
    }

    fun setStorageProvider(provider: SecureStorageProvider) {
        this.secureStorage = provider
    }

    fun getTelemetryIntervalMs(): Long {
        return secureStorage?.getInt(KEY_TELEMETRY_INTERVAL_MS, 5000)?.toLong() ?: 5000L
    }

    fun getInferenceIntervalMs(): Long {
        return secureStorage?.getInt(KEY_INFERENCE_INTERVAL_MS, 10000)?.toLong() ?: 10000L
    }

    fun getTwinSyncIntervalMs(): Long {
        return secureStorage?.getInt(KEY_TWIN_SYNC_INTERVAL_MS, 30000)?.toLong() ?: 30000L
    }

    fun getMaxTtl(): Int {
        return secureStorage?.getInt(KEY_MAX_TTL, 10) ?: 10
    }

    fun getRoutingHeartbeatIntervalMs(): Long {
        return secureStorage?.getInt(KEY_ROUTING_HEARTBEAT_INTERVAL_MS, 8000)?.toLong() ?: 8000L
    }

    fun getEmergencyResponseRole(): com.qualcomm.meshmind.classification.models.EmergencyResponseRole {
        val roleStr = secureStorage?.getString("cfg_emergency_response_role", "CIVILIAN") ?: "CIVILIAN"
        return try {
            com.qualcomm.meshmind.classification.models.EmergencyResponseRole.valueOf(roleStr)
        } catch (e: Exception) {
            com.qualcomm.meshmind.classification.models.EmergencyResponseRole.CIVILIAN
        }
    }

    fun setEmergencyResponseRole(role: com.qualcomm.meshmind.classification.models.EmergencyResponseRole) {
        secureStorage?.putString("cfg_emergency_response_role", role.name)
    }
}
