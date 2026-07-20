package com.qualcomm.meshmind.state

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks stats of in-flight and transmitted packet states.
 */
class PacketState private constructor() {

    enum class Status {
        PENDING, TRANSMITTED, ACKNOWLEDGED, EXPIRED, FAILED
    }

    data class PacketTxRecord(
        val packetId: String,
        val retryCount: Int,
        val status: Status,
        val txTimestamp: Long = System.currentTimeMillis()
    )

    private val inFlightPackets = ConcurrentHashMap<String, PacketTxRecord>()
    private val packetsSentCount = AtomicLong(0)
    private val packetsReceivedCount = AtomicLong(0)
    private val packetsForwardedCount = AtomicLong(0)
    private val packetLossCount = AtomicLong(0)

    companion object {
        @Volatile
        private var instance: PacketState? = null

        fun getInstance(): PacketState {
            return instance ?: synchronized(this) {
                instance ?: PacketState().also { instance = it }
            }
        }
    }

    fun trackPacketTx(packetId: String, record: PacketTxRecord) {
        inFlightPackets[packetId] = record
    }

    fun getPacketRecord(packetId: String): PacketTxRecord? {
        return inFlightPackets[packetId]
    }

    fun incrementSent() = packetsSentCount.incrementAndGet()
    fun incrementReceived() = packetsReceivedCount.incrementAndGet()
    fun incrementForwarded() = packetsForwardedCount.incrementAndGet()
    fun incrementLost() = packetLossCount.incrementAndGet()

    val sentCount: Long get() = packetsSentCount.get()
    val receivedCount: Long get() = packetsReceivedCount.get()
    val forwardedCount: Long get() = packetsForwardedCount.get()
    val lossCount: Long get() = packetLossCount.get()

    fun clear() {
        inFlightPackets.clear()
        packetsSentCount.set(0)
        packetsReceivedCount.set(0)
        packetsForwardedCount.set(0)
        packetLossCount.set(0)
    }
}
