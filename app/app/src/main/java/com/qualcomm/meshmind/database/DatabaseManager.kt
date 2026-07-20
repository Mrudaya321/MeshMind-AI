package com.qualcomm.meshmind.database

import android.content.Context
import com.qualcomm.meshmind.core.runtime.BaseSubsystem
import com.qualcomm.meshmind.diagnostics.models.SubsystemHealth
import com.qualcomm.meshmind.logging.MeshLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Subsystem coordinator managing the lifecycle of the Room database in Kotlin.
 */
class DatabaseManager(context: Context) : BaseSubsystem {

    private val appContext = context.applicationContext
    
    @Volatile
    private var database: AppDatabase? = null
    
    private var isOperational = false
    private var errorCount: Long = 0

    override val subsystemId: String = "database_manager"
    override val initPriority: Int = 15 // Run after storage manager

    override suspend fun initialize() {
        // Runs Room initialization inside IO thread pool
        withContext(Dispatchers.IO) {
            try {
                database = AppDatabase.getInstance(appContext)
                
                // Warm up database connection by executing a dummy write-delete
                database!!.runInTransaction {
                    database!!.logDao().deleteOldLogs(0)
                }
                
                isOperational = true
                MeshLogger.i(subsystemId, "Room database opened successfully.")
            } catch (e: Exception) {
                errorCount++
                isOperational = false
                MeshLogger.e(subsystemId, "Failed to initialize Room Database", e)
                throw e
            }
        }
    }

    override fun shutdown() {
        database?.let { db ->
            if (db.isOpen) {
                db.close()
            }
        }
        isOperational = false
        MeshLogger.i(subsystemId, "Database closed successfully.")
    }

    override fun getHealth(): SubsystemHealth {
        return SubsystemHealth(
            subsystemId,
            isOperational,
            System.currentTimeMillis(),
            errorCount,
            if (isOperational) "Room database open and operational" else "Database is offline or corrupted"
        )
    }

    fun getDatabase(): AppDatabase {
        return database ?: throw IllegalStateException("DatabaseManager has not been initialized yet.")
    }
}
