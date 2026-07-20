import re

with open(r'D:\meshmind\meshmind\app\src\main\java\com\qualcomm\meshmind\network\transport\WifiDirectTransportManager.kt', 'r') as f:
    content = f.read()

# Add imports
imports = """import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.Job
"""
content = content.replace('import kotlinx.coroutines.withContext\n', 'import kotlinx.coroutines.withContext\n' + imports)

# Add enum and states
states = """
    enum class ClientTransportState {
        IDLE, CONNECTING, TCP_CONNECTED, HANDSHAKING, SESSION_VERIFIED, FAILED
    }
    
    private var clientTransportState = ClientTransportState.IDLE
    private var activeClientTarget: String? = null
    private var clientConnectionJob: Job? = null
    val transportEvents = MutableSharedFlow<TransportLifecycleEvent>(extraBufferCapacity = 20)
"""
content = content.replace('private lateinit var identityMgr: DeviceIdentityManager', 'private lateinit var identityMgr: DeviceIdentityManager' + states)

# Update server handshake
server_start = 'MeshLogger.i(TAG, "TRANSPORT_HANDSHAKE_SERVER_STARTED")'
server_start_new = 'MeshLogger.i(TAG, "TRANSPORT_HANDSHAKE_SERVER_STARTED")\n            transportEvents.tryEmit(TransportLifecycleEvent.HandshakeStarted(socket.inetAddress.hostAddress, true))'
content = content.replace(server_start, server_start_new)

server_remote = 'val remoteNodeId = handshakeResult.remoteNodeId'
server_remote_new = 'val remoteNodeId = handshakeResult.remoteNodeId\n            transportEvents.tryEmit(TransportLifecycleEvent.HandshakeRemoteIdReceived(remoteNodeId))'
content = content.replace(server_remote, server_remote_new)

server_derive = 'peerCrypto.deriveSessionKey(handshakeResult.remotePublicKey, localNodeId, remoteNodeId)'
server_derive_new = 'peerCrypto.deriveSessionKey(handshakeResult.remotePublicKey, localNodeId, remoteNodeId)\n            transportEvents.tryEmit(TransportLifecycleEvent.SessionKeyDerived(remoteNodeId))'
content = content.replace(server_derive, server_derive_new)

server_register = 'val session = TransportSession(remoteNodeId, socket, peerCrypto)'
server_register_new = 'transportEvents.tryEmit(TransportLifecycleEvent.PeerSessionRegisterStarted(remoteNodeId))\n            val session = TransportSession(remoteNodeId, socket, peerCrypto)'
content = content.replace(server_register, server_register_new)

server_registered = 'PeerSessionRegistry.getInstance().registerSession(remoteNodeId, session)'
server_registered_new = 'PeerSessionRegistry.getInstance().registerSession(remoteNodeId, session)\n            transportEvents.tryEmit(TransportLifecycleEvent.PeerSessionRegistered(remoteNodeId))'
content = content.replace(server_registered, server_registered_new)

# Update client connectToGroupOwner
client_fun = """    fun connectToGroupOwner(ipAddress: String) {
        transportScope.launch {"""
client_fun_new = """    fun connectToGroupOwner(ipAddress: String) {
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
            transportEvents.tryEmit(TransportLifecycleEvent.TcpClientConnectStarted(ipAddress))"""
content = content.replace(client_fun, client_fun_new)

client_exhausted = """                        errorCount++
                        lastErrorStr = "ClientConnectFailed: ${e.javaClass.simpleName}"
                        MeshLogger.e(TAG, "TCP_CLIENT_CONNECT_FAILED: Exhausted all TCP connection attempts to $ipAddress", e)
                        return@launch"""
client_exhausted_new = """                        errorCount++
                        lastErrorStr = "ClientConnectFailed: ${e.javaClass.simpleName}"
                        MeshLogger.e(TAG, "TCP_CLIENT_CONNECT_FAILED: Exhausted all TCP connection attempts to $ipAddress", e)
                        synchronized(this@WifiDirectTransportManager) { clientTransportState = ClientTransportState.FAILED }
                        transportEvents.tryEmit(TransportLifecycleEvent.TransportFailure(ipAddress, null, "Exhausted connection attempts"))
                        return@launch"""
content = content.replace(client_exhausted, client_exhausted_new)

client_connected = 'MeshLogger.i(TAG, "TCP_CLIENT_CONNECT_SUCCEEDED: TCP_SESSION_ESTABLISHED (Client) connected to $ipAddress")'
client_connected_new = 'synchronized(this@WifiDirectTransportManager) { clientTransportState = ClientTransportState.TCP_CONNECTED }\n            transportEvents.tryEmit(TransportLifecycleEvent.TcpClientSocketConnected(ipAddress))\n            ' + client_connected
content = content.replace(client_connected, client_connected_new)

client_handshake_start = 'MeshLogger.i(TAG, "TRANSPORT_HANDSHAKE_CLIENT_STARTED")'
client_handshake_start_new = 'synchronized(this@WifiDirectTransportManager) { clientTransportState = ClientTransportState.HANDSHAKING }\n                transportEvents.tryEmit(TransportLifecycleEvent.HandshakeStarted(ipAddress, false))\n                ' + client_handshake_start
content = content.replace(client_handshake_start, client_handshake_start_new)

client_remote = 'val remoteNodeId = handshakeResult.remoteNodeId'
client_remote_new = 'val remoteNodeId = handshakeResult.remoteNodeId\n                transportEvents.tryEmit(TransportLifecycleEvent.HandshakeRemoteIdReceived(remoteNodeId))'
content = content.replace(client_remote, client_remote_new)

client_mismatch = """                    socket.close()
                    val connMgr = ServiceLocator.get(WifiDirectConnectionManager::class.java)
                    connMgr.handleIdentityMismatch()
                    return@launch"""
client_mismatch_new = """                    socket.close()
                    synchronized(this@WifiDirectTransportManager) { clientTransportState = ClientTransportState.FAILED }
                    transportEvents.tryEmit(TransportLifecycleEvent.TransportFailure(ipAddress, remoteNodeId, "IDENTITY_MISMATCH"))
                    val connMgr = ServiceLocator.get(WifiDirectConnectionManager::class.java)
                    connMgr.handleIdentityMismatch()
                    return@launch"""
content = content.replace(client_mismatch, client_mismatch_new)

client_derive = 'peerCrypto.deriveSessionKey(handshakeResult.remotePublicKey, localNodeId, remoteNodeId)'
client_derive_new = 'peerCrypto.deriveSessionKey(handshakeResult.remotePublicKey, localNodeId, remoteNodeId)\n                transportEvents.tryEmit(TransportLifecycleEvent.SessionKeyDerived(remoteNodeId))'
content = content.replace(client_derive, client_derive_new)

client_register = 'val session = TransportSession(remoteNodeId, socket, peerCrypto)'
client_register_new = 'transportEvents.tryEmit(TransportLifecycleEvent.PeerSessionRegisterStarted(remoteNodeId))\n                val session = TransportSession(remoteNodeId, socket, peerCrypto)'
content = content.replace(client_register, client_register_new)

client_registered = 'PeerSessionRegistry.getInstance().registerSession(remoteNodeId, session)'
client_registered_new = 'PeerSessionRegistry.getInstance().registerSession(remoteNodeId, session)\n                transportEvents.tryEmit(TransportLifecycleEvent.PeerSessionRegistered(remoteNodeId))\n                synchronized(this@WifiDirectTransportManager) { clientTransportState = ClientTransportState.SESSION_VERIFIED }'
content = content.replace(client_registered, client_registered_new)

client_catch = """                errorCount++
                lastErrorStr = "ClientHandshakeFailed: ${e.javaClass.simpleName}"
                MeshLogger.e(TAG, "TCP_CLIENT_CONNECT_FAILED: Handshake failed", e)
                try { socket?.close() } catch (ignored: Exception) {}"""
client_catch_new = """                errorCount++
                lastErrorStr = "ClientHandshakeFailed: ${e.javaClass.simpleName}"
                MeshLogger.e(TAG, "TCP_CLIENT_CONNECT_FAILED: Handshake failed", e)
                synchronized(this@WifiDirectTransportManager) { clientTransportState = ClientTransportState.FAILED }
                transportEvents.tryEmit(TransportLifecycleEvent.TransportFailure(ipAddress, null, "HandshakeException: ${e.javaClass.simpleName}"))
                try { socket?.close() } catch (ignored: Exception) {}"""
content = content.replace(client_catch, client_catch_new)

with open(r'D:\meshmind\meshmind\app\src\main\java\com\qualcomm\meshmind\network\transport\WifiDirectTransportManager.kt', 'w') as f:
    f.write(content)

print("Done")
