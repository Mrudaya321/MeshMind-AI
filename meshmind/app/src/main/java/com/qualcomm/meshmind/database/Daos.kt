package com.qualcomm.meshmind.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface ConversationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertConversation(conversation: ConversationEntity)

    @Query("SELECT * FROM conversations ORDER BY lastActiveTimestamp DESC")
    fun getAllConversations(): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE conversationId = :conversationId")
    fun getConversation(conversationId: String): ConversationEntity?

    @Query("DELETE FROM conversations WHERE conversationId = :conversationId")
    fun deleteConversation(conversationId: String)
}

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertMessage(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE messageId = :messageId")
    fun getMessage(messageId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun getMessagesForConversation(conversationId: String): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId AND isRead = 0")
    fun getUnreadCount(conversationId: String): Int

    @Update
    fun updateMessage(message: MessageEntity)

    @Query("UPDATE messages SET deliveryStatus = :status WHERE messageId = :messageId")
    fun updateMessageStatus(messageId: String, status: String)

    @Query("UPDATE messages SET deliveryStatus = :status, relayNodeId = :relayNodeId WHERE messageId = :messageId")
    fun updateMessageRelay(messageId: String, status: String, relayNodeId: String)

    @Query("SELECT * FROM messages WHERE body LIKE '%' || :query || '%'")
    fun searchMessages(query: String): List<MessageEntity>
}

@Dao
interface NeighborStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertNeighbor(neighbor: NeighborStateEntity)

    @Query("SELECT * FROM neighbor_states WHERE nodeId = :nodeId")
    fun getNeighbor(nodeId: String): NeighborStateEntity?

    @Query("SELECT * FROM neighbor_states ORDER BY lastSeenTimestamp DESC")
    fun getAllNeighbors(): List<NeighborStateEntity>

    @Query("DELETE FROM neighbor_states WHERE nodeId = :nodeId")
    fun deleteNeighbor(nodeId: String)

    @Query("DELETE FROM neighbor_states WHERE lastSeenTimestamp < :expiryThreshold")
    fun deleteStaleNeighbors(expiryThreshold: Long): Int
}

@Dao
interface RoutingInformationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertRoutingInfo(route: RoutingInformationEntity)

    @Query("SELECT * FROM routing_information WHERE destinationNodeId = :destinationNodeId")
    fun getRoute(destinationNodeId: String): RoutingInformationEntity?

    @Query("SELECT * FROM routing_information WHERE isValid = 1")
    fun getValidRoutes(): List<RoutingInformationEntity>

    @Query("SELECT * FROM routing_information")
    fun getAllRoutes(): List<RoutingInformationEntity>

    @Query("DELETE FROM routing_information WHERE destinationNodeId = :destinationNodeId")
    fun deleteRoute(destinationNodeId: String)
}

@Dao
interface TelemetryHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertTelemetry(telemetry: TelemetryHistoryEntity)

    @Query("SELECT * FROM telemetry_history ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentTelemetry(limit: Int): List<TelemetryHistoryEntity>

    @Query("DELETE FROM telemetry_history WHERE timestamp < :cutoffTimestamp")
    fun deleteOldTelemetry(cutoffTimestamp: Long): Int
}

@Dao
interface PacketHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertPacket(packet: PacketHistoryEntity)

    @Query("SELECT * FROM packet_history WHERE packetId = :packetId")
    fun getPacket(packetId: String): PacketHistoryEntity?

    @Query("SELECT * FROM packet_history WHERE status = :status ORDER BY timestamp ASC")
    fun getPacketsWithStatus(status: String): List<PacketHistoryEntity>

    @Query("SELECT * FROM packet_history ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentPackets(limit: Int): List<PacketHistoryEntity>

    @Query("DELETE FROM packet_history WHERE timestamp < :cutoffTimestamp")
    fun deleteOldPackets(cutoffTimestamp: Long): Int
}

@Dao
interface DiagnosticEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertDiagnostic(event: DiagnosticEventEntity)

    @Query("SELECT * FROM diagnostic_events ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentDiagnostics(limit: Int): List<DiagnosticEventEntity>

    @Query("DELETE FROM diagnostic_events WHERE timestamp < :cutoffTimestamp")
    fun deleteOldDiagnostics(cutoffTimestamp: Long): Int
}

@Dao
interface RuntimeStatisticsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertStats(stats: RuntimeStatisticsEntity)

    @Query("SELECT * FROM runtime_statistics ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentStats(limit: Int): List<RuntimeStatisticsEntity>

    @Query("DELETE FROM runtime_statistics WHERE timestamp < :cutoffTimestamp")
    fun deleteOldStats(cutoffTimestamp: Long): Int
}

@Dao
interface EmergencyEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertEmergency(event: EmergencyEventEntity)

    @Query("SELECT * FROM emergency_events ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentEmergencyEvents(limit: Int): List<EmergencyEventEntity>
}

@Dao
interface DeviceInformationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertDevice(device: DeviceInformationEntity)

    @Query("SELECT * FROM device_information WHERE nodeId = :nodeId")
    fun getDevice(nodeId: String): DeviceInformationEntity?
}
