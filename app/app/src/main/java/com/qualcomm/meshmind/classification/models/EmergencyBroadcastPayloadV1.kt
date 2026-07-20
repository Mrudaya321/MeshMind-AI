package com.qualcomm.meshmind.classification.models

data class EmergencyBroadcastPayloadV1(
    val schemaVersion: Int = 1,
    val emergencyId: String,
    val originNodeId: String,
    val originalText: String,
    val predictedClassIndex: Int,
    val predictedClassLabel: String,
    val confidence: Double,
    val targetResponseRole: String, // String representation of EmergencyResponseRole
    val classificationTimestamp: Long,
    val createdAt: Long,
    val modelVersion: String,
    val taxonomyVersion: String
)
