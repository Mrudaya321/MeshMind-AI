package com.qualcomm.meshmind.network.routing


import com.qualcomm.meshmind.core.dependency.ServiceLocator
import com.qualcomm.meshmind.core.runtime.BaseSubsystem
import com.qualcomm.meshmind.core.scheduling.TaskScheduler
import com.qualcomm.meshmind.diagnostics.models.SubsystemHealth
import com.qualcomm.meshmind.identity.DeviceIdentityManager
import com.qualcomm.meshmind.logging.MeshLogger
import com.qualcomm.meshmind.packet.builder.PacketBuilder
import com.qualcomm.meshmind.packet.models.PacketType
import com.qualcomm.meshmind.repository.RoutingStateRepository
import com.qualcomm.meshmind.state.NeighborStateRepository
import com.qualcomm.meshmind.state.RoutingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Distributed Destination-Sequenced Distance Vector (DSDV) Routing Engine in Kotlin.
 * Maintains path sequence numbers, split horizon, and route sweeps.
 */
class MmpRoutingEngineImpl : BaseSubsystem, RoutingEngine {

    private val routingScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val neighborRepo = NeighborStateRepository.getInstance()
    private val routingState = RoutingState.getInstance()

    private var isOperational = false
    private var errorCount: Long = 0
    
    private var localSequenceNumber = 0
    private var localNodeId: String = "unassigned_node"

    // Verified physical direct routes, installed by TransportSession
    private val verifiedDirectNeighbors = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    companion object {
        private const val TAG = "MmpRoutingEngine"
        private const val MAX_HOPS_THRESHOLD = 15
        private const val STABILITY_LOW_THRESHOLD = 0.3
        private const val INFINITE_COST = 99.0
        private const val ROUTE_EXPIRY_MS = 30000L // 30 seconds route lifetime
        
        private const val TASK_DV_ADVERTISE = "mmp_dv_advertise"
        private const val TASK_DV_SWEEP = "mmp_dv_sweep"
    }

    override val subsystemId: String = "mmp_routing_engine"
    override val initPriority: Int = 45

    override suspend fun initialize() {
        try {
            // Resolve Node Identity
            val identityMgr = ServiceLocator.get(DeviceIdentityManager::class.java)
            localNodeId = identityMgr.resolveNodeId()

            isOperational = true
            MeshLogger.i(TAG, "MMP DSDV Routing Engine online for node: $localNodeId")

            // 1. Schedule periodic DV updates (every 15 seconds)
            TaskScheduler.getInstance().schedulePeriodic(TASK_DV_ADVERTISE, 15000, 0.1) {
                broadcastRoutingTable()
            }

            // 2. Schedule periodic table sweeps (every 8 seconds)
            TaskScheduler.getInstance().schedulePeriodic(TASK_DV_SWEEP, 8000, 0.05) {
                sweepStaleRoutes()
            }
        } catch (e: Exception) {
            errorCount++
            isOperational = false
            MeshLogger.e(TAG, "Failed to start MMP DSDV Routing Engine", e)
            throw e
        }
    }

    override fun shutdown() {
        TaskScheduler.getInstance().cancel(TASK_DV_ADVERTISE)
        TaskScheduler.getInstance().cancel(TASK_DV_SWEEP)
        isOperational = false
        MeshLogger.i(TAG, "MMP DSDV Routing Engine shutdown.")
    }

    override fun getHealth(): SubsystemHealth {
        return SubsystemHealth(
            subsystemId,
            isOperational,
            System.currentTimeMillis(),
            errorCount,
            if (isOperational) "MMP Routing Engine operational" else "Offline"
        )
    }

    override fun updateRoutingTable() {
        if (!isOperational) return

        try {
            val routingStateRepo = ServiceLocator.get(RoutingStateRepository::class.java)

            for (nodeId in verifiedDirectNeighbors.keys) {
                val existingRoute = routingState.getRoute(nodeId)
                
                // If direct route is missing or older, update it
                val acceptUpdate = existingRoute == null || (existingRoute.hopCount > 1) || (existingRoute.cost == INFINITE_COST)
                if (acceptUpdate) {
                    val updatedRoute = RoutingState.RouteRecord(
                        destinationNodeId = nodeId,
                        nextHopNodeId = nodeId,
                        hopCount = 1,
                        cost = 1.0,
                        sequenceNumber = (existingRoute?.sequenceNumber ?: 0) + 2,
                        lastUpdated = System.currentTimeMillis()
                    )
                    
                    routingState.putRoute(nodeId, updatedRoute)

                    // Write updates to Room db asynchronously
                    routingScope.launch {
                        try {
                            routingStateRepo.saveRoute(
                                destination = nodeId,
                                nextHop = nodeId,
                                hopCount = 1,
                                sequenceNumber = updatedRoute.sequenceNumber,
                                isValid = true
                            )
                        } catch (ignored: Exception) {}
                    }
                }
            }
        } catch (e: Exception) {
            errorCount++
            MeshLogger.e(TAG, "Failed updating DSDV routing table costs", e)
        }
    }

    override fun findNextHop(destinationNodeId: String): String? {
        if (!isOperational) return null
        
        // 1. Direct neighbor check (deterministic based on verified transport session)
        if (verifiedDirectNeighbors.containsKey(destinationNodeId)) {
            MeshLogger.d(TAG, "Routing decision [DIRECT_NEIGHBOR] for dest $destinationNodeId -> next hop: $destinationNodeId")
            return destinationNodeId
        }

        // 2. Resolve via DSDV Routing Table
        val route = routingState.getRoute(destinationNodeId)
        if (route != null && route.cost < INFINITE_COST && route.hopCount <= MAX_HOPS_THRESHOLD) {
            // Verify next hop neighbor is a verified physical connection
            if (verifiedDirectNeighbors.containsKey(route.nextHopNodeId)) {
                MeshLogger.d(TAG, "Routing decision [DSDV_ROUTE] for dest $destinationNodeId (seq: ${route.sequenceNumber}, hops: ${route.hopCount}) -> next hop: ${route.nextHopNodeId}")
                return route.nextHopNodeId
            }
        }

        MeshLogger.d(TAG, "Routing decision [NO_ROUTE] for dest $destinationNodeId")
        return null
    }

    override fun getHopCount(destinationNodeId: String): Int {
        if (!isOperational) return Int.MAX_VALUE
        return try {
            val route = routingState.getRoute(destinationNodeId)
            if (route != null && route.cost < INFINITE_COST && route.hopCount <= MAX_HOPS_THRESHOLD) {
                route.hopCount
            } else {
                Int.MAX_VALUE
            }
        } catch (e: Exception) {
            Int.MAX_VALUE
        }
    }

    override fun registerDirectNeighbor(nodeId: String) {
        MeshLogger.i(TAG, "DIRECT_ROUTE_REGISTERED: Bootstrapping direct physical route to $nodeId")
        verifiedDirectNeighbors[nodeId] = true
        // Force an immediate DSDV route update so the local node can route instantly
        updateRoutingTable()
        // Advertise immediately so others learn about this link
        broadcastRoutingTable()
    }

    override fun removeDirectNeighbor(nodeId: String) {
        val removed = verifiedDirectNeighbors.remove(nodeId) != null
        if (removed) {
            MeshLogger.w(TAG, "DIRECT_ROUTE_REMOVED_OR_INVALIDATED: Invalidating direct physical route to $nodeId")
            // Expire the route by forcing a sweep on the removed node
            val existingRoute = routingState.getRoute(nodeId)
            if (existingRoute != null && existingRoute.hopCount == 1 && existingRoute.cost < INFINITE_COST) {
                val brokenRoute = existingRoute.copy(
                    cost = INFINITE_COST,
                    sequenceNumber = existingRoute.sequenceNumber + 1,
                    lastUpdated = System.currentTimeMillis()
                )
                routingState.putRoute(nodeId, brokenRoute)
                // Write to DB
                routingScope.launch {
                    try {
                        val routingStateRepo = ServiceLocator.get(RoutingStateRepository::class.java)
                        routingStateRepo.saveRoute(
                            destination = nodeId,
                            nextHop = nodeId,
                            hopCount = 1,
                            sequenceNumber = brokenRoute.sequenceNumber,
                            isValid = false
                        )
                    } catch (ignored: Exception) {}
                }
            }
            // Trigger an advertisement to notify network of the broken link
            broadcastRoutingTable()
        }
    }

    /**
     * Parses incoming serialized distance-vector advertisements and updates table.
     */
    fun processRoutingAdvertisement(senderId: String, payload: ByteArray) {
        if (!isOperational) return
        
        try {
            val content = String(payload, Charsets.UTF_8)
            val lines = content.split("\n")
            val routingStateRepo = ServiceLocator.get(RoutingStateRepository::class.java)

            val senderState = neighborRepo.getNeighbor(senderId) ?: return

            for (line in lines) {
                if (line.isBlank()) continue
                val parts = line.split(":")
                if (parts.size < 5) continue

                val dest = parts[0]
                val advertisedHopCount = parts[2].toInt()
                val advertisedSeqNum = parts[4].toInt()

                // Split Horizon: Don't accept route back if nextHop points to ourselves
                val nextHop = parts[1]
                if (nextHop == localNodeId) continue

                // The advertised route hop count does NOT include the sender's local hop to us.
                // It is the sender's hop count to the destination. We must add 1.
                val candidateHops = 1 + advertisedHopCount
                val candidateCost = candidateHops.toDouble()

                val currentRoute = routingState.getRoute(dest)

                // DSDV Update Rules (Deterministic):
                // 1. Candidate sequence number is greater (newer) -> accept
                // 2. Sequence numbers are identical AND candidate hop count is lower -> accept
                val acceptUpdate = if (currentRoute == null) {
                    true
                } else if (advertisedSeqNum > currentRoute.sequenceNumber) {
                    MeshLogger.d(TAG, "Accepting NEWEST_SEQUENCE_ROUTE for $dest (new seq: $advertisedSeqNum, old seq: ${currentRoute.sequenceNumber})")
                    true
                } else {
                    val hopAccept = advertisedSeqNum == currentRoute.sequenceNumber && candidateHops < currentRoute.hopCount
                    if (hopAccept) {
                        MeshLogger.d(TAG, "Accepting LOWEST_HOP_ROUTE for $dest (new hops: $candidateHops, old hops: ${currentRoute.hopCount})")
                    }
                    hopAccept
                }

                if (acceptUpdate && candidateHops <= MAX_HOPS_THRESHOLD) {
                    val newRoute = RoutingState.RouteRecord(
                        destinationNodeId = dest,
                        nextHopNodeId = senderId,
                        hopCount = candidateHops,
                        cost = candidateCost,
                        sequenceNumber = advertisedSeqNum,
                        lastUpdated = System.currentTimeMillis()
                    )
                    routingState.putRoute(dest, newRoute)

                    // Write updates to SQLite DB
                    routingScope.launch {
                        try {
                            routingStateRepo.saveRoute(
                                destination = dest,
                                nextHop = senderId,
                                hopCount = candidateHops,
                                sequenceNumber = advertisedSeqNum,
                                isValid = true
                            )
                        } catch (ignored: Exception) {}
                    }
                }
            }
        } catch (e: Exception) {
            errorCount++
            MeshLogger.e(TAG, "Error processing incoming DV advertisement from $senderId", e)
        }
    }

    /**
     * Broadcasts summarized routing tables.
     */
    private fun broadcastRoutingTable() {
        if (!isOperational) return

        try {
            // Increment local node sequence number
            localSequenceNumber += 2

            val routes = routingState.getAllRoutes()
            val sb = java.lang.StringBuilder()

            // Include local node entry
            sb.append("$localNodeId:$localNodeId:0:0.0:$localSequenceNumber\n")

            for (route in routes) {
                // Don't advertise routes that have expired or are infinite cost
                if (route.cost < INFINITE_COST) {
                    sb.append("${route.destinationNodeId}:${route.nextHopNodeId}:${route.hopCount}:${route.cost}:${route.sequenceNumber}\n")
                }
            }

            val payloadBytes = sb.toString().toByteArray(Charsets.UTF_8)
            val crc = com.qualcomm.meshmind.packet.checksum.ChecksumCalculator.calculateCrc32(payloadBytes)

            // Compile distance vector frame
            val frame = PacketBuilder()
                .setSourceNodeId(localNodeId)
                .setDestinationNodeId("BROADCAST")
                .setPacketType(PacketType.DISTANCE_VECTOR)
                .setPriority(1) // High priority control updates
                .setPayload(payloadBytes)
                .setChecksum(crc)
                .build()

            // Enqueue in Scheduler
            com.qualcomm.meshmind.communication.PacketScheduler.getInstance().enqueue(frame)
            MeshLogger.d(TAG, "Broadcasted DSDV routing table updates payload: ${payloadBytes.size} bytes.")
        } catch (e: Exception) {
            MeshLogger.e(TAG, "Failed compiling DSDV routing table updates broadcast", e)
        }
    }

    /**
     * Scans and marks stale/expired routes unreachable (Assigning cost = 99.0 and incrementing sequence to odd).
     */
    private fun sweepStaleRoutes() {
        if (!isOperational) return

        val now = System.currentTimeMillis()
        val routes = routingState.getAllRoutes()
        val routingStateRepo = ServiceLocator.get(RoutingStateRepository::class.java)

        for (route in routes) {
            // Don't expire our own local route or direct neighbors that are still active
            if (route.destinationNodeId == localNodeId) continue

            val elapsed = now - route.lastUpdated
            if (elapsed > ROUTE_EXPIRY_MS && route.cost < INFINITE_COST) {
                // Invalidate route: increment sequence number to odd and make metric infinite
                val brokenRoute = route.copy(
                    cost = INFINITE_COST,
                    sequenceNumber = route.sequenceNumber + 1,
                    lastUpdated = now
                )
                routingState.putRoute(route.destinationNodeId, brokenRoute)
                
                MeshLogger.w(TAG, "Route to destination ${route.destinationNodeId} expired. Propagating infinite cost.")

                routingScope.launch {
                    try {
                        routingStateRepo.saveRoute(
                            destination = route.destinationNodeId,
                            nextHop = route.nextHopNodeId,
                            hopCount = route.hopCount,
                            sequenceNumber = brokenRoute.sequenceNumber,
                            isValid = false
                        )
                    } catch (ignored: Exception) {}
                }
            }
        }
    }
}
