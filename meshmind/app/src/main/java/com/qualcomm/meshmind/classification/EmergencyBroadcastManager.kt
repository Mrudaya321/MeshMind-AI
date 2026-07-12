package com.qualcomm.meshmind.classification

import com.qualcomm.meshmind.classification.models.EmergencyBroadcastPayloadV1
import com.qualcomm.meshmind.classification.models.EmergencyResponseRole
import com.qualcomm.meshmind.communication.ReliableCommunicationManager
import com.qualcomm.meshmind.core.dependency.ServiceLocator
import com.qualcomm.meshmind.identity.DeviceIdentityManager
import com.qualcomm.meshmind.logging.MeshLogger
import com.qualcomm.meshmind.network.routing.RoutingEngine
import java.util.UUID

object EmergencyBroadcastManager {

    private const val TAG = "EmergencyBroadcastManager"

    data class BroadcastAttemptResult(
        val success: Boolean,
        val emergencyId: String?,
        val predictedClassIndex: Int?,
        val predictedClassLabel: String?,
        val confidence: Double?,
        val targetRole: EmergencyResponseRole?,
        val destinationNodeId: String?,
        val status: String,
        val inferenceDurationMs: Long,
        val diagnosticReason: String? = null,
        val runtimeState: String = "READY"
    )

    suspend fun broadcastEmergency(originalText: String): BroadcastAttemptResult {
        val startTime = System.currentTimeMillis()
        val classifier = ServiceLocator.get(EmergencyClassifier::class.java)
        
        // 1. Classification
        val classificationResult = classifier.classify(originalText)
        val inferenceDurationMs = System.currentTimeMillis() - startTime

        if (classificationResult !is EmergencyClassificationResult.Classified) {
            val reason = if (classificationResult is EmergencyClassificationResult.Unavailable) classificationResult.reason else "UNKNOWN_ERROR"
            MeshLogger.w(TAG, "Emergency classification failed. Reason: $reason")
            return BroadcastAttemptResult(
                success = false,
                emergencyId = null,
                predictedClassIndex = null,
                predictedClassLabel = null,
                confidence = null,
                targetRole = null,
                destinationNodeId = null,
                status = "CLASSIFICATION_FAILED",
                inferenceDurationMs = inferenceDurationMs,
                diagnosticReason = reason,
                runtimeState = classifier.runtimeState.name
            )
        }

        // 2. Department Mapping
        val targetRole = EmergencyDepartmentMapper.mapClassToDepartment(classificationResult.classLabel)
        if (targetRole == null) {
            MeshLogger.w(TAG, "Emergency class ${classificationResult.classLabel} has no mapped responder department.")
            return BroadcastAttemptResult(
                success = false,
                emergencyId = null,
                predictedClassIndex = classificationResult.classIndex,
                predictedClassLabel = classificationResult.classLabel,
                confidence = classificationResult.confidence,
                targetRole = null,
                destinationNodeId = null,
                status = "MAPPING_FAILED",
                inferenceDurationMs = inferenceDurationMs
            )
        }
        
        // 3. Destination Resolution
        val routingEngine = ServiceLocator.get(RoutingEngine::class.java)
        val candidates = EmergencyResponseDirectory.getCandidatesByRole(targetRole)
        
        var selectedDestination: String? = null
        var minHopCount = Int.MAX_VALUE
        
        for (candidate in candidates) {
            val hopCount = routingEngine.getHopCount(candidate)
            if (hopCount < Int.MAX_VALUE) {
                if (hopCount < minHopCount) {
                    minHopCount = hopCount
                    selectedDestination = candidate
                } else if (hopCount == minHopCount) {
                    if (selectedDestination == null || candidate < selectedDestination) {
                        selectedDestination = candidate
                    }
                }
            }
        }

        val emergencyId = UUID.randomUUID().toString()
        val identityMgr = ServiceLocator.get(DeviceIdentityManager::class.java)
        val localNodeId = identityMgr.resolveNodeId()
        
        // Construct payload
        val payloadV1 = EmergencyBroadcastPayloadV1(
            emergencyId = emergencyId,
            originNodeId = localNodeId,
            originalText = originalText,
            predictedClassIndex = classificationResult.classIndex,
            predictedClassLabel = classificationResult.classLabel,
            confidence = classificationResult.confidence,
            targetResponseRole = targetRole.name,
            classificationTimestamp = System.currentTimeMillis(),
            createdAt = System.currentTimeMillis(),
            modelVersion = "1.0-8class",
            taxonomyVersion = "1.0-8class"
        )

        // Local Persistence: Log attempt with NO_REACHABLE_RESPONDER if appropriate
        if (selectedDestination == null) {
            MeshLogger.w(TAG, "No reachable responder found for role: $targetRole")
            // TODO: Persist attempt as NO_REACHABLE_RESPONDER in EmergencyBroadcastRepository
            return BroadcastAttemptResult(
                success = false,
                emergencyId = emergencyId,
                predictedClassIndex = classificationResult.classIndex,
                predictedClassLabel = classificationResult.classLabel,
                confidence = classificationResult.confidence,
                targetRole = targetRole,
                destinationNodeId = null,
                status = "NO_REACHABLE_RESPONDER",
                inferenceDurationMs = inferenceDurationMs
            )
        }

        MeshLogger.i(TAG, "Selected destination $selectedDestination for emergency $emergencyId")

        // 4. Handoff to Network
        val commManager = ReliableCommunicationManager.getInstance()
        val sendResult = commManager.sendEmergencyBroadcast(selectedDestination, payloadV1)
        
        val finalStatus = if (sendResult is com.qualcomm.meshmind.communication.SendResult.Enqueued) "ENQUEUED" else "FAILED"
        val success = sendResult is com.qualcomm.meshmind.communication.SendResult.Enqueued

        return BroadcastAttemptResult(
            success = success,
            emergencyId = emergencyId,
            predictedClassIndex = classificationResult.classIndex,
            predictedClassLabel = classificationResult.classLabel,
            confidence = classificationResult.confidence,
            targetRole = targetRole,
            destinationNodeId = selectedDestination,
            status = finalStatus,
            inferenceDurationMs = inferenceDurationMs
        )
    }
}
