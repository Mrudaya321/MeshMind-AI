package com.qualcomm.meshmind.validation

import com.qualcomm.meshmind.core.runtime.BaseSubsystem
import com.qualcomm.meshmind.diagnostics.models.SubsystemHealth
import com.qualcomm.meshmind.logging.MeshLogger

/**
 * Orchestrates verification testing campaigns.
 */
class ValidationManager : BaseSubsystem {

    private var isOperational = false
    private var errorCount: Long = 0
    private var testRunsCount = 0

    companion object {
        private const val TAG = "ValidationManager"
        
        @Volatile
        private var instance: ValidationManager? = null

        fun getInstance(): ValidationManager {
            return instance ?: synchronized(this) {
                instance ?: ValidationManager().also { instance = it }
            }
        }
    }

    override val subsystemId: String = "validation_manager"
    override val initPriority: Int = 96 // Initialized at end of boot loop

    override suspend fun initialize() {
        isOperational = true
        MeshLogger.i(TAG, "MMP Validation Manager Subsystem online.")
    }

    override fun shutdown() {
        isOperational = false
        MeshLogger.i(TAG, "MMP Validation Manager Subsystem offline.")
    }

    override fun getHealth(): SubsystemHealth {
        return SubsystemHealth(
            subsystemId,
            isOperational,
            System.currentTimeMillis(),
            errorCount,
            if (isOperational) "Validation engine active (Tests executed: $testRunsCount)" else "Offline"
        )
    }

    /**
     * Triggers a simulated network fault injection campaign.
     */
    fun runFaultInjectionCampaign(targetNodeId: String) {
        if (!isOperational) return
        testRunsCount++
        
        MeshLogger.w(TAG, "Starting fault injection campaign against node: $targetNodeId")
        FaultInjector.getInstance().injectNeighborLoss(targetNodeId)
    }
}
