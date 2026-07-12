package com.qualcomm.meshmind.packet

import com.qualcomm.meshmind.packet.builder.PacketBuilder
import com.qualcomm.meshmind.packet.checksum.ChecksumCalculator
import com.qualcomm.meshmind.packet.models.PacketType
import com.qualcomm.meshmind.packet.parser.PacketParser
import com.qualcomm.meshmind.packet.serializer.PacketSerializer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PacketSerializationTest {

    @Test
    fun testSerializationDeserializationRoundtrip() {
        val payloadData = "Test Serialization Payload data packet".toByteArray(Charsets.UTF_8)
        val crc = ChecksumCalculator.calculateCrc32(payloadData)
        val ivData = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)

        val originalFrame = PacketBuilder()
            .setProtocolVersion(2)
            .setFlags(8)
            .setPacketType(PacketType.DATA)
            .setTtl(7)
            .setMaxPacketAgeMs(120000L)
            .setPriority(3)
            .setHopCount(2)
            .setPacketId("msg_12345")
            .setSourceNodeId("node_a")
            .setDestinationNodeId("node_b")
            .setPreviousHopNodeId("node_relay")
            .setIv(ivData)
            .setPayload(payloadData)
            .setChecksum(crc)
            .build()

        val serialized = PacketSerializer.serialize(originalFrame)
        assertNotNull(serialized)

        val deserialized = PacketParser.parse(serialized)
        assertNotNull(deserialized)

        assertEquals(originalFrame.protocolVersion, deserialized.protocolVersion)
        assertEquals(originalFrame.flags, deserialized.flags)
        assertEquals(originalFrame.packetType, deserialized.packetType)
        assertEquals(originalFrame.ttl, deserialized.ttl)
        assertEquals(originalFrame.maxPacketAgeMs, deserialized.maxPacketAgeMs)
        assertEquals(originalFrame.priority, deserialized.priority)
        assertEquals(originalFrame.hopCount, deserialized.hopCount)
        assertEquals(originalFrame.packetId, deserialized.packetId)
        assertEquals(originalFrame.sourceNodeId, deserialized.sourceNodeId)
        assertEquals(originalFrame.destinationNodeId, deserialized.destinationNodeId)
        assertEquals(originalFrame.previousHopNodeId, deserialized.previousHopNodeId)
        assertArrayEquals(originalFrame.iv, deserialized.iv)
        assertArrayEquals(originalFrame.payload, deserialized.payload)
        assertEquals(originalFrame.checksum, deserialized.checksum)
    }
}
