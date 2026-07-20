package com.qualcomm.meshmind.packet.parser

import com.qualcomm.meshmind.exceptions.MeshMindException.PacketParsingException
import com.qualcomm.meshmind.packet.models.MeshFrame
import com.qualcomm.meshmind.packet.models.PacketType
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException

/**
 * Parsers binary data packets back into structured MeshFrame objects in Kotlin.
 */
object PacketParser {

    /**
     * De-serializes raw bytes into a structured MeshFrame.
     */
    @Throws(PacketParsingException::class)
    fun parse(rawBytes: ByteArray?): MeshFrame {
        if (rawBytes == null || rawBytes.isEmpty()) {
            throw IllegalArgumentException("Cannot parse empty or null byte buffer.")
        }

        val bais = ByteArrayInputStream(rawBytes)
        val dis = DataInputStream(bais)

        try {
            val protocolVersion = dis.readInt()
            val flags = dis.readInt()
            
            val typeOrdinal = dis.readInt()
            val packetType = PacketType.values().getOrElse(typeOrdinal) { PacketType.DATA }
            
            val ttl = dis.readInt()
            val maxPacketAgeMs = dis.readLong()
            val priority = dis.readInt()
            val hopCount = dis.readInt()
            
            val packetId = dis.readUTF()
            val sourceNodeId = dis.readUTF()
            val destinationNodeId = dis.readUTF()
            
            val hasPreviousHop = dis.readBoolean()
            val previousHopNodeId = if (hasPreviousHop) dis.readUTF() else null
            
            val ivLength = dis.readInt()
            val iv = if (ivLength > 0) {
                ByteArray(ivLength).also {
                    dis.readFully(it)
                }
            } else {
                null
            }

            val payloadLength = dis.readInt()
            val payload = ByteArray(payloadLength)
            dis.readFully(payload)
            
            val checksum = dis.readLong()

            return MeshFrame(
                protocolVersion = protocolVersion,
                flags = flags,
                packetType = packetType,
                ttl = ttl,
                maxPacketAgeMs = maxPacketAgeMs,
                priority = priority,
                hopCount = hopCount,
                packetId = packetId,
                sourceNodeId = sourceNodeId,
                destinationNodeId = destinationNodeId,
                previousHopNodeId = previousHopNodeId,
                iv = iv,
                payload = payload,
                checksum = checksum
            )
        } catch (e: IOException) {
            throw PacketParsingException("Failed parsing binary stream into MeshFrame", e)
        }
    }
}
