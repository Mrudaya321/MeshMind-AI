package com.qualcomm.meshmind.network.transport

import android.content.Context
import com.qualcomm.meshmind.core.dependency.ServiceLocator
import com.qualcomm.meshmind.core.runtime.BaseSubsystem
import com.qualcomm.meshmind.diagnostics.models.SubsystemHealth
import com.qualcomm.meshmind.identity.DeviceIdentityManager
import com.qualcomm.meshmind.logging.MeshLogger
import com.qualcomm.meshmind.packet.models.MeshFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

class WifiDirectTransportManager(private val context: Context) : BaseSubsystem, TransportManager {

    private val transportScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    var isServerListening = false
        private set
    private var isListening = false
    private var isOperational = false
    private var errorCount: Long = 0
    private var lastErrorStr: String = "NONE"
    private lateinit var identityMgr: DeviceIdentityManager
    enum class ClientTransportState {
        IDLE, CONNECTING, TCP_CONNECTED, HANDSHAKING, SESSION_VERIFIED, FAILED
    }
    
    private var clientTransportState = ClientTransportState.IDLE
    private var activeClientTarget: String? = null
    private var clientConnectionJob: Job? = null
    val transportEvents = MutableSharedFlow<TransportLifecycleEvent>(extraBufferCapacity = 20)


    companion object {
        private const val TAG = "WifiDirectTransportManager"
        const val TRANSPORT_PORT = 9999
        var admissibleLogicalPeers: Set<String> = emptySet()
        var expectedCoordinatorNodeId: String? = null
    }

    override val subsystemId: String = "wifi_direct_transport_manager"
    override val initPriority: Int = 50

    override suspend fun initialize() {
        try {
            identityMgr = ServiceLocator.get(DeviceIdentityManager::class.java)
            isOperational = true
            MeshLogger.i(TAG, "Wi-Fi Direct transport manager initialized.")
        } catch (e: Exception) {
            errorCount++
            lastErrorStr = e.javaClass.simpleName
            isOperational = false
            MeshLogger.e(TAG, "Failed to initialize Wi-Fi Direct Transport Manager", e)
            throw e
        }
    }

    override fun shutdown() {
        stopListening()
        PeerSessionRegistry.getInstance().clearAll()
        isOperational = false
        MeshLogger.i(TAG, "Wi-Fi Direct transport manager shut down.")
    }

    override fun getHealth(): SubsystemHealth {
        return SubsystemHealth(
            subsystemId,
            isOperational,
            System.currentTimeMillis(),
            errorCount,
            if (isOperational) "Transport manager online (Listening: $isListening) | LastError: $lastErrorStr" else "Offline"
        )
    }

    val transmittedCount = java.util.concurrent.atomic.AtomicLong(0)

    // --- TransportManager ---
    override suspend fun sendFrame(nextHopNodeId: String, frame: MeshFrame): TransportResult {
        if (!isOperational) return TransportResult.TRANSPORT_UNAVAILABLE

        com.qualcomm.meshmind.diagnostics.ChatDeliveryDiagnostics.logEvent(
            traceId = frame.packetId,
            stage = "PEER_SESSION_LOOKUP_STARTED"
        )
        val session = PeerSessionRegistry.getInstance().getSession(nextHopNodeId)
        if (session == null) {
            com.qualcomm.meshmind.diagnostics.ChatDeliveryDiagnostics.logEvent(
                traceId = frame.packetId,
                stage = "PEER_SESSION_LOOKUP_FAILED"
            )
            MeshLogger.w(TAG, "PEER_ENDPOINT_UNRESOLVED for node: $nextHopNodeId")
            return TransportResult.PEER_UNRESOLVED
        }
        
        com.qualcomm.meshmind.diagnostics.ChatDeliveryDiagnostics.logEvent(
            traceId = frame.packetId,
            stage = "PEER_SESSION_LOOKUP_RESOLVED"
        )

        MeshLogger.d(TAG, "TRANSPORT_SEND_ATTEMPTED to $nextHopNodeId")
        com.qualcomm.meshmind.packet.PacketManager.getInstance().transportSendAttempts.incrementAndGet()
        
        val success = session.sendFrame(frame)
        if (success) {
            transmittedCount.incrementAndGet()
            com.qualcomm.meshmind.packet.PacketManager.getInstance().transportSendSuccesses.incrementAndGet()
            MeshLogger.d(TAG, "TRANSPORT_SEND_SUCCEEDED to $nextHopNodeId")
            return TransportResult.SUCCESS
        } else {
            MeshLogger.e(TAG, "SEND_FAILED to $nextHopNodeId")
            return TransportResult.SEND_FAILED
        }
    }

    override suspend fun broadcastFrame(frame: MeshFrame, excludedNodeIds: Set<String>): BroadcastTransportResult {
        if (!isOperational) {
            MeshLogger.w(TAG, "BROADCAST_TRANSPORT_RESULT: TRANSPORT_UNAVAILABLE")
            return BroadcastTransportResult(BroadcastTransportResult.BroadcastStatus.TRANSPORT_UNAVAILABLE, 0, 0, 0)
        }

        MeshLogger.i(TAG, "BROADCAST_FORWARD_REQUESTED: Type=${frame.packetType}, ID=${frame.packetId}, TTL=${frame.ttl}, PrevHop=${frame.previousHopNodeId}")
        
        val activeSessions = PeerSessionRegistry.getInstance().getActiveSessions()
        
        val localNodeId = identityMgr.resolveNodeId()
        val finalExclusions = excludedNodeIds.toMutableSet().apply {
            add(localNodeId)
            frame.previousHopNodeId?.let { add(it) }
        }
        
        val eligibleSessions = activeSessions.filterKeys { it !in finalExclusions }
        MeshLogger.d(TAG, "BROADCAST_ELIGIBLE_SESSION_COUNT: ${eligibleSessions.size}")
        
        if (eligibleSessions.isEmpty()) {
            MeshLogger.w(TAG, "BROADCAST_TRANSPORT_RESULT: NO_ELIGIBLE_SESSIONS")
            return BroadcastTransportResult(BroadcastTransportResult.BroadcastStatus.NO_ELIGIBLE_SESSIONS, 0, 0, 0)
        }

        var successCount = 0
        var failedCount = 0
        var attemptedCount = 0

        for ((peerId, session) in eligibleSessions) {
            MeshLogger.d(TAG, "BROADCAST_TRANSPORT_ATTEMPT: Sending to peer $peerId")
            attemptedCount++
            com.qualcomm.meshmind.packet.PacketManager.getInstance().transportSendAttempts.incrementAndGet()
            
            val success = session.sendFrame(frame)
            if (success) {
                successCount++
                transmittedCount.incrementAndGet()
                com.qualcomm.meshmind.packet.PacketManager.getInstance().transportSendSuccesses.incrementAndGet()
                MeshLogger.d(TAG, "BROADCAST_TRANSPORT_SUCCESS: to peer $peerId")
            } else {
                failedCount++
                MeshLogger.e(TAG, "BROADCAST_TRANSPORT_FAILED: to peer $peerId")
            }
        }

        val status = when {
            successCount == attemptedCount -> BroadcastTransportResult.BroadcastStatus.ALL_SENDS_SUCCEEDED
            successCount == 0 -> BroadcastTransportResult.BroadcastStatus.ALL_SENDS_FAILED
            else -> BroadcastTransportResult.BroadcastStatus.PARTIAL_SUCCESS
        }

        MeshLogger.i(TAG, "BROADCAST_TRANSPORT_RESULT: status=$status, attempted=$attemptedCount, success=$successCount, failed=$failedCount")
        return BroadcastTransportResult(status, attemptedCount, successCount, failedCount)
    }

    override fun startListening() {
        if (isListening) return
        isListening = true
        
        transportScope.launch {
            try {
                MeshLogger.i(TAG, "TCP_SERVER_START_REQUESTED: Binding server socket to port $TRANSPORT_PORT")
                serverSocket = ServerSocket(TRANSPORT_PORT)
                isServerListening = true
                MeshLogger.i(TAG, "TCP_SERVER_LISTENING: TCP socket listener online on port: $TRANSPORT_PORT")
                
                while (isActive && !serverSocket!!.isClosed) {
                    val clientSocket = serverSocket!!.accept()
                    launch {
                        handleIncomingConnection(clientSocket)
                    }
                }
            } catch (e: Exception) {
                if (isListening) {
                    errorCount++
                    lastErrorStr = e.javaClass.simpleName
                    MeshLogger.e(TAG, "TCP_SERVER_START_FAILED: Server socket listener crashed on port: $TRANSPORT_PORT", e)
                }
            }
        }
    }

    override fun stopListening() {
        isListening = false
        try {
            serverSocket?.close()
            serverSocket = null
            isServerListening = false
            MeshLogger.i(TAG, "TCP socket listener closed.")
        } catch (e: Exception) {
            MeshLogger.e(TAG, "Error closing server socket listener", e)
        }
    }

    private suspend fun handleIncomingConnection(socket: Socket) = withContext(Dispatchers.IO) {
        MeshLogger.i(TAG, "TCP_SESSION_ESTABLISHED (Server): connection from ${socket.inetAddress.hostAddress}")
        try {
            socket.soTimeout = 10000 // Handshake timeout 10 seconds
            
            val dis = DataInputStream(socket.getInputStream())
            val dos = DataOutputStream(socket.getOutputStream())
            
            val localNodeId = identityMgr.resolveNodeId()
            val peerCrypto = PeerSessionCrypto()
            
            MeshLogger.i(TAG, "TRANSPORT_HANDSHAKE_SERVER_STARTED")
            transportEvents.tryEmit(TransportLifecycleEvent.HandshakeStarted(socket.inetAddress?.hostAddress ?: "unknown", true))
            val handshakeResult = TransportHandshake.readHandshake(dis, localNodeId)
            val remoteNodeId = handshakeResult.remoteNodeId
            transportEvents.tryEmit(TransportLifecycleEvent.HandshakeRemoteIdReceived(remoteNodeId))
            
            if (remoteNodeId !in admissibleLogicalPeers) {
                MeshLogger.e(TAG, "IDENTITY_ASSOCIATION_MISMATCH: Server expected one of admissible peers ${admissibleLogicalPeers}, but handshake verified remote node as $remoteNodeId. Rejecting session.")
                socket.close()
                // Do not blindly tear down the server group just because an unknown peer connected
                return@withContext
            }

            // Valid coordinator admission
            MeshLogger.i(TAG, "HANDSHAKE_PEER_ADMITTED: Coordinator admitted logical peer $remoteNodeId")

            TransportHandshake.writeHandshake(dos, localNodeId, peerCrypto.getPublicKey())
            
            com.qualcomm.meshmind.diagnostics.ChatDeliveryDiagnostics.logEvent(
                traceId = "session_$remoteNodeId",
                stage = "SESSION_CRYPTO_LOOKUP_STARTED"
            )
            peerCrypto.deriveSessionKey(handshakeResult.remotePublicKey, localNodeId, remoteNodeId)
            transportEvents.tryEmit(TransportLifecycleEvent.SessionKeyDerived(remoteNodeId))
            com.qualcomm.meshmind.diagnostics.ChatDeliveryDiagnostics.logEvent(
                traceId = "session_$remoteNodeId",
                stage = "SESSION_KEY_DERIVED",
                terminalReason = "fingerprint=${peerCrypto.getSessionKeyFingerprint()}"
            )
            
            socket.soTimeout = 0 // Remove timeout for sustained session
            
            MeshLogger.i(TAG, "PEER_SESSION_REGISTER_REQUESTED: Server registering $remoteNodeId")
            transportEvents.tryEmit(TransportLifecycleEvent.PeerSessionRegisterStarted(remoteNodeId))
            val session = TransportSession(remoteNodeId, socket, peerCrypto)
            PeerSessionRegistry.getInstance().registerSession(remoteNodeId, session)
            transportEvents.tryEmit(TransportLifecycleEvent.PeerSessionRegistered(remoteNodeId))
            MeshLogger.i(TAG, "PEER_SESSION_REGISTERED: Server successfully registered $remoteNodeId")
            
            session.startReadLoop()
        } catch (e: Exception) {
            errorCount++
            lastErrorStr = "ServerHandshakeFailed: ${e.javaClass.simpleName}"
            MeshLogger.e(TAG, "Failed incoming TCP handshake", e)
            try { socket.close() } catch (ignored: Exception) {}
        }
    }

    fun connectToGroupOwner(ipAddress: String) {
        synchronized(this) {
            if (activeClientTarget == ipAddress && 
                (clientTransportState == ClientTransportState.CONNECTING ||
                 clientTransportState == ClientTransportState.TCP_CONNECTED ||
                 clientTransportState == ClientTransportState.HANDSHAKING ||
                 clientTransportState == ClientTransportState.SESSION_VERIFIED)) {
                MeshLogger.w(TAG, "DUPLICATE_TCP_CONNECT_SUPPRESSED: Already processing connection to $ipAddress in state $clientTransportState")
                com.qualcomm.meshmind.diagnostics.ChatDeliveryDiagnostics.logEvent(traceId = "tcp_connect", stage = "DUPLICATE_TCP_CONNECT_SUPPRESSED")
                return
            }
            activeClientTarget = ipAddress
            clientTransportState = ClientTransportState.CONNECTING
        }

        clientConnectionJob?.cancel()
        clientConnectionJob = transportScope.launch {
            transportEvents.tryEmit(TransportLifecycleEvent.TcpClientConnectStarted(ipAddress))
            var socket: Socket? = null
            var attempt = 0
            val maxAttempts = 5
            
            while (attempt < maxAttempts && socket == null && isActive) {
                try {
                    MeshLogger.i(TAG, "TCP_CLIENT_CONNECT_REQUESTED (Attempt ${attempt + 1}): Connecting to TCP server at $ipAddress:$TRANSPORT_PORT")
                    socket = Socket()
                    socket.connect(java.net.InetSocketAddress(ipAddress, TRANSPORT_PORT), 5000)
                } catch (e: Exception) {
                    attempt++
                    MeshLogger.w(TAG, "TCP_CLIENT_CONNECT_FAILED (Attempt $attempt): ${e.javaClass.simpleName} - ${e.message}")
                    socket = null
                    if (attempt < maxAttempts) {
                        delay(2000)
                    } else {
                        errorCount++
                        lastErrorStr = "ClientConnectFailed: ${e.javaClass.simpleName}"
                        MeshLogger.e(TAG, "TCP_CLIENT_CONNECT_FAILED: Exhausted all TCP connection attempts to $ipAddress", e)
                        synchronized(this@WifiDirectTransportManager) { clientTransportState = ClientTransportState.FAILED }
                        transportEvents.tryEmit(TransportLifecycleEvent.TransportFailure(ipAddress, null, "Exhausted connection attempts"))
                        return@launch
                    }
                }
            }

            if (socket == null) return@launch

            synchronized(this@WifiDirectTransportManager) { clientTransportState = ClientTransportState.TCP_CONNECTED }
            transportEvents.tryEmit(TransportLifecycleEvent.TcpClientSocketConnected(ipAddress))
            MeshLogger.i(TAG, "TCP_CLIENT_CONNECT_SUCCEEDED: TCP_SESSION_ESTABLISHED (Client) connected to $ipAddress")

            try {
                socket.soTimeout = 10000 // Handshake timeout 10 seconds
                
                val dos = DataOutputStream(socket.getOutputStream())
                val dis = DataInputStream(socket.getInputStream())

                val localNodeId = identityMgr.resolveNodeId()
                val peerCrypto = PeerSessionCrypto()
                
                synchronized(this@WifiDirectTransportManager) { clientTransportState = ClientTransportState.HANDSHAKING }
                transportEvents.tryEmit(TransportLifecycleEvent.HandshakeStarted(ipAddress, false))
                MeshLogger.i(TAG, "TRANSPORT_HANDSHAKE_CLIENT_STARTED")
                TransportHandshake.writeHandshake(dos, localNodeId, peerCrypto.getPublicKey())
                
                val handshakeResult = TransportHandshake.readHandshake(dis, localNodeId)
                val remoteNodeId = handshakeResult.remoteNodeId
                transportEvents.tryEmit(TransportLifecycleEvent.HandshakeRemoteIdReceived(remoteNodeId))
                
                if (expectedCoordinatorNodeId != null && remoteNodeId != expectedCoordinatorNodeId) {
                    MeshLogger.e(TAG, "COORDINATOR_IDENTITY_MISMATCH: Member expected coordinator $expectedCoordinatorNodeId, but handshake verified remote node as $remoteNodeId. Rejecting session.")
                    socket.close()
                    synchronized(this@WifiDirectTransportManager) { clientTransportState = ClientTransportState.FAILED }
                    transportEvents.tryEmit(TransportLifecycleEvent.TransportFailure(ipAddress, remoteNodeId, "IDENTITY_MISMATCH"))
                    val connMgr = ServiceLocator.get(WifiDirectConnectionManager::class.java)
                    connMgr.handleIdentityMismatch()
                    return@launch
                }

                MeshLogger.i(TAG, "HANDSHAKE_PEER_ADMITTED: Member verified connection to coordinator $remoteNodeId")

                com.qualcomm.meshmind.diagnostics.ChatDeliveryDiagnostics.logEvent(
                    traceId = "session_$remoteNodeId",
                    stage = "SESSION_CRYPTO_LOOKUP_STARTED"
                )
                peerCrypto.deriveSessionKey(handshakeResult.remotePublicKey, localNodeId, remoteNodeId)
                transportEvents.tryEmit(TransportLifecycleEvent.SessionKeyDerived(remoteNodeId))
                com.qualcomm.meshmind.diagnostics.ChatDeliveryDiagnostics.logEvent(
                    traceId = "session_$remoteNodeId",
                    stage = "SESSION_KEY_DERIVED",
                    terminalReason = "fingerprint=${peerCrypto.getSessionKeyFingerprint()}"
                )

                socket.soTimeout = 0 // Remove timeout for sustained session
                
                MeshLogger.i(TAG, "PEER_SESSION_REGISTER_REQUESTED: Client registering $remoteNodeId")
                transportEvents.tryEmit(TransportLifecycleEvent.PeerSessionRegisterStarted(remoteNodeId))
                val session = TransportSession(remoteNodeId, socket, peerCrypto)
                PeerSessionRegistry.getInstance().registerSession(remoteNodeId, session)
                transportEvents.tryEmit(TransportLifecycleEvent.PeerSessionRegistered(remoteNodeId))
                synchronized(this@WifiDirectTransportManager) { clientTransportState = ClientTransportState.SESSION_VERIFIED }
                MeshLogger.i(TAG, "PEER_SESSION_REGISTERED: Client successfully registered $remoteNodeId")
                
                session.startReadLoop()

            } catch (e: Exception) {
                errorCount++
                lastErrorStr = "ClientHandshakeFailed: ${e.javaClass.simpleName}"
                MeshLogger.e(TAG, "TCP_CLIENT_CONNECT_FAILED: Handshake failed", e)
                synchronized(this@WifiDirectTransportManager) { clientTransportState = ClientTransportState.FAILED }
                transportEvents.tryEmit(TransportLifecycleEvent.TransportFailure(ipAddress, null, "HandshakeException: ${e.javaClass.simpleName}"))
                try { socket?.close() } catch (ignored: Exception) {}
            }
        }
    }
}
