package com.qualcomm.meshmind.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val conversationId: String,
    val title: String,
    val lastActiveTimestamp: Long
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["conversationId"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["conversationId"])]
)
data class MessageEntity(
    @PrimaryKey val messageId: String,
    val conversationId: String,
    val senderNodeId: String,
    val receiverNodeId: String,
    val timestamp: Long,
    val body: String,
    val deliveryStatus: String, // Created, Queued, Encrypting, Routed, Transmitting, Relayed, Delivered, Acknowledged, Failed, Expired, Deleted
    val relayNodeId: String?,
    val isRead: Boolean,
    
    // Emergency Classification Metadata (nullable for backward compatibility)
    val emergencyClassIndex: Int? = null,
    val emergencyClassLabel: String? = null,
    val emergencyConfidence: Double? = null,
    val classificationTimestamp: Long? = null,
    val taxonomyVersion: String? = null
)

@Entity(tableName = "neighbor_states")
data class NeighborStateEntity(
    @PrimaryKey val nodeId: String,
    val lastSeenTimestamp: Long,
    val rssi: Int,
    val packetLossRate: Double?,
    val queueLength: Int?,
    val ackSuccessRatio: Double?,
    val batteryLevel: Int?,
    val stabilityIndex: Double?
)

@Entity(tableName = "routing_information")
data class RoutingInformationEntity(
    @PrimaryKey val destinationNodeId: String,
    val nextHopNodeId: String,
    val hopCount: Int,
    val sequenceNumber: Int,
    val updatedTimestamp: Long,
    val routeAge: Long,
    val isValid: Boolean
)

@Entity(tableName = "telemetry_history")
data class TelemetryHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val batteryLevel: Int?,
    val wifiRssi: Int?,
    val isWifiConnected: Boolean,
    val bluetoothNeighborCount: Int,
    val cpuExecutionDelayMs: Long
)

@Entity(tableName = "packet_history")
data class PacketHistoryEntity(
    @PrimaryKey val packetId: String,
    val sourceNodeId: String,
    val destinationNodeId: String,
    val hopCount: Int,
    val ttl: Int,
    val checksum: Long,
    val payloadLength: Int,
    val payload: ByteArray?,
    val isOutgoing: Boolean,
    val status: String, // Transmitted, Acknowledged, Expired, Failed, Relayed
    val timestamp: Long
)

@Entity(tableName = "diagnostic_events")
data class DiagnosticEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val taskKey: String,
    val durationMs: Long,
    val message: String
)

@Entity(tableName = "runtime_statistics")
data class RuntimeStatisticsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val memoryUsageBytes: Long,
    val threadCount: Int,
    val activeCoroutinesCount: Int,
    val databaseSize: Long
)

@Entity(tableName = "emergency_events")
data class EmergencyEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val senderNodeId: String,
    val messagePayload: String,
    val severity: String // INFO, WARNING, CRITICAL
)

@Entity(tableName = "device_information")
data class DeviceInformationEntity(
    @PrimaryKey val nodeId: String,
    val hardwareModel: String,
    val osVersion: String,
    val appVersion: String
)
