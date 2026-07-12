package com.qualcomm.meshmind.packet

import com.qualcomm.meshmind.core.runtime.BaseSubsystem
import com.qualcomm.meshmind.diagnostics.models.SubsystemHealth
import com.qualcomm.meshmind.logging.MeshLogger
import com.qualcomm.meshmind.communication.PacketScheduler
import com.qualcomm.meshmind.communication.RelayManager
import com.qualcomm.meshmind.packet.models.MeshFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicLong

/**
 * Handles background priority schedule packet dispatching and statistics tracking.
 */
class PacketManager : BaseSubsystem {

    private val managerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var isOperational = false
    private var errorCount = AtomicLong(0)

    // Statistics Counters
    val routingAttempts = AtomicLong(0)
    val receivedPackets = AtomicLong(0)
    val failedPackets = AtomicLong(0)
    val retransmittedPackets = AtomicLong(0)
    val transportSendAttempts = AtomicLong(0)
    val transportSendSuccesses = AtomicLong(0)

    companion object {
        private const val TAG = "PacketManager"
        
        @Volatile
        private var instance: PacketManager? = null

        fun getInstance(): PacketManager {
            return instance ?: synchronized(this) {
                instance ?: PacketManager().also { instance = it }
            }
        }
    }

    override val subsystemId: String = "packet_manager"
    override val initPriority: Int = 46 // Run after routing engine (45) but before relay (47)

    override suspend fun initialize() {
        isOperational = true
        MeshLogger.i(TAG, "PacketManager initialized. Starting PacketScheduler dispatcher loop...")
        startSchedulerDispatcher()
    }

    override fun shutdown() {
        isOperational = false
        MeshLogger.i(TAG, "PacketManager shutdown.")
    }

    override fun getHealth(): SubsystemHealth {
        return SubsystemHealth(
            subsystemId,
            isOperational,
            System.currentTimeMillis(),
            errorCount.get(),
            if (isOperational) "Active (Attempts: ${routingAttempts.get()}, Recv: ${receivedPackets.get()}, Fail: ${failedPackets.get()}, Retransmit: ${retransmittedPackets.get()})" else "Offline"
        )
    }

    private fun startSchedulerDispatcher() {
        managerScope.launch {
            val scheduler = PacketScheduler.getInstance()
            while (isActive && isOperational) {
                try {
                    val frame = scheduler.pollNext()
                    if (frame != null) {
                        com.qualcomm.meshmind.diagnostics.ChatDeliveryDiagnostics.logEvent(
                            traceId = frame.packetId,
                            stage = "PACKET_DEQUEUED"
                        )
                        routingAttempts.incrementAndGet()
                        MeshLogger.d(TAG, "Drained outgoing frame ${frame.packetId} from PacketScheduler, forwarding to RelayManager.")
                        // Forward to RelayManager for destination checks & multi-hop forwarding
                        RelayManager.getInstance().processFrame(frame)
                    }
                } catch (e: Exception) {
                    errorCount.incrementAndGet()
                    MeshLogger.e(TAG, "Error in PacketScheduler dispatcher loop", e)
                }
                delay(50) // Small yield delay to prevent high CPU utilization
            }
        }
    }

    fun incrementRetransmissions() {
        retransmittedPackets.incrementAndGet()
    }

    fun incrementFailed() {
        failedPackets.incrementAndGet()
    }

    fun incrementReceived() {
        receivedPackets.incrementAndGet()
    }
}
