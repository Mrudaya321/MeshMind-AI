package com.qualcomm.meshmind.network.dtn

import com.qualcomm.meshmind.packet.models.MeshFrame

/**
 * Interface contract for Delay-Tolerant Networking (DTN) packet buffering in Kotlin.
 */
interface DtnBuffer {

    /**
     * Stores a packet in the database/storage buffer until a suitable path is found.
     */
    fun bufferPacket(frame: MeshFrame)

    /**
     * Extracts buffered frames destined for the resolved node ID.
     */
    fun retrievePacketsForDestination(destinationNodeId: String): List<MeshFrame>

    /**
     * Trims expired frames to conserve local disk space.
     */
    fun pruneExpiredPackets()
}
