package com.qualcomm.meshmind.packet.validation

import com.qualcomm.meshmind.packet.checksum.ChecksumCalculator
import com.qualcomm.meshmind.packet.models.MeshFrame

/**
 * Validates protocol rules and integrity checks on MeshFrames in Kotlin.
 */
object FrameValidator {

    private const val MAX_ALLOWABLE_HOPS = 16

    /**
     * Checks if a frame violates routing boundaries or packet integrity thresholds.
     */
    fun validateFrame(frame: MeshFrame?): Boolean {
        if (frame == null) return false
        
        // Protocol configuration boundaries check
        if (frame.protocolVersion <= 0) return false
        
        // TTL limits check
        if (frame.ttl <= 0) return false

        // Maximum hops check
        if (frame.hopCount > MAX_ALLOWABLE_HOPS) return false

        // Nodes validity check
        if (frame.packetId.isBlank() || frame.sourceNodeId.isBlank() || frame.destinationNodeId.isBlank()) return false

        // Integrity checksum check on payload
        val computed = ChecksumCalculator.calculateCrc32(frame.payload)
        return computed == frame.checksum
    }
}
