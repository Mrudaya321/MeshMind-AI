package com.qualcomm.meshmind.network.transport

import com.qualcomm.meshmind.packet.models.MeshFrame

enum class TransportResult {
    SUCCESS,
    PEER_UNRESOLVED,
    TRANSPORT_UNAVAILABLE,
    SEND_FAILED
}

data class BroadcastTransportResult(
    val status: BroadcastStatus,
    val attemptedCount: Int,
    val successCount: Int,
    val failedCount: Int
) {
    enum class BroadcastStatus {
        ALL_SENDS_SUCCEEDED,
        PARTIAL_SUCCESS,
        ALL_SENDS_FAILED,
        NO_ELIGIBLE_SESSIONS,
        TRANSPORT_UNAVAILABLE
    }
}

/**
 * Interface representing the physical socket and Wi-Fi Direct connection layer in Kotlin.
 */
interface TransportManager {

    /**
     * Attempts to transmit a MeshFrame to the direct next-hop neighbor over verified sessions.
     */
    suspend fun sendFrame(nextHopNodeId: String, frame: MeshFrame): TransportResult

    /**
     * Attempts a one-hop fan-out of the frame to all adjacent active verified sessions.
     */
    suspend fun broadcastFrame(frame: MeshFrame, excludedNodeIds: Set<String> = emptySet()): BroadcastTransportResult

    /**
     * Binds internal server socket receivers.
     */
    fun startListening()

    /**
     * Unbinds and releases sockets.
     */
    fun stopListening()
}
