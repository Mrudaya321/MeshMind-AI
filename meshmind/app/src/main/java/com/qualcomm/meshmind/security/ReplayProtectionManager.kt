package com.qualcomm.meshmind.security

import java.util.concurrent.ConcurrentHashMap

/**
 * Replay protection manager tracking recently received message IDs.
 */
class ReplayProtectionManager {

    private val seenMessageIds = ConcurrentHashMap<String, Long>()
    private val retentionWindowMs = 600000L // 10 minutes sliding window

    companion object {
        @Volatile
        private var instance: ReplayProtectionManager? = null

        fun getInstance(): ReplayProtectionManager {
            return instance ?: synchronized(this) {
                instance ?: ReplayProtectionManager().also { instance = it }
            }
        }
    }

    /**
     * Checks if the packet is replayed. If not, caches the identifier.
     */
    fun isReplayedOrDuplicate(packetId: String): Boolean {
        val now = System.currentTimeMillis()
        
        // Periodic pruning of old IDs to prevent memory growth
        pruneExpiredRecords(now)

        val previousTime = seenMessageIds.putIfAbsent(packetId, now)
        return previousTime != null
    }

    private fun pruneExpiredRecords(currentTime: Long) {
        val iterator = seenMessageIds.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (currentTime - entry.value > retentionWindowMs) {
                iterator.remove()
            }
        }
    }

    fun clear() {
        seenMessageIds.clear()
    }
}
