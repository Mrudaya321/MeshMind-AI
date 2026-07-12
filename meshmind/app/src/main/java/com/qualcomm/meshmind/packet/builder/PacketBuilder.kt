package com.qualcomm.meshmind.packet.builder

import com.qualcomm.meshmind.packet.models.MeshFrame
import com.qualcomm.meshmind.packet.models.PacketType
import java.util.UUID

/**
 * Fluent builder class to compile a valid MeshFrame protocol frame in Kotlin.
 */
class PacketBuilder {

    private var protocolVersion = 1
    private var flags = 0
    private var packetType = PacketType.DATA
    private var ttl = 10
    private var maxPacketAgeMs = 30000L
    private var priority = 1
    private var hopCount = 0
    private var packetId = UUID.randomUUID().toString()
    private var sourceNodeId: String? = null
    private var destinationNodeId: String? = null
    private var previousHopNodeId: String? = null
    private var iv: ByteArray? = null
    private var payload: ByteArray = ByteArray(0)
    private var checksum: Long = 0

    fun setProtocolVersion(version: Int): PacketBuilder = apply { this.protocolVersion = version }
    fun setFlags(flags: Int): PacketBuilder = apply { this.flags = flags }
    fun setPacketType(type: PacketType): PacketBuilder = apply { this.packetType = type }
    fun setTtl(ttl: Int): PacketBuilder = apply { this.ttl = ttl }
    fun setMaxPacketAgeMs(maxAge: Long): PacketBuilder = apply { this.maxPacketAgeMs = maxAge }
    fun setPriority(priority: Int): PacketBuilder = apply { this.priority = priority }
    fun setHopCount(hopCount: Int): PacketBuilder = apply { this.hopCount = hopCount }
    fun setPacketId(packetId: String): PacketBuilder = apply { this.packetId = packetId }
    fun setSourceNodeId(sourceNodeId: String): PacketBuilder = apply { this.sourceNodeId = sourceNodeId }
    fun setDestinationNodeId(destinationNodeId: String): PacketBuilder = apply { this.destinationNodeId = destinationNodeId }
    fun setPreviousHopNodeId(prevHop: String?): PacketBuilder = apply { this.previousHopNodeId = prevHop }
    fun setIv(iv: ByteArray?): PacketBuilder = apply { this.iv = iv }
    fun setPayload(payload: ByteArray): PacketBuilder = apply { this.payload = payload }
    fun setChecksum(checksum: Long): PacketBuilder = apply { this.checksum = checksum }

    fun build(): MeshFrame {
        val src = sourceNodeId ?: throw IllegalStateException("Packet source node ID must be specified.")
        val dest = destinationNodeId ?: throw IllegalStateException("Packet destination node ID must be specified.")
        return MeshFrame(
            protocolVersion = protocolVersion,
            flags = flags,
            packetType = packetType,
            ttl = ttl,
            maxPacketAgeMs = maxPacketAgeMs,
            priority = priority,
            hopCount = hopCount,
            packetId = packetId,
            sourceNodeId = src,
            destinationNodeId = dest,
            previousHopNodeId = previousHopNodeId,
            iv = iv,
            payload = payload,
            checksum = checksum
        )
    }
}
