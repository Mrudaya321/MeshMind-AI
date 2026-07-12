package com.qualcomm.meshmind.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "emergency_broadcasts")
data class EmergencyBroadcastEntity(
    @PrimaryKey val emergencyId: String,
    val originNodeId: String,
    val originalText: String,
    val predictedClassIndex: Int,
    val predictedClassLabel: String,
    val confidence: Double,
    val targetResponseRole: String,
    val destinationNodeId: String?,
    val classificationTimestamp: Long,
    val createdAt: Long,
    val deliveryStatus: String,
    val isOutgoing: Boolean,
    val modelVersion: String,
    val taxonomyVersion: String
)
