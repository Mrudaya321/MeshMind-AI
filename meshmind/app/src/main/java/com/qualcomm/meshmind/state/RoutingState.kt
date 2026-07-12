package com.qualcomm.meshmind.state

import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks compiled routing paths. Read by transmission workers, updated by AI sweeps.
 */
class RoutingState private constructor() {

    data class RouteRecord(
        val destinationNodeId: String,
        val nextHopNodeId: String,
        val hopCount: Int,
        val cost: Double,
        val sequenceNumber: Int,
        val lastUpdated: Long = System.currentTimeMillis()
    )

    private val routingTable = ConcurrentHashMap<String, RouteRecord>()

    companion object {
        @Volatile
        private var instance: RoutingState? = null

        fun getInstance(): RoutingState {
            return instance ?: synchronized(this) {
                instance ?: RoutingState().also { instance = it }
            }
        }
    }

    fun putRoute(destination: String, route: RouteRecord) {
        routingTable[destination] = route
    }

    fun getRoute(destination: String): RouteRecord? {
        return routingTable[destination]
    }

    fun getAllRoutes(): Collection<RouteRecord> {
        return routingTable.values
    }

    fun removeRoute(destination: String) {
        routingTable.remove(destination)
    }

    fun clear() {
        routingTable.clear()
    }
}
