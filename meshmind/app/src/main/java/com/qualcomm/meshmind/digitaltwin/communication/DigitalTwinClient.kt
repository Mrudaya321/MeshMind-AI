package com.qualcomm.meshmind.digitaltwin.communication

import com.qualcomm.meshmind.core.runtime.BaseSubsystem
import com.qualcomm.meshmind.core.scheduling.TaskScheduler
import com.qualcomm.meshmind.diagnostics.models.SubsystemHealth
import com.qualcomm.meshmind.logging.MeshLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.DataOutputStream
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Passive Digital Twin synchronization client in Kotlin.
 * Feeds structured telemetry and health statistics to the observing Snapdragon AI PC.
 */
class DigitalTwinClient : BaseSubsystem {

    private val twinScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isOperational = false
    private var errorCount: Long = 0

    // Local buffering queue for offline cache storage
    private val telemetryCacheQueue = ConcurrentLinkedQueue<String>()

    private var activeSocket: Socket? = null
    private var isConnectedToPc = false

    companion object {
        private const val TAG = "DigitalTwinClient"
        private const val PC_SERVER_PORT = 9090
        private const val PC_SERVER_HOST = "192.168.1.100" // Example target IP of the AI PC observer
        private const val TASK_RECONNECT = "twin_pc_reconnect"
    }

    override val subsystemId: String = "digital_twin_client"
    override val initPriority: Int = 90

    override suspend fun initialize() {
        isOperational = true
        MeshLogger.i(TAG, "Digital Twin synchronization client initialized.")

        // Start periodic reconnect & flush worker sweeps (every 15 seconds)
        TaskScheduler.getInstance().schedulePeriodic(TASK_RECONNECT, 15000, 0.05) {
            verifyConnectionAndFlushCache()
        }
    }

    override fun shutdown() {
        TaskScheduler.getInstance().cancel(TASK_RECONNECT)
        closeSocket()
        isOperational = false
        MeshLogger.i(TAG, "Digital Twin client disconnected.")
    }

    override fun getHealth(): SubsystemHealth {
        return SubsystemHealth(
            subsystemId,
            isOperational,
            System.currentTimeMillis(),
            errorCount,
            if (isConnectedToPc) "Connected to Snapdragon AI PC twin server" else "Twin connection offline (Cache buffer: ${telemetryCacheQueue.size} snapshots)"
        )
    }

    fun isConnected(): Boolean = isConnectedToPc
    fun getCacheSize(): Int = telemetryCacheQueue.size

    /**
     * Pushes a serialized log/telemetry block to the Snapdragon PC.
     * Buffers offline if PC is not reachable.
     */
    fun pushTelemetrySnapshot(serializedData: String) {
        if (!isOperational) {
            errorCount++
            return
        }

        // Cache the snapshot
        telemetryCacheQueue.offer(serializedData)

        // Attempt asynchronous dispatch
        twinScope.launch {
            flushCache()
        }
    }

    private fun verifyConnectionAndFlushCache() {
        if (!isOperational) return
        
        twinScope.launch {
            if (!isConnectedToPc) {
                attemptConnection()
            }
            flushCache()
        }
    }

    private fun attemptConnection() {
        try {
            closeSocket()
            // Establish socket with short connection timeout
            activeSocket = Socket(PC_SERVER_HOST, PC_SERVER_PORT).apply {
                soTimeout = 3000
            }
            isConnectedToPc = true
            MeshLogger.i(TAG, "Successfully connected to observing AI PC at $PC_SERVER_HOST:$PC_SERVER_PORT")
        } catch (e: Exception) {
            isConnectedToPc = false
            // Suppress logs under offline loops to prevent spamming
            MeshLogger.d(TAG, "AI PC observer server is offline. Telemetry is being cached locally.")
        }
    }

    private @Synchronized fun flushCache() {
        val socket = activeSocket
        if (socket == null || !isConnectedToPc || socket.isClosed) {
            return
        }

        try {
            val dos = DataOutputStream(socket.getOutputStream())
            while (!telemetryCacheQueue.isEmpty()) {
                val data = telemetryCacheQueue.peek() ?: break
                val bytes = data.toByteArray(Charsets.UTF_8)
                
                // Write length followed by bytes block
                dos.writeInt(bytes.size)
                dos.write(bytes)
                dos.flush()

                // Remove from cache after successful transmit
                telemetryCacheQueue.poll()
            }
        } catch (e: Exception) {
            errorCount++
            isConnectedToPc = false
            closeSocket()
            MeshLogger.w(TAG, "AI PC observer socket link broken. Re-buffering telemetry stream.")
        }
    }

    private fun closeSocket() {
        try {
            activeSocket?.close()
        } catch (ignored: Exception) {}
        activeSocket = null
        isConnectedToPc = false
    }
}
