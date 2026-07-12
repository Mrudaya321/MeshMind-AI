package com.qualcomm.meshmind.communication

import com.qualcomm.meshmind.core.dependency.ServiceLocator
import com.qualcomm.meshmind.database.DatabaseManager
import com.qualcomm.meshmind.database.PacketHistoryDao
import com.qualcomm.meshmind.database.PacketHistoryEntity
import com.qualcomm.meshmind.logging.MeshLogger
import com.qualcomm.meshmind.packet.models.MeshFrame
import com.qualcomm.meshmind.packet.parser.PacketParser
import com.qualcomm.meshmind.packet.serializer.PacketSerializer
import com.qualcomm.meshmind.repository.PacketHistoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages Delay-Tolerant Networking (DTN) packet buffering inside persistent SQL storage.
 */
class DtnBufferManager {

    private val packetDao: PacketHistoryDao by lazy {
        ServiceLocator.get(DatabaseManager::class.java).getDatabase().packetHistoryDao()
    }

    private val packetHistoryRepo: PacketHistoryRepository by lazy {
        ServiceLocator.get(PacketHistoryRepository::class.java)
    }

    companion object {
        private const val TAG = "DtnBufferManager"
        private const val STATUS_DTN_BUFFERED = "BufferedForDTN"
        
        @Volatile
        private var instance: DtnBufferManager? = null

        fun getInstance(): DtnBufferManager {
            return instance ?: synchronized(this) {
                instance ?: DtnBufferManager().also { instance = it }
            }
        }
    }

    /**
     * Persists the frame with "BufferedForDTN" status.
     */
    suspend fun bufferFrame(frame: MeshFrame) = withContext(Dispatchers.IO) {
        try {
            val serializedFrame = PacketSerializer.serialize(frame)
            val entity = PacketHistoryEntity(
                packetId = frame.packetId,
                sourceNodeId = frame.sourceNodeId,
                destinationNodeId = frame.destinationNodeId,
                hopCount = frame.hopCount,
                ttl = frame.ttl,
                checksum = frame.checksum,
                payloadLength = serializedFrame.size,
                payload = frame.payload,
                isOutgoing = true,
                status = STATUS_DTN_BUFFERED,
                timestamp = System.currentTimeMillis()
            )
            packetDao.insertPacket(entity)
            MeshLogger.i(TAG, "Frame ${frame.packetId} buffered in DTN store for destination: ${frame.destinationNodeId}")
        } catch (e: Exception) {
            MeshLogger.e(TAG, "Failed to buffer frame in DTN", e)
        }
    }

    /**
     * Fetches and cleans buffered packets when a neighbor route link is discovered.
     */
    suspend fun releaseFramesForDestination(destinationNodeId: String): List<MeshFrame> = withContext(Dispatchers.IO) {
        try {
            val buffered = packetDao.getPacketsWithStatus(STATUS_DTN_BUFFERED)
            val targetFrames = mutableListOf<MeshFrame>()
            
            for (entity in buffered) {
                if (entity.destinationNodeId == destinationNodeId) {
                    val frame = MeshFrame(
                        protocolVersion = 1,
                        flags = 0,
                        packetType = com.qualcomm.meshmind.packet.models.PacketType.DATA,
                        ttl = entity.ttl,
                        maxPacketAgeMs = 30000L,
                        priority = 2,
                        hopCount = entity.hopCount,
                        packetId = entity.packetId,
                        sourceNodeId = entity.sourceNodeId,
                        destinationNodeId = entity.destinationNodeId,
                        previousHopNodeId = null,
                        iv = null,
                        payload = entity.payload ?: ByteArray(0),
                        checksum = entity.checksum
                    )
                    
                    // Update status in SQL
                    packetDao.insertPacket(entity.copy(status = "Queued", timestamp = System.currentTimeMillis()))
                    targetFrames.add(frame)
                }
            }
            if (targetFrames.isNotEmpty()) {
                MeshLogger.i(TAG, "Released ${targetFrames.size} DTN frames for destination: $destinationNodeId")
            }
            targetFrames
        } catch (e: Exception) {
            MeshLogger.e(TAG, "Failed to release DTN buffered frames", e)
            emptyList()
        }
    }
}
