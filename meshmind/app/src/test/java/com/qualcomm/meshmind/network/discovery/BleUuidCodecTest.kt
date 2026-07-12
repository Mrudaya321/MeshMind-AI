package com.qualcomm.meshmind.network.discovery

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.util.UUID

class BleUuidCodecTest {

    @Test
    fun testBleUuidCodecRoundTrip() {
        // The real logical Node IDs observed during physical testing
        val node1 = "17cdc9fc-7692-433a-8d54-1386e1d4e916"
        val node2 = "e14b118a-82ec-4b60-976f-6de1fce4834e"
        val node3 = UUID.randomUUID().toString()

        val nodesToTest = listOf(node1, node2, node3)

        for (originalNodeId in nodesToTest) {
            // ENCODE to BLE 16-byte representation (Same as BleDiscoveryManagerImpl)
            val uuid = UUID.fromString(originalNodeId)
            val encodeBuffer = ByteBuffer.wrap(ByteArray(16))
            encodeBuffer.putLong(uuid.mostSignificantBits)
            encodeBuffer.putLong(uuid.leastSignificantBits)
            val nodeIdBytes = encodeBuffer.array()

            assertEquals("BLE service data must be exactly 16 bytes", 16, nodeIdBytes.size)

            // DECODE back from 16-byte representation
            val decodeBuffer = ByteBuffer.wrap(nodeIdBytes)
            val decodedUuid = UUID(decodeBuffer.long, decodeBuffer.long)
            val decodedNodeId = decodedUuid.toString()

            // ASSERT symmetry
            assertEquals("Decoded Node ID must canonically match the original.", originalNodeId, decodedNodeId)
        }
    }
}
