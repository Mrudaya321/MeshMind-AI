package com.qualcomm.meshmind.observer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class ObserverEnvelopeCodecTest {

    @Test
    fun testEncodePacketObservation() {
        val type = ObserverFrameCodec.TYPE_PACKET_OBSERVATION
        val metadata = "{\"timestamp\":12345,\"stage\":\"LOCAL_ENQUEUED\"}"
        val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04)

        val frameBytes = ObserverFrameCodec.encodeFrame(type, metadata, payload)
        assertNotNull(frameBytes)
        
        val buffer = ByteBuffer.wrap(frameBytes)
        assertEquals(ObserverFrameCodec.MAGIC_1, buffer.get())
        assertEquals(ObserverFrameCodec.MAGIC_2, buffer.get())
        assertEquals(ObserverFrameCodec.VERSION, buffer.get())
        assertEquals(type, buffer.get())
        
        val totalLength = buffer.getInt()
        assertEquals(2 + metadata.toByteArray().size + payload.size, totalLength)
        
        val metaLen = buffer.getShort().toInt()
        assertEquals(metadata.toByteArray().size, metaLen)
        
        val metaBytes = ByteArray(metaLen)
        buffer.get(metaBytes)
        assertEquals(metadata, String(metaBytes))
        
        val outPayload = ByteArray(payload.size)
        buffer.get(outPayload)
        assertArrayEquals(payload, outPayload)
    }

    @Test
    fun testEncodeHeartbeat() {
        val type = ObserverFrameCodec.TYPE_GATEWAY_HEARTBEAT
        val metadata = "{\"timestamp\":12345}"
        
        val frameBytes = ObserverFrameCodec.encodeFrame(type, metadata)
        assertNotNull(frameBytes)
        
        val buffer = ByteBuffer.wrap(frameBytes)
        buffer.position(4) // skip magic/version/type
        val totalLength = buffer.getInt()
        assertEquals(2 + metadata.toByteArray().size, totalLength)
        
        val metaLen = buffer.getShort().toInt()
        assertEquals(metadata.toByteArray().size, metaLen)
        
        val metaBytes = ByteArray(metaLen)
        buffer.get(metaBytes)
        assertEquals(metadata, String(metaBytes))
        
        assertEquals(buffer.limit(), buffer.position()) // No payload
    }

    @Test
    fun testEncodeTooLargeMetadata() {
        val type = ObserverFrameCodec.TYPE_PACKET_OBSERVATION
        val largeMetadata = "A".repeat(40000)
        
        val frameBytes = ObserverFrameCodec.encodeFrame(type, largeMetadata)
        assertNull(frameBytes)
    }
}
