package com.qualcomm.meshmind.repository

import com.qualcomm.meshmind.models.NeighborNodeState
import com.qualcomm.meshmind.state.NeighborStateRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class NeighborStateRepositoryTest {

    private lateinit var repository: NeighborStateRepository

    @Before
    fun setUp() {
        repository = NeighborStateRepository.getInstance()
        repository.clear()
    }

    @Test
    fun testUpdateAndRetrieveNeighbor() {
        val testNodeId = "node_abc"
        val state = NeighborNodeState(
            nodeId = testNodeId,
            rssi = -60,
            packetLossRate = null,
            queueLength = null,
            ackSuccessRatio = null,
            batteryLevel = null,
            stabilityIndex = null,
            lastSeenTimestamp = System.currentTimeMillis()
        )

        repository.updateNeighbor(testNodeId, state)
        
        val retrieved = repository.getNeighbor(testNodeId)
        assertNotNull(retrieved)
        assertEquals(-60, retrieved?.rssi)
        assertEquals(testNodeId, retrieved?.nodeId)
    }

    @Test
    fun testRemoveNeighbor() {
        val testNodeId = "node_delete"
        val state = NeighborNodeState(
            nodeId = testNodeId,
            rssi = -75,
            packetLossRate = null,
            queueLength = null,
            ackSuccessRatio = null,
            batteryLevel = null,
            stabilityIndex = null,
            lastSeenTimestamp = System.currentTimeMillis()
        )

        repository.updateNeighbor(testNodeId, state)
        assertNotNull(repository.getNeighbor(testNodeId))

        repository.removeNeighbor(testNodeId)
        assertEquals(null, repository.getNeighbor(testNodeId))
    }
}
