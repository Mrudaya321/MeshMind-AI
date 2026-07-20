package com.qualcomm.meshmind.packet.models

import com.qualcomm.meshmind.packet.checksum.ChecksumCalculator

enum class PacketType {
    DATA,
    ACK,
    HELLO,
    DISTANCE_VECTOR,
    TELEMETRY,
    EMERGENCY,
    ARDUINO_INFRASTRUCTURE,
    DIGITAL_TWIN,
    CONTROL
}

/**
 * Production-ready MeshMind Frame (MMF) representation in Kotlin.
 */
data class MeshFrame(
    val protocolVersion: Int,
    val flags: Int,
    val packetType: PacketType,
    val ttl: Int,
    val maxPacketAgeMs: Long,
    val priority: Int,
    val hopCount: Int,
    val packetId: String,
    val sourceNodeId: String,
    val destinationNodeId: String,
    val previousHopNodeId: String?,
    val iv: ByteArray?,
    val payload: ByteArray,
    val checksum: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MeshFrame) return false

        if (protocolVersion != other.protocolVersion) return false
        if (flags != other.flags) return false
        if (packetType != other.packetType) return false
        if (ttl != other.ttl) return false
        if (maxPacketAgeMs != other.maxPacketAgeMs) return false
        if (priority != other.priority) return false
        if (hopCount != other.hopCount) return false
        if (packetId != other.packetId) return false
        if (sourceNodeId != other.sourceNodeId) return false
        if (destinationNodeId != other.destinationNodeId) return false
        if (previousHopNodeId != other.previousHopNodeId) return false
        if (iv != null) {
            if (other.iv == null) return false
            if (!iv.contentEquals(other.iv)) return false
        } else if (other.iv != null) return false
        if (!payload.contentEquals(other.payload)) return false
        if (checksum != other.checksum) return false

        return true
    }

    override fun hashCode(): Int {
        var result = protocolVersion
        result = 31 * result + flags
        result = 31 * result + packetType.hashCode()
        result = 31 * result + ttl
        result = 31 * result + maxPacketAgeMs.hashCode()
        result = 31 * result + priority
        result = 31 * result + hopCount
        result = 31 * result + packetId.hashCode()
        result = 31 * result + sourceNodeId.hashCode()
        result = 31 * result + destinationNodeId.hashCode()
        result = 31 * result + (previousHopNodeId?.hashCode() ?: 0)
        result = 31 * result + (iv?.contentHashCode() ?: 0)
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + checksum.hashCode()
        return result
    }

    /**
     * Validates header structure values.
     */
    fun isValid(): Boolean {
        if (protocolVersion <= 0) return false
        if (ttl <= 0) return false
        if (packetId.isBlank() || sourceNodeId.isBlank() || destinationNodeId.isBlank()) return false
        
        // Check computed checksum on payload
        val computed = ChecksumCalculator.calculateCrc32(payload)
        return computed == checksum
    }
}
