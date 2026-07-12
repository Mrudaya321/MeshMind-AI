package com.qualcomm.meshmind.network.routing


/**
 * Interface representing the routing protocol engine in Kotlin.
 */
interface RoutingEngine {

    /**
     * Re-calculates paths using deterministic direct-neighbor reachability.
     */
    fun updateRoutingTable()

    /**
     * Resolves the next-hop node ID for forwarding a packet to the destination.
     */
    fun findNextHop(destinationNodeId: String): String?

    /**
     * Resolves the deterministic hop count to the destination.
     * Returns Int.MAX_VALUE if unreachable.
     */
    fun getHopCount(destinationNodeId: String): Int

    /**
     * Registers a verified direct physical neighbor (e.g. via an active TransportSession).
     */
    fun registerDirectNeighbor(nodeId: String)

    /**
     * Removes a direct physical neighbor route when its session closes.
     */
    fun removeDirectNeighbor(nodeId: String)
}
