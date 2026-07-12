package com.qualcomm.meshmind.models

/**
 * Immutable data representation of a neighbor node's status, link quality, and predicted reliability.
 */
data class NeighborNodeState(
    val nodeId: String,
    val rssi: Int,
    val packetLossRate: Double?,
    val queueLength: Int?,
    val ackSuccessRatio: Double?,
    val batteryLevel: Int?,
    val stabilityIndex: Double?,
    val lastSeenTimestamp: Long
)
