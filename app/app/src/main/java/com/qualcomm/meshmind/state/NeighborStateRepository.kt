package com.qualcomm.meshmind.state

import com.qualcomm.meshmind.core.dependency.ServiceLocator
import com.qualcomm.meshmind.database.DatabaseManager
import com.qualcomm.meshmind.database.NeighborStateDao
import com.qualcomm.meshmind.database.NeighborStateEntity
import com.qualcomm.meshmind.models.NeighborNodeState
import com.qualcomm.meshmind.repository.BaseRepository
import com.qualcomm.meshmind.repository.ConversationRepository
import com.qualcomm.meshmind.logging.MeshLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Authoritative source of truth for all neighboring devices.
 * Integrates an in-memory hot cache with persistent Room database storage.
 */
class NeighborStateRepository private constructor() : BaseRepository() {

    private val neighbors = ConcurrentHashMap<String, NeighborNodeState>()
    private val repositoryScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _neighborListFlow = MutableStateFlow<List<NeighborNodeState>>(emptyList())
    val neighborListFlow: StateFlow<List<NeighborNodeState>> = _neighborListFlow.asStateFlow()

    private val neighborDao: NeighborStateDao by lazy {
        ServiceLocator.get(DatabaseManager::class.java).getDatabase().neighborStateDao()
    }

    companion object {
        @Volatile
        private var instance: NeighborStateRepository? = null

        fun getInstance(): NeighborStateRepository {
            return instance ?: synchronized(this) {
                instance ?: NeighborStateRepository().also { instance = it }
            }
        }
    }

    /**
     * Rebuilds the in-memory cache from the persistent database during startup.
     */
    suspend fun preloadFromDatabase() {
        executeIO {
            val entities = neighborDao.getAllNeighbors()
            for (entity in entities) {
                neighbors[entity.nodeId] = entity.toDomainModel()
            }
            updateFlow()
        }
    }

    /**
     * Saves a neighbor's telemetry and prediction updates to memory and database.
     */
    fun updateNeighbor(nodeId: String, state: NeighborNodeState) {
        neighbors[nodeId] = state
        updateFlow()
        
        // Trigger immediate routing updates based on neighbor state changes
        try {
            val routingEngine = ServiceLocator.get(com.qualcomm.meshmind.network.routing.RoutingEngine::class.java)
            routingEngine.updateRoutingTable()
        } catch (ignored: Exception) {}

        // Asynchronously persist state to SQL and auto-create chat conversations
        repositoryScope.launch {
            try {
                executeIO {
                    neighborDao.insertNeighbor(state.toEntity())
                }
                val conversationRepo = ServiceLocator.get(ConversationRepository::class.java)
                if (conversationRepo.getAllConversations().none { it.conversationId == nodeId }) {
                    conversationRepo.createConversation(nodeId, "Secure Chat with $nodeId")
                    MeshLogger.i("NeighborStateRepository", "Created auto-conversation for neighbor: $nodeId")
                }
            } catch (e: Exception) {
                // Failure is logged by BaseRepository.safeCall/executeIO
            }
        }
    }

    fun getNeighbor(nodeId: String): NeighborNodeState? {
        return neighbors[nodeId]
    }

    fun getAllNeighbors(): List<NeighborNodeState> {
        return neighbors.values.toList()
    }

    /**
     * Returns strictly active neighbors updated within the cutoff window.
     */
    fun getActiveNeighbors(cutoffMs: Long): List<NeighborNodeState> {
        val now = System.currentTimeMillis()
        val activeList = mutableListOf<NeighborNodeState>()
        
        for (neighbor in neighbors.values) {
            val ageMs = now - neighbor.lastSeenTimestamp
            if (ageMs <= cutoffMs) {
                MeshLogger.d("NeighborStateRepository", "BLE_NEIGHBOR_ACTIVE: NodeId=${neighbor.nodeId}, age=${ageMs}ms")
                activeList.add(neighbor)
            } else {
                MeshLogger.d("NeighborStateRepository", "BLE_NEIGHBOR_STALE: NodeId=${neighbor.nodeId}, age=${ageMs}ms, threshold=${cutoffMs}ms")
            }
        }
        
        return activeList
    }

    /**
     * Removes a neighbor from the active cache and deletes it from Room.
     */
    fun removeNeighbor(nodeId: String) {
        neighbors.remove(nodeId)
        updateFlow()
        
        repositoryScope.launch {
            try {
                executeIO {
                    neighborDao.deleteNeighbor(nodeId)
                }
            } catch (e: Exception) {
                // Handled in BaseRepository
            }
        }
    }

    /**
     * Erases all cached neighbors and flushes SQL table.
     */
    fun clear() {
        neighbors.clear()
        updateFlow()

        repositoryScope.launch {
            try {
                executeIO {
                    // Truncate table by deleting stale with infinite threshold
                    neighborDao.deleteStaleNeighbors(Long.MAX_VALUE)
                }
            } catch (e: Exception) {
                // Handled in BaseRepository
            }
        }
    }

    /**
     * Expires and purges neighbor states that have not been heard from recently.
     */
    suspend fun expireStaleNeighbors(cutoffTimestamp: Long) {
        executeIO {
            // Delete from Room
            neighborDao.deleteStaleNeighbors(cutoffTimestamp)
            
            // Clean memory cache
            val iterator = neighbors.entries.iterator()
            var modified = false
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.value.lastSeenTimestamp < cutoffTimestamp) {
                    val ageMs = System.currentTimeMillis() - entry.value.lastSeenTimestamp
                    MeshLogger.w("NeighborStateRepository", "NEIGHBOR_EXPIRED: NodeId=${entry.key}, age=${ageMs}ms, threshold=${System.currentTimeMillis() - cutoffTimestamp}ms")
                    iterator.remove()
                    modified = true
                }
            }
            if (modified) {
                updateFlow()
            }
        }
    }

    private fun updateFlow() {
        _neighborListFlow.value = neighbors.values.toList()
    }

    // Extension Mappings
    private fun NeighborStateEntity.toDomainModel() = NeighborNodeState(
        nodeId = nodeId,
        rssi = rssi,
        packetLossRate = packetLossRate,
        queueLength = queueLength,
        ackSuccessRatio = ackSuccessRatio,
        batteryLevel = batteryLevel,
        stabilityIndex = stabilityIndex,
        lastSeenTimestamp = lastSeenTimestamp
    )

    private fun NeighborNodeState.toEntity() = NeighborStateEntity(
        nodeId = nodeId,
        rssi = rssi,
        packetLossRate = packetLossRate,
        queueLength = queueLength,
        ackSuccessRatio = ackSuccessRatio,
        batteryLevel = batteryLevel,
        stabilityIndex = stabilityIndex,
        lastSeenTimestamp = lastSeenTimestamp
    )
}
