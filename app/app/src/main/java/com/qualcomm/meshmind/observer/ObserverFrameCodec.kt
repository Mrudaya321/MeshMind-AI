package com.qualcomm.meshmind.observer

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Encodes Observer Envelope frames for the persistent TCP socket.
 * Format:
 * [Magic: 2 bytes] = 0x0B, 0x5E
 * [Version: 1 byte] = 0x01
 * [Type: 1 byte]
 * [Total Length: 4 bytes] (Length of Metadata + Payload)
 * [Metadata Length: 2 bytes]
 * [Metadata JSON String: N bytes]
 * [Payload: M bytes]
 */
object ObserverFrameCodec {
    const val MAGIC_1: Byte = 0x0B
    const val MAGIC_2: Byte = 0x5E
    const val VERSION: Byte = 0x01

    const val TYPE_PACKET_OBSERVATION: Byte = 1
    const val TYPE_NODE_SNAPSHOT: Byte = 2
    const val TYPE_ROUTING_SNAPSHOT: Byte = 3
    const val TYPE_GATEWAY_HEARTBEAT: Byte = 4

    const val MAX_FRAME_SIZE = 65536 // 64 KB

    fun encodeFrame(type: Byte, metadataJson: String, payloadBytes: ByteArray = ByteArray(0)): ByteArray? {
        val metadataBytes = metadataJson.toByteArray(StandardCharsets.UTF_8)
        if (metadataBytes.size > 32767) {
            return null // Metadata too large
        }
        
        val totalLength = 2 + metadataBytes.size + payloadBytes.size
        val frameSize = 2 + 1 + 1 + 4 + totalLength
        
        if (frameSize > MAX_FRAME_SIZE) {
            return null
        }

        val buffer = ByteBuffer.allocate(frameSize)
        buffer.put(MAGIC_1)
        buffer.put(MAGIC_2)
        buffer.put(VERSION)
        buffer.put(type)
        buffer.putInt(totalLength)
        buffer.putShort(metadataBytes.size.toShort())
        buffer.put(metadataBytes)
        buffer.put(payloadBytes)

        return buffer.array()
    }
}
