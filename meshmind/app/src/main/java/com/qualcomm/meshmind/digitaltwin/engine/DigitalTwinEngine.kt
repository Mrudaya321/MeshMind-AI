package com.qualcomm.meshmind.digitaltwin.engine

import com.qualcomm.meshmind.logging.MeshLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Maintains virtual node representation models and aggregates network-wide state reports in Kotlin.
 */
class DigitalTwinEngine {

    data class DigitalTwinNode(
        val nodeId: String,
        val uptimeMs: Long,
        val lastSeenTimestamp: Long,
        val neighborsCount: Int,
        val routesCount: Int,
        val healthSummary: String
    )

    data class TimelineEvent(
        val name: String,
        val description: String,
        val timestamp: Long,
        val originNodeId: String
    )

    private val nodes = ConcurrentHashMap<String, DigitalTwinNode>()
    private val eventsTimeline = CopyOnWriteArrayList<TimelineEvent>()

    companion object {
        private const val TAG = "DigitalTwinEngine"
        
        @Volatile
        private var instance: DigitalTwinEngine? = null

        fun getInstance(): DigitalTwinEngine {
            return instance ?: synchronized(this) {
                instance ?: DigitalTwinEngine().also { instance = it }
            }
        }
    }

    /**
     * Parses snapshot or event messages sent from TelemetryManager and updates state.
     */
    fun processTelemetryPayload(payload: String) {
        try {
            if (payload.startsWith("EVENT:")) {
                parseAndStoreEvent(payload)
            } else if (payload.startsWith("SNAPSHOT")) {
                parseAndStoreSnapshot(payload)
            }
        } catch (e: Exception) {
            MeshLogger.e(TAG, "Failed parsing telemetry payload in DigitalTwinEngine", e)
        }
    }

    private fun parseAndStoreEvent(payload: String) {
        val parts = payload.split(":")
        if (parts.size >= 5) {
            val nodeId = parts[1]
            val eventName = parts[2]
            val desc = parts[3]
            val time = parts[4].toLongOrNull() ?: System.currentTimeMillis()

            val event = TimelineEvent(eventName, desc, time, nodeId)
            eventsTimeline.add(event)
            MeshLogger.i(TAG, "Virtual Twin recorded event [$eventName] from node $nodeId.")
        }
    }

    private fun parseAndStoreSnapshot(payload: String) {
        val lines = payload.split("\n")
        var nodeId = ""
        var uptimeMs = 0L
        var timestamp = System.currentTimeMillis()
        var neighbors = 0
        var routes = 0
        var healthSummary = "Healthy"

        for (line in lines) {
            if (line.startsWith("nodeId:")) nodeId = line.removePrefix("nodeId:")
            if (line.startsWith("uptimeMs:")) uptimeMs = line.removePrefix("uptimeMs:").toLongOrNull() ?: 0L
            if (line.startsWith("timestamp:")) timestamp = line.removePrefix("timestamp:").toLongOrNull() ?: timestamp
            if (line.startsWith("neighbors:")) neighbors = line.removePrefix("neighbors:").toIntOrNull() ?: 0
            if (line.startsWith("routes:")) routes = line.removePrefix("routes:").toIntOrNull() ?: 0
        }

        if (nodeId.isNotEmpty()) {
            val node = DigitalTwinNode(
                nodeId = nodeId,
                uptimeMs = uptimeMs,
                lastSeenTimestamp = timestamp,
                neighborsCount = neighbors,
                routesCount = routes,
                healthSummary = healthSummary
            )
            nodes[nodeId] = node
            
            // Reconstruct topology relationships
            TopologyReconstructor.getInstance().updateNodeLinks(nodeId, neighbors)
        }
    }

    fun getNode(nodeId: String): DigitalTwinNode? = nodes[nodeId]

    fun getAllNodes(): List<DigitalTwinNode> = nodes.values.toList()

    fun getTimeline(): List<TimelineEvent> = eventsTimeline.toList()

    fun clear() {
        nodes.clear()
        eventsTimeline.clear()
    }
}
