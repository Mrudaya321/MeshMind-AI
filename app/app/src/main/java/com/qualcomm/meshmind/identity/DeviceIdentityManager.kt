package com.qualcomm.meshmind.identity

import android.content.Context
import com.qualcomm.meshmind.logging.MeshLogger
import com.qualcomm.meshmind.storage.SettingsDataStore
import kotlinx.coroutines.flow.first
import java.util.UUID
import java.util.Locale

/**
 * Generates and maintains a permanent Node Identifier for the MeshMind Edge Node.
 */
class DeviceIdentityManager(context: Context) {

    private val dataStore = SettingsDataStore.getInstance(context)
    private var cachedNodeId: String? = null

    companion object {
        private const val TAG = "DeviceIdentityManager"
        
        @Volatile
        private var instance: DeviceIdentityManager? = null

        fun getInstance(context: Context): DeviceIdentityManager {
            return instance ?: synchronized(this) {
                instance ?: DeviceIdentityManager(context.applicationContext).also { instance = it }
            }
        }

        /**
         * Authoritatively canonicalizes a logical Node ID for identity matching.
         */
        fun canonicalNodeId(nodeId: String): String {
            return nodeId.trim().lowercase(Locale.ROOT)
        }
    }

    /**
     * Resolves the permanent unique node ID. Generates it once if missing.
     */
    suspend fun resolveNodeId(): String {
        cachedNodeId?.let { return it }

        // Read from Jetpack DataStore blocking briefly on initialization
        val savedId = dataStore.nodeIdFlow.first()
        return if (savedId != null) {
            val canon = canonicalNodeId(savedId)
            cachedNodeId = canon
            MeshLogger.i(TAG, "Restored permanent Node ID: $canon")
            canon
        } else {
            val freshId = canonicalNodeId(UUID.randomUUID().toString())
            dataStore.saveNodeId(freshId)
            cachedNodeId = freshId
            MeshLogger.i(TAG, "Generated fresh secure Node ID: $freshId")
            freshId
        }
    }

    /**
     * Resets and forces regeneration of the identity. Used for developer diagnostics.
     */
    suspend fun forceRegenerateIdentity(): String {
        val freshId = canonicalNodeId(UUID.randomUUID().toString())
        dataStore.saveNodeId(freshId)
        cachedNodeId = freshId
        MeshLogger.i(TAG, "Developer reset forced regeneration of Node ID: $freshId")
        return freshId
    }

    /**
     * Synchronously returns the cached node ID if available.
     */
    fun getCachedNodeId(): String? {
        return cachedNodeId
    }
}
