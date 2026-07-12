package com.qualcomm.meshmind.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * Data Access Object for local persistent logs.
 */
@Dao
interface LogDao {

    @Insert
    fun insertLog(log: LogEntity)

    @Query("SELECT * FROM system_logs ORDER BY timestamp DESC LIMIT 500")
    fun getRecentLogs(): List<LogEntity>

    @Query("DELETE FROM system_logs WHERE timestamp < :cutoffTimestamp")
    fun deleteOldLogs(cutoffTimestamp: Long): Int
}
