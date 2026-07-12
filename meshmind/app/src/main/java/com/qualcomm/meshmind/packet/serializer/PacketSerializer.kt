package com.qualcomm.meshmind.packet.serializer

import com.qualcomm.meshmind.exceptions.MeshMindException.PacketParsingException
import com.qualcomm.meshmind.packet.models.MeshFrame
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * Handles the binary serialization of a MeshFrame in Kotlin.
 */
object PacketSerializer {

    /**
     * Converts a structured MeshFrame into a binary payload stream.
     */
    @Throws(PacketParsingException::class)
    fun serialize(frame: MeshFrame?): ByteArray {
        if (frame == null) {
            throw IllegalArgumentException("Cannot serialize a null MeshFrame.")
        }

        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        try {
            dos.writeInt(frame.protocolVersion)
            dos.writeInt(frame.flags)
            dos.writeInt(frame.packetType.ordinal)
            dos.writeInt(frame.ttl)
            dos.writeLong(frame.maxPacketAgeMs)
            dos.writeInt(frame.priority)
            dos.writeInt(frame.hopCount)
            
            dos.writeUTF(frame.packetId)
            dos.writeUTF(frame.sourceNodeId)
            dos.writeUTF(frame.destinationNodeId)
            
            // Previous hop node ID nullable encoding
            if (frame.previousHopNodeId != null) {
                dos.writeBoolean(true)
                dos.writeUTF(frame.previousHopNodeId)
            } else {
                dos.writeBoolean(false)
            }

            // IV nullable encoding
            if (frame.iv != null) {
                dos.writeInt(frame.iv.size)
                dos.write(frame.iv)
            } else {
                dos.writeInt(0)
            }
            
            // Payload encoding
            dos.writeInt(frame.payload.size)
            dos.write(frame.payload)
            
            dos.writeLong(frame.checksum)
            
            dos.flush()
            return baos.toByteArray()
        } catch (e: IOException) {
            throw PacketParsingException("Serialization error occurred during frame packing", e)
        }
    }
}
