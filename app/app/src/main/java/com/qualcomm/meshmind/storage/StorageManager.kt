package com.qualcomm.meshmind.storage

import android.content.Context
import com.qualcomm.meshmind.core.runtime.BaseSubsystem
import com.qualcomm.meshmind.diagnostics.models.SubsystemHealth
import com.qualcomm.meshmind.logging.MeshLogger
import java.io.File

/**
 * Storage Subsystem Orchestrator in Kotlin.
 * Configures directories, handles secure properties, and prepares AI models local caching.
 */
class StorageManager(context: Context) : BaseSubsystem {

    private val appContext = context.applicationContext
    
    @Volatile
    private var secureStorage: SecureStorageProvider? = null
    
    lateinit var modelDirectory: File
        private set
        
    lateinit var diagnosticsDirectory: File
        private set

    private var isOperational = false
    private var errorCount: Long = 0

    override val subsystemId: String = "storage_manager"
    override val initPriority: Int = 10 // High priority

    override suspend fun initialize() {
        try {
            secureStorage = SecureStorageProvider(appContext)
            
            // Set up directories for AI LiteRT models and exported diagnostics
            modelDirectory = File(appContext.filesDir, "models").apply {
                if (!exists()) mkdirs()
            }

            diagnosticsDirectory = File(appContext.filesDir, "diagnostics").apply {
                if (!exists()) mkdirs()
            }

            isOperational = true
            MeshLogger.i(subsystemId, "Storage directory architecture created.")
        } catch (e: Exception) {
            errorCount++
            isOperational = false
            MeshLogger.e(subsystemId, "Failed to initialize storage folders", e)
            throw e
        }
    }

    override fun shutdown() {
        isOperational = false
        MeshLogger.i(subsystemId, "StorageManager subsystem shutdown.")
    }

    override fun getHealth(): SubsystemHealth {
        return SubsystemHealth(
            subsystemId,
            isOperational,
            System.currentTimeMillis(),
            errorCount,
            if (isOperational) "Storage filesystem is online and secure preferences are initialized" else "Storage initialization failed"
        )
    }

    fun getSecureStorage(): SecureStorageProvider {
        return secureStorage ?: throw IllegalStateException("StorageManager has not been initialized yet.")
    }
}
