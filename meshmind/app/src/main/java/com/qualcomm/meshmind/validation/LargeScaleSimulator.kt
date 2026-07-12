package com.qualcomm.meshmind.validation

import com.qualcomm.meshmind.models.NeighborNodeState
import com.qualcomm.meshmind.state.NeighborStateRepository

/**
 * Large-Scale Node Simulator for verification.
 * Automatically generates simulated chain, star, or grid neighbor state node coordinates.
 */
class LargeScaleSimulator {

    companion object {
        @Volatile
        private var instance: LargeScaleSimulator? = null

        fun getInstance(): LargeScaleSimulator {
            return instance ?: synchronized(this) {
                instance ?: LargeScaleSimulator().also { instance = it }
            }
        }
    }

    /**
     * Spawns simulated chain topology: Node1 -> Node2 -> Node3...
     */
    fun createChainTopology(nodeCount: Int) {
        val repo = NeighborStateRepository.getInstance()
        repo.clear()
        
        for (i in 1..nodeCount) {
            val nodeId = "virtual_node_$i"
            val state = NeighborNodeState(
                nodeId = nodeId,
                rssi = -60 - (i * 2), // Farther nodes have worse RSSI
                packetLossRate = 0.02 * i,
                queueLength = i % 3,
                ackSuccessRatio = 0.98,
                batteryLevel = 90 - i,
                stabilityIndex = 0.9 - (i * 0.01),
                lastSeenTimestamp = System.currentTimeMillis()
            )
            repo.updateNeighbor(nodeId, state)
        }
    }

    /**
     * Spawns simulated grid topology.
     */
    fun createGridTopology(width: Int, height: Int) {
        val repo = NeighborStateRepository.getInstance()
        repo.clear()

        for (x in 0 until width) {
            for (y in 0 until height) {
                val nodeId = "virtual_grid_${x}_$y"
                val state = NeighborNodeState(
                    nodeId = nodeId,
                    rssi = -65,
                    packetLossRate = 0.05,
                    queueLength = 0,
                    ackSuccessRatio = 1.0,
                    batteryLevel = 95,
                    stabilityIndex = 0.95,
                    lastSeenTimestamp = System.currentTimeMillis()
                )
                repo.updateNeighbor(nodeId, state)
            }
        }
    }
}
