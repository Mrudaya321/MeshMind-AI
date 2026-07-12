package com.qualcomm.meshmind.validation

import com.qualcomm.meshmind.models.NeighborNodeState
import com.qualcomm.meshmind.state.NeighborStateRepository

/**
 * Controlled Fault Injection Engine for MeshMind validation loops in Kotlin.
 */
class FaultInjector {

    companion object {
        @Volatile
        private var instance: FaultInjector? = null

        fun getInstance(): FaultInjector {
            return instance ?: synchronized(this) {
                instance ?: FaultInjector().also { instance = it }
            }
        }
    }

    /**
     * Forces neighbor connection drop (sets stabilityIndex to 0.0).
     */
    fun injectNeighborLoss(nodeId: String) {
        val repo = NeighborStateRepository.getInstance()
        val existing = repo.getNeighbor(nodeId)
        if (existing != null) {
            val brokenState = existing.copy(
                stabilityIndex = 0.0,
                lastSeenTimestamp = System.currentTimeMillis() - 60000L // Backdate lastSeen to force expire
            )
            repo.updateNeighbor(nodeId, brokenState)
        }
    }

    /**
     * Corrupts packet checksum parameter.
     */
    fun injectPacketCorruption(data: ByteArray): ByteArray {
        if (data.isNotEmpty()) {
            val copy = data.clone()
            // Invert the last byte of raw payload data
            copy[copy.size - 1] = (copy[copy.size - 1].toInt() xor 0xFF).toByte()
            return copy
        }
        return data
    }
}
