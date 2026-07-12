package com.qualcomm.meshmind.digitaltwin.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tracks chronological trajectories and relay actions experienced by packets.
 */
class PacketTraceEngine {

    data class ForwardingHop(
        val nodeId: String,
        val status: String,
        val timestamp: Long
    )

    data class PacketTrajectory(
        val packetId: String,
        val originNodeId: String,
        val destinationNodeId: String,
        val hopsList: List<ForwardingHop>
    )

    private val packetHops = ConcurrentHashMap<String, CopyOnWriteArrayList<ForwardingHop>>()
    private val trajectories = ConcurrentHashMap<String, PacketTrajectory>()

    companion object {
        @Volatile
        private var instance: PacketTraceEngine? = null

        fun getInstance(): PacketTraceEngine {
            return instance ?: synchronized(this) {
                instance ?: PacketTraceEngine().also { instance = it }
            }
        }
    }

    /**
     * Appends a forwarding step record for a packet.
     */
    fun recordHop(packetId: String, origin: String, destination: String, node: String, status: String) {
        val list = packetHops.getOrPut(packetId) { CopyOnWriteArrayList() }
        list.add(ForwardingHop(node, status, System.currentTimeMillis()))

        val trajectory = PacketTrajectory(
            packetId = packetId,
            originNodeId = origin,
            destinationNodeId = destination,
            hopsList = list.toList()
        )
        trajectories[packetId] = trajectory
    }

    fun getTrajectory(packetId: String): PacketTrajectory? = trajectories[packetId]

    fun clear() {
        packetHops.clear()
        trajectories.clear()
    }
}
