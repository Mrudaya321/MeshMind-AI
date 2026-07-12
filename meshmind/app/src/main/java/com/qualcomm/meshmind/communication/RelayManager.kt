package com.qualcomm.meshmind.communication

import com.qualcomm.meshmind.core.dependency.ServiceLocator
import com.qualcomm.meshmind.core.runtime.BaseSubsystem
import com.qualcomm.meshmind.diagnostics.models.SubsystemHealth
import com.qualcomm.meshmind.identity.DeviceIdentityManager
import com.qualcomm.meshmind.logging.MeshLogger
import com.qualcomm.meshmind.network.routing.RoutingEngine
import com.qualcomm.meshmind.network.transport.TransportManager
import com.qualcomm.meshmind.packet.models.MeshFrame
import com.qualcomm.meshmind.repository.MessageRepository
import com.qualcomm.meshmind.repository.PacketHistoryRepository
import com.qualcomm.meshmind.state.NeighborStateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Collections

/**
 * Orchestrates multi-hop packet forwarding and classify decisions.
 * Remains completely isolated from DSDV route calculations and NPU predictions.
 */
class RelayManager : BaseSubsystem {

    data class ReplayKey(
        val packetId: String,
        val sourceNodeId: String,
        val packetType: com.qualcomm.meshmind.packet.models.PacketType
    )

    private val relayScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val neighborRepo = NeighborStateRepository.getInstance()
    private val dtnBuffer = DtnBufferManager.getInstance()

    private var isOperational = false
    private var errorCount: Long = 0
    private var localNodeId: String = "unassigned_node"

    // Thread-safe bounded LRU cache tracking processed packet identities
    private val processedPacketCache = Collections.synchronizedMap(
        object : LinkedHashMap<ReplayKey, Boolean>(100, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<ReplayKey, Boolean>?): Boolean {
                return size > 500
            }
        }
    )

    companion object {
        private const val TAG = "RelayManager"
        
        @Volatile
        private var instance: RelayManager? = null

        fun getInstance(): RelayManager {
            return instance ?: synchronized(this) {
                instance ?: RelayManager().also { instance = it }
            }
        }
    }

    override val subsystemId: String = "relay_manager"
    override val initPriority: Int = 47 // Run after routing engine (45)

    override suspend fun initialize() {
        try {
            val identityMgr = ServiceLocator.get(DeviceIdentityManager::class.java)
            localNodeId = identityMgr.resolveNodeId()
            isOperational = true
            MeshLogger.i(TAG, "MMP Relay Manager operational for node: $localNodeId")
        } catch (e: Exception) {
            errorCount++
            isOperational = false
            MeshLogger.e(TAG, "Failed to start MMP Relay Manager", e)
            throw e
        }
    }

    override fun shutdown() {
        processedPacketCache.clear()
        isOperational = false
        MeshLogger.i(TAG, "MMP Relay Manager shutdown.")
    }

    override fun getHealth(): SubsystemHealth {
        return SubsystemHealth(
            subsystemId,
            isOperational,
            System.currentTimeMillis(),
            errorCount,
            if (isOperational) "MMP Relay operational (Processed cache size: ${processedPacketCache.size})" else "Offline"
        )
    }

    /**
     * Entry point to process validated incoming frames from the Communication Core.
     */
    fun processFrame(frame: MeshFrame) {
        if (!isOperational) return

        relayScope.launch {
            try {
                com.qualcomm.meshmind.diagnostics.ChatDeliveryDiagnostics.logEvent(
                    traceId = frame.packetId,
                    stage = "RELAY_PROCESS_STARTED"
                )

                // 1. Classification: Destination Check
                if (frame.destinationNodeId == localNodeId) {
                    com.qualcomm.meshmind.diagnostics.ChatDeliveryDiagnostics.logEvent(
                        traceId = frame.packetId,
                        stage = "REMOTE_DESTINATION_MATCHED"
                    )
                    MeshLogger.i(TAG, "Frame ${frame.packetId} reached terminal destination. Delivering locally.")
                    // Deliver to local reliable communication manager
                    ReliableCommunicationManager.getInstance().processIncomingRawBytes(
                        com.qualcomm.meshmind.packet.serializer.PacketSerializer.serialize(frame)
                    )
                    return@launch
                }

                // 2. Duplicate Detection check (using ReplayKey to avoid ACK suppression)
                val replayKey = ReplayKey(
                    packetId = frame.packetId,
                    sourceNodeId = frame.sourceNodeId,
                    packetType = frame.packetType
                )
                if (processedPacketCache.putIfAbsent(replayKey, true) != null) {
                    MeshLogger.d(TAG, "Duplicate frame ${frame.packetId} (Type: ${frame.packetType}) suppressed at relay.")
                    logPacketEvent(frame, "DuplicateRejected")
                    return@launch
                }

                // 3. Time-To-Live decrement and validation check
                val candidateTtl = frame.ttl - 1
                if (candidateTtl <= 0) {
                    MeshLogger.w(TAG, "Frame ${frame.packetId} TTL expired at relay node. Dropping packet.")
                    logPacketEvent(frame, "TTLExpired")
                    return@launch
                }

                // 4. Handle BROADCAST explicitly without hitting RoutingEngine
                if (frame.destinationNodeId == "BROADCAST") {
                    val transportManager = ServiceLocator.get(TransportManager::class.java)
                    if (frame.packetType == com.qualcomm.meshmind.packet.models.PacketType.DISTANCE_VECTOR) {
                        MeshLogger.i(TAG, "ROUTING_ADVERTISEMENT_ONE_HOP_BROADCAST: Fan-out distance vector advertisement.")
                        val exclusions = setOfNotNull(frame.previousHopNodeId)
                        transportManager.broadcastFrame(frame, exclusions)
                        return@launch
                    } else if (frame.packetType == com.qualcomm.meshmind.packet.models.PacketType.CONTROL) {
                        // Strict validation: Only well-formed application control broadcasts are forwarded
                        val payloadV1 = com.qualcomm.meshmind.classification.models.EmergencyRoleAnnouncementCodec.decode(frame.payload)
                        if (payloadV1 == null) {
                            MeshLogger.w(TAG, "Dropping malformed or unsupported CONTROL broadcast frame ${frame.packetId}")
                            logPacketEvent(frame, "DroppedMalformedControl")
                            return@launch
                        }
                        
                        // Local ingestion for valid broadcast announcements
                        ReliableCommunicationManager.getInstance().processIncomingRawBytes(
                            com.qualcomm.meshmind.packet.serializer.PacketSerializer.serialize(frame)
                        )
                        
                        val updatedFrame = frame.copy(
                            ttl = candidateTtl,
                            hopCount = frame.hopCount + 1,
                            previousHopNodeId = localNodeId
                        )
                        val exclusions = setOfNotNull(frame.previousHopNodeId)
                        val result = transportManager.broadcastFrame(updatedFrame, exclusions)

                        if (result.status == com.qualcomm.meshmind.network.transport.BroadcastTransportResult.BroadcastStatus.ALL_SENDS_SUCCEEDED ||
                            result.status == com.qualcomm.meshmind.network.transport.BroadcastTransportResult.BroadcastStatus.PARTIAL_SUCCESS) {
                            logPacketEvent(updatedFrame, "BroadcastForwarded")
                            ServiceLocator.get(MessageRepository::class.java).updateMessageStatus(updatedFrame.packetId, "Transmitted")
                            MeshLogger.i(TAG, "Forwarding verified CONTROL broadcast ${frame.packetId}. Successes: ${result.successCount}")
                        } else if (result.status == com.qualcomm.meshmind.network.transport.BroadcastTransportResult.BroadcastStatus.NO_ELIGIBLE_SESSIONS ||
                                   result.status == com.qualcomm.meshmind.network.transport.BroadcastTransportResult.BroadcastStatus.ALL_SENDS_FAILED) {
                            MeshLogger.w(TAG, "Broadcast failed (status=${result.status}). DTN buffering...")
                            logPacketEvent(updatedFrame, "BufferedForDTN")
                            ServiceLocator.get(MessageRepository::class.java).updateMessageStatus(updatedFrame.packetId, "BufferedForDTN")
                            dtnBuffer.bufferFrame(updatedFrame)
                        }
                        return@launch
                    } else {
                        // All other packet types (DATA, EMERGENCY, ACK) must NOT trigger flood behavior
                        MeshLogger.w(TAG, "Dropping invalid broadcast target for frame type ${frame.packetType} (id: ${frame.packetId})")
                        logPacketEvent(frame, "DroppedInvalidBroadcastType")
                        return@launch
                    }
                }

                // 5. Request route next-hop from RoutingEngine (Unicast only)
                com.qualcomm.meshmind.diagnostics.ChatDeliveryDiagnostics.logEvent(
                    traceId = frame.packetId,
                    stage = "DIRECT_SESSION_CHECK_STARTED"
                )
                
                val canonicalDest = frame.destinationNodeId.lowercase(java.util.Locale.ROOT).trim()
                val directSession = com.qualcomm.meshmind.network.transport.PeerSessionRegistry.getInstance().getSession(canonicalDest)
                
                val nextHop: String?
                if (directSession != null && directSession.isActive()) {
                    nextHop = canonicalDest
                    com.qualcomm.meshmind.diagnostics.ChatDeliveryDiagnostics.logEvent(
                        traceId = frame.packetId,
                        stage = "DIRECT_SESSION_RESOLVED",
                        nextHop = canonicalDest,
                        directSessionFound = true
                    )
                    com.qualcomm.meshmind.diagnostics.ChatDeliveryDiagnostics.logEvent(
                        traceId = frame.packetId,
                        stage = "DIRECT_VERIFIED_SESSION_ROUTE_USED"
                    )
                    MeshLogger.i(TAG, "DIRECT_VERIFIED_SESSION_ROUTE_USED for ${frame.packetId} directly to $canonicalDest")
                } else {
                    com.qualcomm.meshmind.diagnostics.ChatDeliveryDiagnostics.logEvent(
                        traceId = frame.packetId,
                        stage = "ROUTE_LOOKUP_STARTED",
                        directSessionFound = false
                    )
                    val routingEngine = ServiceLocator.get(RoutingEngine::class.java)
                    nextHop = routingEngine.findNextHop(frame.destinationNodeId)
                    if (nextHop != null) {
                        com.qualcomm.meshmind.diagnostics.ChatDeliveryDiagnostics.logEvent(
                            traceId = frame.packetId,
                            stage = "ROUTE_LOOKUP_RESOLVED",
                            nextHop = nextHop,
                            routeLookupSuccess = true
                        )
                    } else {
                        com.qualcomm.meshmind.diagnostics.ChatDeliveryDiagnostics.logEvent(
                            traceId = frame.packetId,
                            stage = "ROUTE_LOOKUP_FAILED",
                            routeLookupSuccess = false
                        )
                    }
                }

                // Re-build updated frame with decremented TTL and incremented hopCount
                val updatedFrame = frame.copy(
                    ttl = candidateTtl,
                    hopCount = frame.hopCount + 1,
                    previousHopNodeId = localNodeId
                )

                if (nextHop != null) {
                    val transportManager = ServiceLocator.get(TransportManager::class.java)
                    val result = transportManager.sendFrame(nextHop, updatedFrame)
                    
                    if (result == com.qualcomm.meshmind.network.transport.TransportResult.SUCCESS) {
                        logPacketEvent(updatedFrame, "ForwardedSuccessfully")
                        ServiceLocator.get(MessageRepository::class.java).updateMessageStatus(updatedFrame.packetId, "Transmitted")
                        MeshLogger.i(TAG, "Forwarding packet ${frame.packetId} to next hop: $nextHop")
                    } else if (result == com.qualcomm.meshmind.network.transport.TransportResult.PEER_UNRESOLVED) {
                        MeshLogger.w(TAG, "PEER_UNRESOLVED for next hop: $nextHop. DTN buffering...")
                        logPacketEvent(updatedFrame, "BufferedForDTN")
                        ServiceLocator.get(MessageRepository::class.java).updateMessageStatus(updatedFrame.packetId, "BufferedForDTN")
                        dtnBuffer.bufferFrame(updatedFrame)
                    } else {
                        MeshLogger.w(TAG, "TRANSPORT_UNAVAILABLE or SEND_FAILED for next hop: $nextHop")
                        logPacketEvent(updatedFrame, "BufferedForDTN")
                        ServiceLocator.get(MessageRepository::class.java).updateMessageStatus(updatedFrame.packetId, "BufferedForDTN")
                        dtnBuffer.bufferFrame(updatedFrame)
                    }
                } else {
                    // Route missing: Delegate packet to DTN store
                    MeshLogger.w(TAG, "No valid route to ${frame.destinationNodeId} from relay node. DTN buffering...")
                    logPacketEvent(updatedFrame, "BufferedForDTN")
                    ServiceLocator.get(MessageRepository::class.java).updateMessageStatus(updatedFrame.packetId, "BufferedForDTN")
                    dtnBuffer.bufferFrame(updatedFrame)
                }
            } catch (e: Exception) {
                errorCount++
                MeshLogger.e(TAG, "Relay manager execution error processing frame: ${frame.packetId}", e)
            }
        }
    }

    private fun logPacketEvent(frame: MeshFrame, status: String) {
        relayScope.launch {
            try {
                val repo = ServiceLocator.get(PacketHistoryRepository::class.java)
                repo.logPacket(
                    packetId = frame.packetId,
                    source = frame.sourceNodeId,
                    destination = frame.destinationNodeId,
                    hopCount = frame.hopCount,
                    ttl = frame.ttl,
                    checksum = frame.checksum,
                    payloadLength = frame.payload.size,
                    isOutgoing = true,
                    status = status
                )
            } catch (ignored: Exception) {}
        }
    }
}
