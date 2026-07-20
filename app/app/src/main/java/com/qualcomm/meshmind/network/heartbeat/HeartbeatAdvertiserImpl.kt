package com.qualcomm.meshmind.network.heartbeat

import com.qualcomm.meshmind.core.dependency.ServiceLocator
import com.qualcomm.meshmind.core.runtime.BaseSubsystem
import com.qualcomm.meshmind.core.scheduling.TaskScheduler
import com.qualcomm.meshmind.diagnostics.models.SubsystemHealth
import com.qualcomm.meshmind.identity.DeviceIdentityManager
import com.qualcomm.meshmind.logging.MeshLogger
import com.qualcomm.meshmind.packet.builder.PacketBuilder
import com.qualcomm.meshmind.packet.models.PacketType
import com.qualcomm.meshmind.communication.PacketScheduler

/**
 * Executes periodic mesh-level heartbeat broadcasts to advertise local node presence to neighboring peers.
 */
class HeartbeatAdvertiserImpl : BaseSubsystem, HeartbeatAdvertiser {

    private var isRunning = false
    private var isOperational = false
    private var errorCount = 0L
    private var cachedNodeId: String = "unknown_node"

    companion object {
        private const val TAG = "HeartbeatAdvertiser"
        private const val TASK_HEARTBEAT = "mesh_heartbeat_broadcast"
    }

    override val subsystemId: String = "heartbeat_advertiser"
    override val initPriority: Int = 41 // Run after discovery manager (40) but before Arduino manager (42)

    override suspend fun initialize() {
        // Resolve node ID here (suspend context) so broadcastHeartbeat() can use it synchronously
        try {
            val identityMgr = ServiceLocator.get(DeviceIdentityManager::class.java)
            cachedNodeId = identityMgr.resolveNodeId()
        } catch (ignored: Exception) {}
        isOperational = true
        startHeartbeats()
    }

    override fun shutdown() {
        stopHeartbeats()
        isOperational = false
    }

    override fun getHealth(): SubsystemHealth {
        return SubsystemHealth(
            subsystemId,
            isOperational,
            System.currentTimeMillis(),
            errorCount,
            if (isOperational) "Heartbeat broadcasts running" else "Offline"
        )
    }

    // --- HeartbeatAdvertiser ---
    override fun startHeartbeats() {
        if (isRunning) return
        isRunning = true
        MeshLogger.i(TAG, "Starting periodic mesh heartbeats...")
        
        // Schedule periodic heartbeat broadcasts (every 10 seconds)
        TaskScheduler.getInstance().schedulePeriodic(TASK_HEARTBEAT, 10000, 0.1) {
            broadcastHeartbeat()
        }
    }

    override fun stopHeartbeats() {
        if (!isRunning) return
        isRunning = false
        TaskScheduler.getInstance().cancel(TASK_HEARTBEAT)
        MeshLogger.i(TAG, "Stopped periodic mesh heartbeats.")
    }

    private fun broadcastHeartbeat() {
        if (!isOperational) return
        try {
            val nodeId = cachedNodeId
            val payload = "HEARTBEAT:$nodeId".toByteArray(Charsets.UTF_8)
            
            // Heartbeat is sent as a high-priority control broadcast packet
            val frame = PacketBuilder()
                .setSourceNodeId(nodeId)
                .setDestinationNodeId("BROADCAST")
                .setPacketType(PacketType.ACK) // Using ACK/control format
                .setPriority(1) // High priority control level
                .setPayload(payload)
                .build()
            
            PacketScheduler.getInstance().enqueue(frame)
            MeshLogger.d(TAG, "Broadcasted local presence heartbeat packet from node: $nodeId")
        } catch (e: Exception) {
            errorCount++
            MeshLogger.e(TAG, "Failed to broadcast local node presence heartbeat", e)
        }
    }
}
