package com.qualcomm.meshmind.arduino

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe memory cache repository tracking active Arduino beacons.
 */
class InfrastructureRepository {

    private val nodes = ConcurrentHashMap<String, InfrastructureNode>()
    
    private val _nodesFlow = MutableStateFlow<List<InfrastructureNode>>(emptyList())
    val nodesFlow: StateFlow<List<InfrastructureNode>> = _nodesFlow.asStateFlow()

    companion object {
        @Volatile
        private var instance: InfrastructureRepository? = null

        fun getInstance(): InfrastructureRepository {
            return instance ?: synchronized(this) {
                instance ?: InfrastructureRepository().also { instance = it }
            }
        }
    }

    fun updateNode(node: InfrastructureNode) {
        nodes[node.nodeId] = node
        _nodesFlow.value = nodes.values.toList()
    }

    fun getNode(nodeId: String): InfrastructureNode? = nodes[nodeId]

    fun getAllNodes(): List<InfrastructureNode> = nodes.values.toList()

    /**
     * Obsolete nodes that have not transmitted a heartbeat within threshold.
     */
    fun sweepStaleNodes(expiryThreshold: Long) {
        val iterator = nodes.entries.iterator()
        var changed = false
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.lastSeenTimestamp < expiryThreshold) {
                iterator.remove()
                changed = true
            }
        }
        if (changed) {
            _nodesFlow.value = nodes.values.toList()
        }
    }

    fun clear() {
        nodes.clear()
        _nodesFlow.value = emptyList()
    }
}
