package com.qualcomm.meshmind.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local Room Entity representing a persistent system log entry.
 */
@Entity(tableName = "system_logs")
data class LogEntity(
    val timestamp: Long,
    val level: String,
    val tag: String,
    val message: String
) {
    @PrimaryKey(autoGenerate = true)
    var id: Long = 0
}
