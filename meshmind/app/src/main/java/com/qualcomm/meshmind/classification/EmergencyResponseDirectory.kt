package com.qualcomm.meshmind.classification

import com.qualcomm.meshmind.classification.models.EmergencyResponseRole
import java.util.concurrent.ConcurrentHashMap

data class DirectoryEntry(
    val canonicalNodeId: String,
    val role: EmergencyResponseRole,
    val generation: Long,
    val lastSeenTimestamp: Long
)

object EmergencyResponseDirectory {
    private val directory = ConcurrentHashMap<String, DirectoryEntry>()
    private const val STALE_THRESHOLD_MS = 600000L // 10 minutes

    /**
     * Update the directory with a received announcement.
     * Only updates if the generation is strictly newer.
     */
    fun updateRole(nodeId: String, role: EmergencyResponseRole, generation: Long) {
        val existing = directory[nodeId]
        if (existing == null || generation > existing.generation) {
            directory[nodeId] = DirectoryEntry(
                canonicalNodeId = nodeId,
                role = role,
                generation = generation,
                lastSeenTimestamp = System.currentTimeMillis()
            )
        } else if (existing.generation == generation) {
            // Just refresh timestamp
            directory[nodeId] = existing.copy(lastSeenTimestamp = System.currentTimeMillis())
        }
    }

    /**
     * Resolves all known candidate node IDs advertising exactly the requested role.
     * Excludes stale entries. Excludes CIVILIAN unless explicitly requested.
     */
    fun getCandidatesByRole(role: EmergencyResponseRole): List<String> {
        val now = System.currentTimeMillis()
        return directory.values
            .filter { it.role == role }
            .filter { (now - it.lastSeenTimestamp) < STALE_THRESHOLD_MS }
            .map { it.canonicalNodeId }
    }

    /**
     * Exposes the full active directory for diagnostic purposes.
     */
    fun getActiveDirectory(): List<DirectoryEntry> {
        val now = System.currentTimeMillis()
        return directory.values.filter { (now - it.lastSeenTimestamp) < STALE_THRESHOLD_MS }
    }
    
    fun clear() {
        directory.clear()
    }
}
