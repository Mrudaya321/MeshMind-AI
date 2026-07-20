package com.qualcomm.meshmind.network.transport

import com.qualcomm.meshmind.logging.MeshLogger
import com.qualcomm.meshmind.packet.models.MeshFrame
import com.qualcomm.meshmind.packet.serializer.PacketSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages an active, bidirectional, verified TCP socket connection for a specific logical Node ID.
 */
class TransportSession(
    val remoteNodeId: String,
    private val socket: Socket,
    private val peerCrypto: PeerSessionCrypto
) {
    private val sessionScope = CoroutineScope(Dispatchers.IO + Job())
    private val writeMutex = Mutex()
    
    private val dataInputStream: DataInputStream
    private val dataOutputStream: DataOutputStream
    private val isRunning = AtomicBoolean(true)

    companion object {
        private const val TAG = "TransportSession"
        private const val MAX_FRAME_SIZE = 1024 * 1024 // 1MB max frame
    }

    init {
        dataInputStream = DataInputStream(socket.getInputStream())
        dataOutputStream = DataOutputStream(socket.getOutputStream())
    }

    fun startReadLoop() {
        sessionScope.launch {
            try {
                while (isActive && isRunning.get() && !socket.isClosed) {
                    val length = dataInputStream.readInt()
                    
                    if (length <= 0 || length > MAX_FRAME_SIZE) {
                        MeshLogger.e(TAG, "Invalid transport frame length: $length. Closing session.")
                        break
                    }
                    
                    val rawBytes = ByteArray(length)
                    dataInputStream.readFully(rawBytes)
                    
                    // Route to MMF receive pipeline
                    try {
                        val traceId = peekPacketId(rawBytes)
                        com.qualcomm.meshmind.diagnostics.ChatDeliveryDiagnostics.logEvent(
                            traceId = traceId,
                            stage = "REMOTE_FRAME_READ",
                            remoteFrameObserved = true
                        )

                        val rawFrame = com.qualcomm.meshmind.packet.parser.PacketParser.parse(rawBytes)
                        
                        com.qualcomm.meshmind.diagnostics.ChatDeliveryDiagnostics.logEvent(
                            traceId = rawFrame.packetId,
                            stage = "REMOTE_FRAME_DECODED"
                        )
                        
                        if (com.qualcomm.meshmind.packet.validation.FrameValidator.validateFrame(rawFrame) &&
                            com.qualcomm.meshmind.security.SecurityManager.getInstance().verifyUniqueFrame(rawFrame)) {
                            
                            com.qualcomm.meshmind.diagnostics.ChatDeliveryDiagnostics.logEvent(
                                traceId = rawFrame.packetId,
                                stage = "WIRE_DECRYPT_STARTED"
                            )

                            val logicalFrame = if (rawFrame.packetType == com.qualcomm.meshmind.packet.models.PacketType.DATA || rawFrame.packetType == com.qualcomm.meshmind.packet.models.PacketType.EMERGENCY) {
                                try {
                                    val dis = java.io.DataInputStream(java.io.ByteArrayInputStream(rawFrame.payload))
                                    val cryptoVersion = dis.readByte()
                                    if (cryptoVersion.toInt() != 1) {
                                        throw IllegalStateException("Unsupported crypto version: $cryptoVersion")
                                    }
                                    val nonceLength = dis.readByte().toInt()
                                    val nonce = ByteArray(nonceLength)
                                    dis.readFully(nonce)
                                    val ciphertext = ByteArray(dis.available())
                                    dis.readFully(ciphertext)
                                    
                                    val decryptedPayload = peerCrypto.decryptPayload(nonce, ciphertext)
                                    val logicalChecksum = com.qualcomm.meshmind.packet.checksum.ChecksumCalculator.calculateCrc32(decryptedPayload)
                                    
                                    com.qualcomm.meshmind.diagnostics.ChatDeliveryDiagnostics.logEvent(
                                        traceId = rawFrame.packetId,
                                        stage = "WIRE_DECRYPT_SUCCEEDED"
                                    )
                                    
                                    rawFrame.copy(payload = decryptedPayload, checksum = logicalChecksum)
                                } catch (e: Exception) {
                                    com.qualcomm.meshmind.diagnostics.ChatDeliveryDiagnostics.logEvent(
                                        traceId = rawFrame.packetId,
                                        stage = "WIRE_DECRYPT_AUTH_FAILED",
                                        terminalReason = e.javaClass.simpleName
                                    )
                                    MeshLogger.e(TAG, "Failed to decrypt frame payload for packet ${rawFrame.packetId}", e)
                                    null
                                }
                            } else {
                                rawFrame
                            }

                            if (logicalFrame != null) {
                                // Phase 13 Observer Tap
                                try {
                                    val localNodeId = com.qualcomm.meshmind.core.dependency.ServiceLocator.get(com.qualcomm.meshmind.identity.DeviceIdentityManager::class.java).resolveNodeId()
                                    val obsMeta = "{\"timestamp\":${System.currentTimeMillis()},\"stage\":\"DIRECT_RECEIVED\",\"node\":\"$localNodeId\",\"packetId\":\"${logicalFrame.packetId}\"}"
                                    val obsBytes = PacketSerializer.serialize(logicalFrame)
                                    com.qualcomm.meshmind.observer.ObserverPacketTap.enqueueObservation(
                                        com.qualcomm.meshmind.observer.ObserverRecord(com.qualcomm.meshmind.observer.ObserverFrameCodec.TYPE_PACKET_OBSERVATION, obsMeta, obsBytes)
                                    )
                                } catch (ignored: Exception) {}
                                
                                com.qualcomm.meshmind.packet.PacketManager.getInstance().incrementReceived()
                                com.qualcomm.meshmind.communication.RelayManager.getInstance().processFrame(logicalFrame)
                            }
                        }
                    } catch (e: Exception) {
                        MeshLogger.w(TAG, "Failed to parse incoming frame over session to $remoteNodeId: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    MeshLogger.e(TAG, "Session read loop failed for $remoteNodeId", e)
                }
            } finally {
                closeSession()
            }
        }
    }

    suspend fun sendFrame(frame: MeshFrame): Boolean {
        if (!isActive()) return false

        com.qualcomm.meshmind.diagnostics.ChatDeliveryDiagnostics.logEvent(
            traceId = frame.packetId,
            stage = "TRANSPORT_WRITE_STARTED"
        )

        return try {
            val frameToSerialize = if (frame.packetType == com.qualcomm.meshmind.packet.models.PacketType.DATA || frame.packetType == com.qualcomm.meshmind.packet.models.PacketType.EMERGENCY) {
                com.qualcomm.meshmind.diagnostics.ChatDeliveryDiagnostics.logEvent(
                    traceId = frame.packetId,
                    stage = "WIRE_ENCRYPT_STARTED"
                )
                try {
                    val (nonce, ciphertext) = peerCrypto.encryptPayload(frame.payload)
                    val envelope = java.io.ByteArrayOutputStream()
                    val dos = java.io.DataOutputStream(envelope)
                    dos.writeByte(1) // cryptoVersion
                    dos.writeByte(nonce.size)
                    dos.write(nonce)
                    dos.write(ciphertext)
                    dos.flush()
                    val encryptedPayload = envelope.toByteArray()
                    val encryptedChecksum = com.qualcomm.meshmind.packet.checksum.ChecksumCalculator.calculateCrc32(encryptedPayload)
                    
                    com.qualcomm.meshmind.diagnostics.ChatDeliveryDiagnostics.logEvent(
                        traceId = frame.packetId,
                        stage = "WIRE_ENCRYPT_SUCCEEDED"
                    )
                    
                    frame.copy(
                        payload = encryptedPayload,
                        checksum = encryptedChecksum
                    )
                } catch (e: Exception) {
                    com.qualcomm.meshmind.diagnostics.ChatDeliveryDiagnostics.logEvent(
                        traceId = frame.packetId,
                        stage = "WIRE_ENCRYPT_FAILED",
                        terminalReason = e.javaClass.simpleName
                    )
                    throw e
                }
            } else {
                frame
            }

            val rawBytes = PacketSerializer.serialize(frameToSerialize)
            
            // Phase 13 Observer Tap
            try {
                val localNodeId = com.qualcomm.meshmind.core.dependency.ServiceLocator.get(com.qualcomm.meshmind.identity.DeviceIdentityManager::class.java).resolveNodeId()
                val obsMeta = "{\"timestamp\":${System.currentTimeMillis()},\"stage\":\"NEXT_HOP_SENT\",\"node\":\"$localNodeId\",\"packetId\":\"${frameToSerialize.packetId}\"}"
                com.qualcomm.meshmind.observer.ObserverPacketTap.enqueueObservation(
                    com.qualcomm.meshmind.observer.ObserverRecord(com.qualcomm.meshmind.observer.ObserverFrameCodec.TYPE_PACKET_OBSERVATION, obsMeta, rawBytes)
                )
            } catch (ignored: Exception) {}
            
            writeMutex.withLock {
                dataOutputStream.writeInt(rawBytes.size)
                dataOutputStream.write(rawBytes)
                dataOutputStream.flush()
            }
            com.qualcomm.meshmind.diagnostics.ChatDeliveryDiagnostics.logEvent(
                traceId = frame.packetId,
                stage = "TRANSPORT_WRITE_SUCCEEDED",
                socketWriteSuccess = true
            )
            true
        } catch (e: Exception) {
            com.qualcomm.meshmind.diagnostics.ChatDeliveryDiagnostics.logEvent(
                traceId = frame.packetId,
                stage = "TRANSPORT_WRITE_FAILED",
                terminalReason = e.javaClass.simpleName,
                socketWriteSuccess = false
            )
            MeshLogger.e(TAG, "Failed to send frame to $remoteNodeId", e)
            closeSession()
            false
        }
    }

    fun isActive(): Boolean {
        return isRunning.get() && !socket.isClosed && socket.isConnected
    }

    fun closeSession() {
        if (isRunning.compareAndSet(true, false)) {
            try {
                dataInputStream.close()
            } catch (e: Exception) {}
            try {
                dataOutputStream.close()
            } catch (e: Exception) {}
            try {
                socket.close()
            } catch (e: Exception) {}
            
            PeerSessionRegistry.getInstance().removeSession(remoteNodeId, this)
        }
    }

    private fun peekPacketId(rawBytes: ByteArray): String {
        return try {
            val dis = java.io.DataInputStream(java.io.ByteArrayInputStream(rawBytes))
            dis.skipBytes(32) // Skip protocolVersion(4) + flags(4) + typeOrdinal(4) + ttl(4) + maxPacketAgeMs(8) + priority(4) + hopCount(4)
            dis.readUTF()
        } catch (e: Exception) {
            "unknown"
        }
    }
}
