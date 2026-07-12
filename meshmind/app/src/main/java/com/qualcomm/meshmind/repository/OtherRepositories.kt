package com.qualcomm.meshmind.repository

import com.qualcomm.meshmind.core.dependency.ServiceLocator
import com.qualcomm.meshmind.database.DatabaseManager
import com.qualcomm.meshmind.database.DiagnosticEventDao
import com.qualcomm.meshmind.database.DiagnosticEventEntity
import com.qualcomm.meshmind.database.EmergencyEventDao
import com.qualcomm.meshmind.database.EmergencyEventEntity
import com.qualcomm.meshmind.database.PacketHistoryDao
import com.qualcomm.meshmind.database.PacketHistoryEntity
import com.qualcomm.meshmind.database.RoutingInformationDao
import com.qualcomm.meshmind.database.RoutingInformationEntity
import com.qualcomm.meshmind.database.RuntimeStatisticsDao
import com.qualcomm.meshmind.database.RuntimeStatisticsEntity
import com.qualcomm.meshmind.database.TelemetryHistoryDao
import com.qualcomm.meshmind.database.TelemetryHistoryEntity
import com.qualcomm.meshmind.state.NeighborStateRepository

class PacketHistoryRepository : BaseRepository() {
    private val packetDao: PacketHistoryDao by lazy {
        ServiceLocator.get(DatabaseManager::class.java).getDatabase().packetHistoryDao()
    }

    suspend fun logPacket(
        packetId: String,
        source: String,
        destination: String,
        hopCount: Int,
        ttl: Int,
        checksum: Long,
        payloadLength: Int,
        payload: ByteArray? = null,
        isOutgoing: Boolean,
        status: String
    ) = executeIO {
        val entity = PacketHistoryEntity(
            packetId = packetId,
            sourceNodeId = source,
            destinationNodeId = destination,
            hopCount = hopCount,
            ttl = ttl,
            checksum = checksum,
            payloadLength = payloadLength,
            payload = payload,
            isOutgoing = isOutgoing,
            status = status,
            timestamp = System.currentTimeMillis()
        )
        packetDao.insertPacket(entity)
    }

    suspend fun getRecentPackets(limit: Int): List<PacketHistoryEntity> = executeIO {
        packetDao.getRecentPackets(limit)
    }

    suspend fun pruneOldPackets(cutoff: Long) = executeIO {
        packetDao.deleteOldPackets(cutoff)
    }
}

class TelemetryRepository : BaseRepository() {
    private val telemetryDao: TelemetryHistoryDao by lazy {
        ServiceLocator.get(DatabaseManager::class.java).getDatabase().telemetryHistoryDao()
    }

    suspend fun saveTelemetry(
        batteryLevel: Int?,
        wifiRssi: Int?,
        isWifiConnected: Boolean,
        bluetoothNeighborCount: Int,
        cpuDelayMs: Long
    ) = executeIO {
        val entity = TelemetryHistoryEntity(
            timestamp = System.currentTimeMillis(),
            batteryLevel = batteryLevel,
            wifiRssi = wifiRssi,
            isWifiConnected = isWifiConnected,
            bluetoothNeighborCount = bluetoothNeighborCount,
            cpuExecutionDelayMs = cpuDelayMs
        )
        telemetryDao.insertTelemetry(entity)
    }

    suspend fun getRecentTelemetry(limit: Int): List<TelemetryHistoryEntity> = executeIO {
        telemetryDao.getRecentTelemetry(limit)
    }

    suspend fun pruneOldTelemetry(cutoff: Long) = executeIO {
        telemetryDao.deleteOldTelemetry(cutoff)
    }
}

class RuntimeStatisticsRepository : BaseRepository() {
    private val statsDao: RuntimeStatisticsDao by lazy {
        ServiceLocator.get(DatabaseManager::class.java).getDatabase().runtimeStatisticsDao()
    }

    suspend fun recordStats(
        memoryUsage: Long,
        threadCount: Int,
        activeCoroutines: Int,
        dbSize: Long
    ) = executeIO {
        val entity = RuntimeStatisticsEntity(
            timestamp = System.currentTimeMillis(),
            memoryUsageBytes = memoryUsage,
            threadCount = threadCount,
            activeCoroutinesCount = activeCoroutines,
            databaseSize = dbSize
        )
        statsDao.insertStats(entity)
    }

    suspend fun pruneOldStats(cutoff: Long) = executeIO {
        statsDao.deleteOldStats(cutoff)
    }
}

class DiagnosticsRepository : BaseRepository() {
    private val diagnosticsDao: DiagnosticEventDao by lazy {
        ServiceLocator.get(DatabaseManager::class.java).getDatabase().diagnosticEventDao()
    }

    suspend fun recordDiagnostic(taskKey: String, durationMs: Long, message: String) = executeIO {
        val entity = DiagnosticEventEntity(
            timestamp = System.currentTimeMillis(),
            taskKey = taskKey,
            durationMs = durationMs,
            message = message
        )
        diagnosticsDao.insertDiagnostic(entity)
    }

    suspend fun getRecentDiagnostics(limit: Int): List<DiagnosticEventEntity> = executeIO {
        diagnosticsDao.getRecentDiagnostics(limit)
    }

    suspend fun pruneOldDiagnostics(cutoff: Long) = executeIO {
        diagnosticsDao.deleteOldDiagnostics(cutoff)
    }
}

class EmergencyEventRepository : BaseRepository() {
    private val emergencyDao: EmergencyEventDao by lazy {
        ServiceLocator.get(DatabaseManager::class.java).getDatabase().emergencyEventDao()
    }

    suspend fun recordEmergency(sender: String, message: String, severity: String) = executeIO {
        val entity = EmergencyEventEntity(
            timestamp = System.currentTimeMillis(),
            senderNodeId = sender,
            messagePayload = message,
            severity = severity
        )
        emergencyDao.insertEmergency(entity)
    }

    suspend fun getRecentEmergencies(limit: Int): List<EmergencyEventEntity> = executeIO {
        emergencyDao.getRecentEmergencyEvents(limit)
    }
}

class RoutingStateRepository : BaseRepository() {
    private val routingDao: RoutingInformationDao by lazy {
        ServiceLocator.get(DatabaseManager::class.java).getDatabase().routingInformationDao()
    }

    suspend fun saveRoute(
        destination: String,
        nextHop: String,
        hopCount: Int,
        sequenceNumber: Int,
        isValid: Boolean
    ) = executeIO {
        val entity = RoutingInformationEntity(
            destinationNodeId = destination,
            nextHopNodeId = nextHop,
            hopCount = hopCount,
            sequenceNumber = sequenceNumber,
            updatedTimestamp = System.currentTimeMillis(),
            routeAge = 0L,
            isValid = isValid
        )
        routingDao.insertRoutingInfo(entity)
    }

    suspend fun getRoute(destination: String): RoutingInformationEntity? = executeIO {
        routingDao.getRoute(destination)
    }

    suspend fun getAllValidRoutes(): List<RoutingInformationEntity> = executeIO {
        routingDao.getValidRoutes()
    }

    suspend fun removeRoute(destination: String) = executeIO {
        routingDao.deleteRoute(destination)
    }
}

class PacketQueueRepository : BaseRepository() {
    private val packetDao: PacketHistoryDao by lazy {
        ServiceLocator.get(DatabaseManager::class.java).getDatabase().packetHistoryDao()
    }

    suspend fun enqueuePacket(
        packetId: String,
        source: String,
        destination: String,
        hopCount: Int,
        ttl: Int,
        checksum: Long,
        payloadLength: Int,
        payload: ByteArray? = null
    ) = executeIO {
        val entity = PacketHistoryEntity(
            packetId = packetId,
            sourceNodeId = source,
            destinationNodeId = destination,
            hopCount = hopCount,
            ttl = ttl,
            checksum = checksum,
            payloadLength = payloadLength,
            payload = payload,
            isOutgoing = true,
            status = "Queued",
            timestamp = System.currentTimeMillis()
        )
        packetDao.insertPacket(entity)
    }

    suspend fun getPendingPackets(): List<PacketHistoryEntity> = executeIO {
        packetDao.getPacketsWithStatus("Queued")
    }

    suspend fun updatePacketStatus(packetId: String, status: String) = executeIO {
        val existing = packetDao.getPacket(packetId)
        if (existing != null) {
            val updated = existing.copy(status = status, timestamp = System.currentTimeMillis())
            packetDao.insertPacket(updated)
        }
    }
}

class DigitalTwinRepository : BaseRepository() {
    private val packetHistoryRepo by lazy { PacketHistoryRepository() }
    
    suspend fun getPacketSuccessRatio(): Double {
        val recent = packetHistoryRepo.getRecentPackets(100)
        if (recent.isEmpty()) return 1.0
        val acked = recent.count { it.status == "Acknowledged" || it.status == "Delivered" }
        return acked.toDouble() / recent.size
    }

    suspend fun getTopologySummary(): String {
        val neighbors = NeighborStateRepository.getInstance().getAllNeighbors()
        return "Neighbors count: ${neighbors.size}. Links online: ${neighbors.size}"
    }
}
