package com.qualcomm.meshmind.release

import com.qualcomm.meshmind.core.runtime.BaseSubsystem
import com.qualcomm.meshmind.diagnostics.models.SubsystemHealth
import com.qualcomm.meshmind.logging.MeshLogger

/**
 * Governs compiler build configurations, semantic version constraints, and privacy logging filters.
 */
class ReleaseManager : BaseSubsystem {

    enum class BuildVariant {
        DEVELOPMENT,
        INTERNAL_TESTING,
        HARDWARE_VALIDATION,
        PERFORMANCE_BENCHMARKING,
        DEMONSTRATION,
        PRODUCTION_RELEASE
    }

    private var isOperational = false
    private var errorCount: Long = 0
    private var activeVariant = BuildVariant.DEVELOPMENT

    // Feature Flags default states
    private var developerModeEnabled = false
    private var diagnosticsVisible = false
    private var telemetryCompressionEnabled = true

    companion object {
        const val APP_VERSION = "1.2.0"
        const val PROTOCOL_VERSION = 2

        private const val TAG = "ReleaseManager"
        
        @Volatile
        private var instance: ReleaseManager? = null

        fun getInstance(): ReleaseManager {
            return instance ?: synchronized(this) {
                instance ?: ReleaseManager().also { instance = it }
            }
        }
    }

    override val subsystemId: String = "release_manager"
    override val initPriority: Int = 98 // Runs last

    override suspend fun initialize() {
        isOperational = true
        MeshLogger.i(TAG, "MMP Release Manager active (Semantic Version: $APP_VERSION, Variant: $activeVariant)")
    }

    override fun shutdown() {
        isOperational = false
        MeshLogger.i(TAG, "MMP Release Manager offline.")
    }

    override fun getHealth(): SubsystemHealth {
        return SubsystemHealth(
            subsystemId,
            isOperational,
            System.currentTimeMillis(),
            errorCount,
            if (isOperational) "Variant: $activeVariant (Secure logging: ${if (activeVariant == BuildVariant.PRODUCTION_RELEASE) "Active" else "Inactive"})" else "Offline"
        )
    }

    // --- Build Variant APIs ---
    fun getActiveVariant(): BuildVariant = activeVariant

    fun setActiveVariant(variant: BuildVariant) {
        activeVariant = variant
        // Auto-configure feature flags when variant changes
        when (variant) {
            BuildVariant.PRODUCTION_RELEASE -> {
                developerModeEnabled = false
                diagnosticsVisible = false
                telemetryCompressionEnabled = true
            }
            BuildVariant.DEVELOPMENT -> {
                developerModeEnabled = true
                diagnosticsVisible = true
                telemetryCompressionEnabled = false
            }
            else -> {
                developerModeEnabled = false
                diagnosticsVisible = true
                telemetryCompressionEnabled = true
            }
        }
    }

    // --- Feature Flags APIs ---
    fun isDeveloperModeEnabled(): Boolean = developerModeEnabled

    fun setDeveloperModeEnabled(enabled: Boolean) {
        if (activeVariant != BuildVariant.PRODUCTION_RELEASE) {
            developerModeEnabled = enabled
        }
    }

    fun isDiagnosticsVisible(): Boolean = diagnosticsVisible

    fun setDiagnosticsVisible(visible: Boolean) {
        if (activeVariant != BuildVariant.PRODUCTION_RELEASE) {
            diagnosticsVisible = visible
        }
    }

    fun isTelemetryCompressionEnabled(): Boolean = telemetryCompressionEnabled

    /**
     * Redacts payload strings under production variant runs to guard message privacy.
     */
    fun redactLogPayload(payload: String): String {
        return if (activeVariant != BuildVariant.PRODUCTION_RELEASE && developerModeEnabled) {
            payload
        } else {
            "[REDACTED PAYLOAD - ENCRYPTED]"
        }
    }
}
