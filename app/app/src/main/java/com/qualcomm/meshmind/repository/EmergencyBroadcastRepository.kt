package com.qualcomm.meshmind.repository

import com.qualcomm.meshmind.database.AppDatabase
import com.qualcomm.meshmind.database.EmergencyBroadcastDao
import com.qualcomm.meshmind.database.EmergencyBroadcastEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class EmergencyBroadcastRepository(private val database: AppDatabase) {
    
    private val dao: EmergencyBroadcastDao = database.emergencyBroadcastDao()

    suspend fun saveBroadcast(
        emergencyId: String,
        originNodeId: String,
        originalText: String,
        predictedClassIndex: Int,
        predictedClassLabel: String,
        confidence: Double,
        targetResponseRole: String,
        destinationNodeId: String?,
        classificationTimestamp: Long,
        createdAt: Long,
        deliveryStatus: String,
        isOutgoing: Boolean,
        modelVersion: String,
        taxonomyVersion: String
    ) = withContext(Dispatchers.IO) {
        dao.insert(
            EmergencyBroadcastEntity(
                emergencyId = emergencyId,
                originNodeId = originNodeId,
                originalText = originalText,
                predictedClassIndex = predictedClassIndex,
                predictedClassLabel = predictedClassLabel,
                confidence = confidence,
                targetResponseRole = targetResponseRole,
                destinationNodeId = destinationNodeId,
                classificationTimestamp = classificationTimestamp,
                createdAt = createdAt,
                deliveryStatus = deliveryStatus,
                isOutgoing = isOutgoing,
                modelVersion = modelVersion,
                taxonomyVersion = taxonomyVersion
            )
        )
    }
    
    suspend fun updateDeliveryStatus(emergencyId: String, status: String) = withContext(Dispatchers.IO) {
        dao.updateDeliveryStatus(emergencyId, status)
    }

    suspend fun getAllBroadcasts(): List<EmergencyBroadcastEntity> = withContext(Dispatchers.IO) {
        dao.getAllBroadcasts()
    }
}
