package com.qualcomm.meshmind.digitaltwin.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * Reconstructs the decentralized mesh network topology graph.
 */
class TopologyReconstructor {

    data class NetworkLink(
        val sourceNodeId: String,
        val destinationNodeId: String,
        val lastSeenTimestamp: Long
    )

    // Map sourceNodeId -> Map of destinationNodeId -> Link
    private val graphLinks = ConcurrentHashMap<String, ConcurrentHashMap<String, NetworkLink>>()

    companion object {
        @Volatile
        private var instance: TopologyReconstructor? = null

        fun getInstance(): TopologyReconstructor {
            return instance ?: synchronized(this) {
                instance ?: TopologyReconstructor().also { instance = it }
            }
        }
    }

    /**
     * Updates link entries based on reported neighbor counts and states.
     */
    fun updateNodeLinks(sourceNodeId: String, neighborCount: Int) {
        val nodeLinks = graphLinks.getOrPut(sourceNodeId) { ConcurrentHashMap() }
        
        // Simulates edge linkages reconstruction
        for (i in 1..neighborCount) {
            val destination = "neighbor_node_$i"
            val link = NetworkLink(
                sourceNodeId = sourceNodeId,
                destinationNodeId = destination,
                lastSeenTimestamp = System.currentTimeMillis()
            )
            nodeLinks[destination] = link
        }
    }

    /**
     * Returns list of reconstructed graph edges.
     */
    fun getReconstructedLinks(): List<NetworkLink> {
        val links = mutableListOf<NetworkLink>()
        for (subMap in graphLinks.values) {
            links.addAll(subMap.values)
        }
        return links
    }

    fun clear() {
        graphLinks.clear()
    }
}
