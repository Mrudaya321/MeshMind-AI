package com.qualcomm.meshmind.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface EmergencyBroadcastDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(broadcast: EmergencyBroadcastEntity)

    @Update
    suspend fun update(broadcast: EmergencyBroadcastEntity)

    @Query("SELECT * FROM emergency_broadcasts ORDER BY createdAt DESC")
    suspend fun getAllBroadcasts(): List<EmergencyBroadcastEntity>

    @Query("SELECT * FROM emergency_broadcasts WHERE emergencyId = :id")
    suspend fun getBroadcastById(id: String): EmergencyBroadcastEntity?
    
    @Query("UPDATE emergency_broadcasts SET deliveryStatus = :status WHERE emergencyId = :id")
    suspend fun updateDeliveryStatus(id: String, status: String)
}
