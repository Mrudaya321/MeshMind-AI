package com.qualcomm.meshmind.arduino

/**
 * Immutable representations of static Arduino infrastructure nodes.
 */
data class EnvironmentalReading(
    val sensorId: String,
    val sensorName: String,
    val value: Double,
    val unit: String,
    val timestamp: Long
)

data class InfrastructureNode(
    val nodeId: String,
    val macAddress: String,
    val lastSeenTimestamp: Long,
    val batteryPercent: Int,
    val firmwareVersion: String,
    val isEmergencyTriggered: Boolean,
    val emergencyCategory: String?,
    val readings: List<EnvironmentalReading>
)
