package com.qualcomm.meshmind.network.transport

import com.qualcomm.meshmind.logging.MeshLogger
import java.util.concurrent.ConcurrentHashMap
import com.qualcomm.meshmind.core.dependency.ServiceLocator

class PeerSessionRegistry private constructor() {

    private val activeSessions = ConcurrentHashMap<String, TransportSession>()

    companion object {
        private const val TAG = "PeerSessionRegistry"
        
        @Volatile
        private var instance: PeerSessionRegistry? = null

        fun getInstance(): PeerSessionRegistry {
            return instance ?: synchronized(this) {
                instance ?: PeerSessionRegistry().also { instance = it }
            }
        }
    }

    /**
     * Registers a successfully handshaken session.
     * Replaces and closes any pre-existing session for the same logical Node ID.
     */
    fun registerSession(rawNodeId: String, session: TransportSession) {
        val nodeId = com.qualcomm.meshmind.identity.DeviceIdentityManager.canonicalNodeId(rawNodeId)
        MeshLogger.i(TAG, "PEER_SESSION_REGISTER_REQUESTED: Remote NodeId: $nodeId, Session: ${session.hashCode()}")
        val oldSession = activeSessions.put(nodeId, session)
        if (oldSession != null && oldSession != session) {
            MeshLogger.w(TAG, "PEER_SESSION_REPLACED: Closing old session for NodeId: $nodeId")
            oldSession.closeSession()
        }
        MeshLogger.i(TAG, "PEER_SESSION_REGISTERED: Authoritative session mapped to $nodeId")
        try {
            val routingEngine = ServiceLocator.get(com.qualcomm.meshmind.network.routing.RoutingEngine::class.java)
            routingEngine.registerDirectNeighbor(nodeId)
            ServiceLocator.get(com.qualcomm.meshmind.network.transport.WifiDirectConnectionManager::class.java).logDiagnosticEvent("DIRECT_NEIGHBOR_REGISTERED")
        } catch (e: Exception) {
            MeshLogger.e(TAG, "Failed to register direct physical route for $nodeId", e)
        }
    }

    /**
     * Retrieves the active session for the requested Node ID.
     * Removes and returns null if the stored session is discovered to be inactive.
     */
    fun getSession(rawNodeId: String): TransportSession? {
        val nodeId = com.qualcomm.meshmind.identity.DeviceIdentityManager.canonicalNodeId(rawNodeId)
        val session = activeSessions[nodeId]
        if (session != null && !session.isActive()) {
            MeshLogger.w(TAG, "PEER_SESSION_REMOVE_IGNORED_STALE_SESSION: Removing inactive session during getSession resolution for $nodeId")
            activeSessions.remove(nodeId, session)
            return null
        }
        return session
    }

    /**
     * Removes the session mapping only if it matches the session being closed.
     */
    fun removeSession(rawNodeId: String, session: TransportSession) {
        val nodeId = com.qualcomm.meshmind.identity.DeviceIdentityManager.canonicalNodeId(rawNodeId)
        val removed = activeSessions.remove(nodeId, session)
        if (removed) {
            MeshLogger.i(TAG, "PEER_SESSION_REMOVED: Removed session mapping for $nodeId")
            MeshLogger.i(TAG, "PEER_SESSION_CLOSED: Session closed for $nodeId")
            try {
                val routingEngine = ServiceLocator.get(com.qualcomm.meshmind.network.routing.RoutingEngine::class.java)
                routingEngine.removeDirectNeighbor(nodeId)
                ServiceLocator.get(com.qualcomm.meshmind.network.transport.WifiDirectConnectionManager::class.java).logDiagnosticEvent("DIRECT_NEIGHBOR_REMOVED")
            } catch (e: Exception) {
                MeshLogger.e(TAG, "Failed to remove direct physical route for $nodeId", e)
            }
        } else {
            MeshLogger.w(TAG, "PEER_SESSION_REMOVE_IGNORED_MISMATCH: Ignored remove for $nodeId (session identity mismatch)")
        }
    }

    /**
     * Force-closes and removes all active sessions. Used for complete subsystem shutdown.
     */
    fun clearAll() {
        val iter = activeSessions.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            entry.value.closeSession()
            iter.remove()
            try {
                val routingEngine = ServiceLocator.get(com.qualcomm.meshmind.network.routing.RoutingEngine::class.java)
                routingEngine.removeDirectNeighbor(entry.key)
            } catch (e: Exception) {
                MeshLogger.e(TAG, "Failed to remove direct physical route for ${entry.key}", e)
            }
        }
        MeshLogger.i(TAG, "PEER_SESSION_REGISTRY_CLEARED: All sessions closed and removed.")
    }

    /**
     * Returns an immutable copy of the active sessions map, filtering out any inactive ones.
     */
    fun getActiveSessions(): Map<String, TransportSession> {
        // Opportunistic cleanup during enumeration
        val iter = activeSessions.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (!entry.value.isActive()) {
                iter.remove()
                try {
                    val routingEngine = ServiceLocator.get(com.qualcomm.meshmind.network.routing.RoutingEngine::class.java)
                    routingEngine.removeDirectNeighbor(entry.key)
                } catch (e: Exception) {
                    MeshLogger.e(TAG, "Failed to remove direct physical route for ${entry.key}", e)
                }
            }
        }
        return activeSessions.toMap()
    }
    
    /**
     * Fast-path active session count for probing loops.
     */
    fun getActiveSessionCount(): Int {
        var count = 0
        for ((nodeId, session) in activeSessions) {
            if (session.isActive()) count++ else {
                activeSessions.remove(nodeId, session)
                try {
                    val routingEngine = ServiceLocator.get(com.qualcomm.meshmind.network.routing.RoutingEngine::class.java)
                    routingEngine.removeDirectNeighbor(nodeId)
                } catch (e: Exception) {
                    MeshLogger.e(TAG, "Failed to remove direct physical route for $nodeId", e)
                }
            }
        }
        return count
    }
}
