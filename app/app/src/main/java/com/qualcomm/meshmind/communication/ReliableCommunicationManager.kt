package com.qualcomm.meshmind.communication

import com.qualcomm.meshmind.core.dependency.ServiceLocator
import com.qualcomm.meshmind.logging.MeshLogger
import com.qualcomm.meshmind.packet.builder.PacketBuilder
import com.qualcomm.meshmind.packet.checksum.ChecksumCalculator
import com.qualcomm.meshmind.packet.models.MeshFrame
import com.qualcomm.meshmind.packet.models.PacketType
import com.qualcomm.meshmind.packet.parser.PacketParser
import com.qualcomm.meshmind.packet.serializer.PacketSerializer
import com.qualcomm.meshmind.packet.validation.FrameValidator
import com.qualcomm.meshmind.repository.MessageRepository
import com.qualcomm.meshmind.security.SecurityManager
import com.qualcomm.meshmind.network.routing.MmpRoutingEngineImpl
import com.qualcomm.meshmind.network.transport.TransportResult
import com.qualcomm.meshmind.network.routing.RoutingEngine
import com.qualcomm.meshmind.identity.DeviceIdentityManager
import com.qualcomm.meshmind.classification.EmergencyClassifier
import com.qualcomm.meshmind.classification.EmergencyClassificationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * reliable execution controller managing transmissions scheduling, retries, and acknowledgments in Kotlin.
 */
class ReliableCommunicationManager {

    private val managerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val securityManager = SecurityManager.getInstance()
    private val scheduler = PacketScheduler.getInstance()
    private val dtnBuffer = DtnBufferManager.getInstance()
    
    private val pendingAcks = ConcurrentHashMap<String, MeshFrame>()
    private val retryCounts = ConcurrentHashMap<String, Int>()

    companion object {
        private const val TAG = "ReliableCommunicationManager"
        private const val MAX_RETRIES = 3
        private const val BASE_RETRY_DELAY_MS = 2000L

        @Volatile
        private var instance: ReliableCommunicationManager? = null

        fun getInstance(): ReliableCommunicationManager {
            return instance ?: synchronized(this) {
                instance ?: ReliableCommunicationManager().also { instance = it }
            }
        }
    }

    /**
     * Prepares a text message for sending: constructs, persists, and schedules it.
     */
    suspend fun sendTextMessage(source: String, destination: String, text: String, isEmergency: Boolean = false, traceId: String? = null): SendResult = kotlinx.coroutines.withContext(managerScope.coroutineContext) {
        try {
            // Build Payload V1
            val payloadV1 = MeshChatPayloadV1(
                originalText = text,
                emergencyClassIndex = null,
                emergencyClassLabel = null,
                emergencyConfidence = null,
                taxonomyVersion = null
            )
            val payloadBytes = MeshChatPayloadCodec.encode(payloadV1)
            
            val checksum = ChecksumCalculator.calculateCrc32(payloadBytes)
            
            traceId?.let {
                com.qualcomm.meshmind.diagnostics.ChatDeliveryDiagnostics.logEvent(it, stage = "CHAT_PACKET_BUILD_STARTED")
            }

            // 1. Build initial plain frame (no encryption here, encryption is hop-by-hop in TransportSession)
            val builder = PacketBuilder()
                .setSourceNodeId(source)
                .setDestinationNodeId(destination)
                .setPacketType(PacketType.DATA)
                .setPriority(2)
                .setPayload(payloadBytes)
                .setChecksum(checksum)
            
            if (traceId != null) {
                builder.setPacketId(traceId)
            }
            
            val plainFrame = builder.build()

            traceId?.let {
                com.qualcomm.meshmind.diagnostics.ChatDeliveryDiagnostics.logEvent(it, stage = "CHAT_PACKET_BUILD_SUCCEEDED")
                com.qualcomm.meshmind.diagnostics.ChatDeliveryDiagnostics.logEvent(it, stage = "CHAT_LOCAL_PERSIST_STARTED")
            }

            // 2. Persist message inside SQL db
            val messageRepo = ServiceLocator.get(MessageRepository::class.java)
            messageRepo.saveMessage(
                messageId = plainFrame.packetId,
                conversationId = destination, // For 1-to-1 chat, conversation ID is the remote node ID
                senderNodeId = source,
                receiverNodeId = destination,
                body = text,
                deliveryStatus = "Queued",
                emergencyClassIndex = null,
                emergencyClassLabel = null,
                emergencyConfidence = null,
                classificationTimestamp = null,
                taxonomyVersion = null
            )
            
            traceId?.let {
                com.qualcomm.meshmind.diagnostics.ChatDeliveryDiagnostics.logEvent(it, stage = "CHAT_LOCAL_PERSIST_SUCCEEDED")
            }

            // 3. Enqueue in Priority Scheduler
            scheduler.enqueue(plainFrame)
            
            // Phase 13 Observer Tap
            try {
                val obsMeta = "{\"timestamp\":${System.currentTimeMillis()},\"stage\":\"LOCAL_ENQUEUED\",\"node\":\"$source\",\"packetId\":\"${plainFrame.packetId}\"}"
                val obsBytes = PacketSerializer.serialize(plainFrame)
                com.qualcomm.meshmind.observer.ObserverPacketTap.enqueueObservation(
                    com.qualcomm.meshmind.observer.ObserverRecord(com.qualcomm.meshmind.observer.ObserverFrameCodec.TYPE_PACKET_OBSERVATION, obsMeta, obsBytes)
                )
            } catch (ignored: Exception) {}
            traceId?.let {
                com.qualcomm.meshmind.diagnostics.ChatDeliveryDiagnostics.logEvent(it, stage = "PACKET_ENQUEUED")
            }
            MeshLogger.i(TAG, "Queued plain frame: ${plainFrame.packetId} for destination: $destination")

            // 4. Track for retransmission check
            if (!isEmergency) {
                trackRetransmission(plainFrame)
            }
            
            return@withContext SendResult.Enqueued(plainFrame.packetId)
        } catch (e: Exception) {
            MeshLogger.e(TAG, "Failed preparing message for dispatch", e)
            return@withContext SendResult.LocalPersistenceFailed(e.javaClass.simpleName)
        }
    }

    /**
     * Dedicated emergency broadcast send boundary.
     * Guaranteed to use PacketType.EMERGENCY and the established DSDV transport logic.
     */
    suspend fun sendEmergencyBroadcast(destination: String, payload: com.qualcomm.meshmind.classification.models.EmergencyBroadcastPayloadV1): SendResult = kotlinx.coroutines.withContext(managerScope.coroutineContext) {
        try {
            val payloadBytes = com.qualcomm.meshmind.classification.EmergencyBroadcastPayloadCodec.encode(payload)
            val checksum = ChecksumCalculator.calculateCrc32(payloadBytes)
            
            val identityMgr = ServiceLocator.get(DeviceIdentityManager::class.java)
            val source = identityMgr.resolveNodeId()

            val builder = PacketBuilder()
                .setSourceNodeId(source)
                .setDestinationNodeId(destination)
                .setPacketType(com.qualcomm.meshmind.packet.models.PacketType.EMERGENCY)
                .setPriority(0)
                .setPayload(payloadBytes)
                .setChecksum(checksum)
                .setPacketId(payload.emergencyId)
            
            val frame = builder.build()
            
            scheduler.enqueue(frame)
            
            // Phase 13 Observer Tap
            try {
                val obsMeta = "{\"timestamp\":${System.currentTimeMillis()},\"stage\":\"LOCAL_ENQUEUED\",\"node\":\"$source\",\"packetId\":\"${frame.packetId}\"}"
                val obsBytes = PacketSerializer.serialize(frame)
                com.qualcomm.meshmind.observer.ObserverPacketTap.enqueueObservation(
                    com.qualcomm.meshmind.observer.ObserverRecord(com.qualcomm.meshmind.observer.ObserverFrameCodec.TYPE_PACKET_OBSERVATION, obsMeta, obsBytes)
                )
            } catch (ignored: Exception) {}
            SendResult.Enqueued(frame.packetId)
        } catch (e: Exception) {
            MeshLogger.e(TAG, "Failed to send emergency broadcast", e)
            SendResult.LocalPersistenceFailed(e.javaClass.simpleName)
        }
    }

    /**
     * Dedicated application-level role announcement send boundary.
     * Uses PacketType.CONTROL and "BROADCAST" destination for bounded Phase 11A propagation.
     */
    suspend fun sendEmergencyRoleAnnouncement(payload: com.qualcomm.meshmind.classification.models.EmergencyRoleAnnouncementV1): SendResult = kotlinx.coroutines.withContext(managerScope.coroutineContext) {
        try {
            val payloadBytes = com.qualcomm.meshmind.classification.models.EmergencyRoleAnnouncementCodec.encode(payload)
            val checksum = ChecksumCalculator.calculateCrc32(payloadBytes)
            
            val builder = PacketBuilder()
                .setSourceNodeId(payload.canonicalNodeId)
                .setDestinationNodeId("BROADCAST")
                .setPacketType(com.qualcomm.meshmind.packet.models.PacketType.CONTROL)
                .setPriority(0)
                .setPayload(payloadBytes)
                .setChecksum(checksum)
                .setPacketId(payload.announcementId)
            
            val frame = builder.build()
            
            scheduler.enqueue(frame)
            SendResult.Enqueued(frame.packetId)
        } catch (e: Exception) {
            MeshLogger.e(TAG, "Failed to send emergency role announcement", e)
            SendResult.LocalPersistenceFailed(e.javaClass.simpleName)
        }
    }


    /**
     * Receives and processes raw byte arrays incoming from radios.
     */
    fun processIncomingRawBytes(rawBytes: ByteArray) {
        managerScope.launch {
            try {
                // 1. Parse into structured frame
                val rawFrame = PacketParser.parse(rawBytes)

                // Increment received counters
                try {
                    com.qualcomm.meshmind.packet.PacketManager.getInstance().incrementReceived()
                } catch (ignored: Exception) {}

                // 2. Run structural validation check
                if (!FrameValidator.validateFrame(rawFrame)) {
                    MeshLogger.e(TAG, "Discarding structurally invalid incoming frame.")
                    return@launch
                }
                
                // Replay/Duplicate validation has already been performed at the physical transport layer
                // (TransportSession.startReadLoop). Executing it here a second time mathematically guarantees
                // that all valid locally-bound payloads and ACKs are instantly rejected as duplicates.
                // Thus, we skip verifyUniqueFrame(rawFrame) here.

                when (rawFrame.packetType) {
                    PacketType.ACK -> {
                        handleAckReceived(rawFrame.packetId)
                    }
                    PacketType.DATA -> {
                        try {
                            val plainFrame = rawFrame
                            val payloadV1 = MeshChatPayloadCodec.decode(plainFrame.payload)
                            val text = payloadV1.originalText
                            
                            val canonicalSource = plainFrame.sourceNodeId.lowercase(java.util.Locale.ROOT).trim()
                            val canonicalDest = plainFrame.destinationNodeId.lowercase(java.util.Locale.ROOT).trim()
                            
                            com.qualcomm.meshmind.diagnostics.ChatDeliveryDiagnostics.logEvent(
                                traceId = rawFrame.packetId,
                                stage = "REMOTE_CONVERSATION_RESOLVE_STARTED"
                            )
                            
                            val conversationRepo = ServiceLocator.get(com.qualcomm.meshmind.repository.ConversationRepository::class.java)
                            val existingConv = conversationRepo.getConversation(canonicalSource)
                            if (existingConv != null) {
                                com.qualcomm.meshmind.diagnostics.ChatDeliveryDiagnostics.logEvent(rawFrame.packetId, stage = "REMOTE_CONVERSATION_FOUND")
                            } else {
                                conversationRepo.createConversation(
                                    conversationId = canonicalSource,
                                    title = "Neighbor $canonicalSource"
                                )
                                com.qualcomm.meshmind.diagnostics.ChatDeliveryDiagnostics.logEvent(rawFrame.packetId, stage = "REMOTE_CONVERSATION_CREATED")
                            }
                            
                            com.qualcomm.meshmind.diagnostics.ChatDeliveryDiagnostics.logEvent(rawFrame.packetId, stage = "REMOTE_MESSAGE_PERSIST_STARTED")
                            
                            val messageRepo = ServiceLocator.get(MessageRepository::class.java)
                            messageRepo.saveMessage(
                                messageId = plainFrame.packetId,
                                conversationId = canonicalSource,
                                senderNodeId = canonicalSource,
                                receiverNodeId = canonicalDest,
                                body = text,
                                deliveryStatus = "Delivered",
                                emergencyClassIndex = null,
                                emergencyClassLabel = null,
                                emergencyConfidence = null,
                                classificationTimestamp = null,
                                taxonomyVersion = null
                            )
                            
                            com.qualcomm.meshmind.diagnostics.ChatDeliveryDiagnostics.logEvent(rawFrame.packetId, stage = "REMOTE_MESSAGE_PERSIST_SUCCEEDED")
                            MeshLogger.i(TAG, "Processed incoming DATA packet: ${plainFrame.packetId}")
                            
                            sendAck(plainFrame.destinationNodeId, plainFrame.sourceNodeId, plainFrame.packetId)
                        } catch (e: Exception) {
                            MeshLogger.e(TAG, "Failed decoding incoming DATA frame", e)
                        }
                    }
                    PacketType.EMERGENCY -> {
                        try {
                            val plainFrame = rawFrame
                            val payloadV1 = com.qualcomm.meshmind.classification.EmergencyBroadcastPayloadCodec.decode(plainFrame.payload)
                            
                            if (payloadV1 != null) {
                                // TODO: Route to EmergencyBroadcastRepository / EmergencyBroadcastManager
                                MeshLogger.i(TAG, "Processed incoming EMERGENCY packet: ${payloadV1.emergencyId}")
                                
                                // Send standard ACK for emergency packet
                                sendAck(plainFrame.destinationNodeId, plainFrame.sourceNodeId, plainFrame.packetId)
                            } else {
                                MeshLogger.w(TAG, "Failed to decode valid EmergencyBroadcastPayloadV1")
                            }
                        } catch (e: Exception) {
                            MeshLogger.e(TAG, "Failed decoding incoming EMERGENCY frame", e)
                        }
                    }
                    PacketType.CONTROL -> {
                        try {
                            val plainFrame = rawFrame
                            val payloadV1 = com.qualcomm.meshmind.classification.models.EmergencyRoleAnnouncementCodec.decode(plainFrame.payload)
                            if (payloadV1 != null) {
                                val role = com.qualcomm.meshmind.classification.models.EmergencyResponseRole.valueOf(payloadV1.responseRole)
                                com.qualcomm.meshmind.classification.EmergencyResponseDirectory.updateRole(
                                    nodeId = payloadV1.canonicalNodeId,
                                    role = role,
                                    generation = payloadV1.generation
                                )
                                MeshLogger.i(TAG, "Processed incoming CONTROL role announcement: ${payloadV1.canonicalNodeId} as $role")
                            }
                        } catch (e: Exception) {
                            MeshLogger.w(TAG, "Failed to decode valid EmergencyRoleAnnouncementV1: ${e.message}")
                        }
                    }
                    PacketType.DISTANCE_VECTOR -> {
                        val engine = ServiceLocator.get(com.qualcomm.meshmind.network.routing.RoutingEngine::class.java) as? MmpRoutingEngineImpl
                        engine?.processRoutingAdvertisement(rawFrame.sourceNodeId, rawFrame.payload)
                    }
                    else -> {
                        MeshLogger.d(TAG, "Received protocol control frame: ${rawFrame.packetType}")
                    }
                }
            } catch (e: Exception) {
                MeshLogger.e(TAG, "Error processing incoming raw bytes", e)
            }
        }
    }

    private fun handleAckReceived(packetId: String) {
        pendingAcks.remove(packetId)
        retryCounts.remove(packetId)
        
        managerScope.launch {
            val messageRepo = ServiceLocator.get(MessageRepository::class.java)
            messageRepo.updateMessageStatus(packetId, "Acknowledged")
            MeshLogger.i(TAG, "ACK received. Confirmed packet: $packetId")
            
            // Phase 13 Observer Tap
            try {
                val obsMeta = "{\"timestamp\":${System.currentTimeMillis()},\"stage\":\"ACK_OBSERVED\",\"packetId\":\"$packetId\"}"
                com.qualcomm.meshmind.observer.ObserverPacketTap.enqueueObservation(
                    com.qualcomm.meshmind.observer.ObserverRecord(com.qualcomm.meshmind.observer.ObserverFrameCodec.TYPE_PACKET_OBSERVATION, obsMeta, null)
                )
            } catch (ignored: Exception) {}
        }
    }

    private fun sendAck(source: String, destination: String, originalPacketId: String) {
        managerScope.launch {
            try {
                // ACK packets carry no encrypted payload, just original packetId in the header
                val ackFrame = PacketBuilder()
                    .setPacketId(originalPacketId)
                    .setSourceNodeId(source)
                    .setDestinationNodeId(destination)
                    .setPacketType(PacketType.ACK)
                    .setPriority(1)
                    .build()

                scheduler.enqueue(ackFrame)
                MeshLogger.d(TAG, "Enqueued ACK packet back to: $destination")
            } catch (e: Exception) {
                MeshLogger.e(TAG, "Failed to compile ACK packet", e)
            }
        }
    }

    private fun trackRetransmission(frame: MeshFrame) {
        pendingAcks[frame.packetId] = frame
        retryCounts[frame.packetId] = 0
        scheduleRetries(frame.packetId)
    }

    private fun scheduleRetries(packetId: String) {
        managerScope.launch {
            var retries = 0
            while (retries < MAX_RETRIES) {
                // Exponential backoff wait delay
                val delayTime = BASE_RETRY_DELAY_MS * (1 shl retries)
                delay(delayTime)

                if (!pendingAcks.containsKey(packetId)) {
                    // Packet ACK received or cancelled
                    return@launch
                }

                retries++
                retryCounts[packetId] = retries
                MeshLogger.w(TAG, "No ACK received. Retransmitting packet: $packetId (Attempt $retries / $MAX_RETRIES)")
                
                try {
                    com.qualcomm.meshmind.packet.PacketManager.getInstance().incrementRetransmissions()
                } catch (ignored: Exception) {}

                val frame = pendingAcks[packetId]
                if (frame != null) {
                    scheduler.enqueue(frame)
                }
            }

            // After the final retry is enqueued, we must wait one final ACK window
            // before terminally declaring the packet as failed.
            val finalDelayTime = BASE_RETRY_DELAY_MS * (1 shl retries)
            delay(finalDelayTime)

            if (!pendingAcks.containsKey(packetId)) {
                return@launch
            }

            // Exceeded maximum retries limit
            handleRetriesExhausted(packetId)
        }
    }

    private fun handleRetriesExhausted(packetId: String) {
        pendingAcks.remove(packetId)
        retryCounts.remove(packetId)

        try {
            com.qualcomm.meshmind.packet.PacketManager.getInstance().incrementFailed()
        } catch (ignored: Exception) {}

        managerScope.launch {
            val messageRepo = ServiceLocator.get(MessageRepository::class.java)
            messageRepo.updateMessageStatus(packetId, "Failed")
            MeshLogger.e(TAG, "Retransmission retries exhausted. Packet failed: $packetId")
        }
    }
}
