import re

file_path = r'D:\meshmind\meshmind\app\src\main\java\com\qualcomm\meshmind\network\transport\WifiDirectConnectionManager.kt'
with open(file_path, 'r') as f:
    content = f.read()

# 1. Remove tcpStageReached
content = content.replace('var tcpStageReached: Boolean = false,\n    ', '')
content = content.replace(' GROUP=$groupFormed TCP=$tcpStageReached FINAL=$finalOutcome', ' GROUP=$groupFormed FINAL=$finalOutcome')

# 2. Add transportEventJob
content = content.replace('private var discoveryRetryJob: Job? = null\n', 'private var discoveryRetryJob: Job? = null\n    private var transportEventJob: Job? = null\n')

# 3. Add transportEvent collector in initialize()
init_str = """            checkStaleGroupAndRecover()
            observeNeighborState()"""
init_str_new = """            checkStaleGroupAndRecover()
            observeNeighborState()
            observeTransportEvents()"""
content = content.replace(init_str, init_str_new)

# 4. Add observeTransportEvents()
observe_method = """
    private fun observeTransportEvents() {
        transportEventJob?.cancel()
        val transportMgr = ServiceLocator.get(WifiDirectTransportManager::class.java)
        transportEventJob = connectionScope.launch {
            transportMgr.transportEvents.collect { event ->
                when (event) {
                    is TransportLifecycleEvent.TcpClientConnectStarted -> {
                        if (connectionState == ConnectionState.WAITING_FOR_GROUP_FORMATION || connectionState == ConnectionState.GROUP_JOINED) {
                            connectionState = ConnectionState.TCP_CONNECTING
                            logDiagnosticEvent("TCP_CLIENT_CONNECT_STARTED")
                        }
                    }
                    is TransportLifecycleEvent.TcpClientSocketConnected -> {
                        if (connectionState == ConnectionState.TCP_CONNECTING) {
                            connectionState = ConnectionState.TCP_CONNECTED
                            logDiagnosticEvent("TCP_CLIENT_SOCKET_CONNECTED")
                        }
                    }
                    is TransportLifecycleEvent.HandshakeStarted -> {
                        if (localClusterRole == "MEMBER" && (connectionState == ConnectionState.TCP_CONNECTED || connectionState == ConnectionState.TCP_CONNECTING)) {
                            connectionState = ConnectionState.HANDSHAKE_PENDING
                            logDiagnosticEvent("HANDSHAKE_STARTED")
                        } else if (localClusterRole == "COORDINATOR") {
                            logDiagnosticEvent("HANDSHAKE_STARTED")
                        }
                    }
                    is TransportLifecycleEvent.HandshakeRemoteIdReceived -> {
                        logDiagnosticEvent("HANDSHAKE_REMOTE_ID_RECEIVED")
                    }
                    is TransportLifecycleEvent.SessionKeyDerived -> {
                        logDiagnosticEvent("SESSION_KEY_DERIVED")
                    }
                    is TransportLifecycleEvent.PeerSessionRegisterStarted -> {
                        logDiagnosticEvent("PEER_SESSION_REGISTER_STARTED")
                    }
                    is TransportLifecycleEvent.PeerSessionRegistered -> {
                        logDiagnosticEvent("PEER_SESSION_REGISTERED")
                    }
                    is TransportLifecycleEvent.TransportFailure -> {
                        val gen = activeAttemptGeneration
                        if (localClusterRole == "MEMBER" && gen != null) {
                            currentAttemptRecord?.finalOutcome = "TRANSPORT_FAILURE: ${event.reason}"
                        }
                    }
                }
            }
        }
    }
"""
content = content.replace('private fun registerReceiver() {', observe_method + '\n    private fun registerReceiver() {')

# 5. Add transportEventJob to shutdown()
content = content.replace('discoveryRetryJob?.cancel()', 'discoveryRetryJob?.cancel()\n        transportEventJob?.cancel()')

# 6. Remove tcpStageReached writes in requestConnectionInfo
content = content.replace('currentAttemptRecord?.tcpStageReached = true\n                    transportMgr.startListening()', 'transportMgr.startListening()')
content = content.replace('currentAttemptRecord?.tcpStageReached = true\n                        transportMgr.connectToGroupOwner(groupOwnerAddress)', 'transportMgr.connectToGroupOwner(groupOwnerAddress)')

# 7. Update canonicalization in startMeshBuildCycle
# The code already uses `lowercase(Locale.ROOT)`, but let's use the new `canonicalNodeId`
content = content.replace('it.nodeId.lowercase(Locale.ROOT).trim()', 'com.qualcomm.meshmind.identity.DeviceIdentityManager.canonicalNodeId(it.nodeId)')
content = content.replace('(identityMgr.getCachedNodeId() ?: "unknown_node").lowercase(Locale.ROOT).trim()', 'com.qualcomm.meshmind.identity.DeviceIdentityManager.canonicalNodeId(identityMgr.getCachedNodeId() ?: "unknown_node")')

# 8. Add ConnectionState.TCP_CONNECTED
content = content.replace('TCP_CONNECTING, HANDSHAKE_PENDING', 'TCP_CONNECTING, TCP_CONNECTED, HANDSHAKE_PENDING')

# 9. Update executeConnectionAttempt wait loops
exec_attempt_old = """            connectionState = ConnectionState.WAITING_FOR_GROUP_FORMATION
            val tcpStageReached = waitForConditionOrTimeout(10000L) {
                currentAttemptRecord?.tcpStageReached == true || currentAttemptRecord?.finalOutcome == "GROUP_FORMED_FALSE_AFTER_BROADCAST" || currentAttemptRecord?.finalOutcome == "IDENTITY_ASSOCIATION_MISMATCH"
            }
            
            if (currentAttemptRecord?.finalOutcome == "GROUP_FORMED_FALSE_AFTER_BROADCAST") {
                failActiveCandidate(generation, "GROUP_FORMED_FALSE_AFTER_BROADCAST")
                return@launch
            }
            
            if (currentAttemptRecord?.finalOutcome == "IDENTITY_ASSOCIATION_MISMATCH") {
                failActiveCandidate(generation, "COORDINATOR_IDENTITY_MISMATCH")
                return@launch
            }
            
            if (!tcpStageReached) {
                failActiveCandidate(generation, "NEGOTIATION_TIMEOUT_NO_TCP_STAGE")
                return@launch
            }
            
            connectionState = ConnectionState.HANDSHAKE_PENDING
            val sessionVerified = waitForConditionOrTimeout(10000L) {
                if (currentAttemptRecord?.finalOutcome == "IDENTITY_ASSOCIATION_MISMATCH") return@waitForConditionOrTimeout true
                val activeSessionCount = com.qualcomm.meshmind.network.transport.PeerSessionRegistry.getInstance().getActiveSessionCount()
                activeSessionCount > 0
            }
            
            if (currentAttemptRecord?.finalOutcome == "IDENTITY_ASSOCIATION_MISMATCH") {
                failActiveCandidate(generation, "COORDINATOR_IDENTITY_MISMATCH")
                return@launch
            }
            
            if (!sessionVerified) {
                failActiveCandidate(generation, "NEGOTIATION_TIMEOUT_NO_TRANSPORT_SESSION")
                return@launch
            }
            
            val verifiedSession = com.qualcomm.meshmind.network.transport.PeerSessionRegistry.getInstance().getSession(electedCoordinatorNodeId ?: "")
            if (verifiedSession != null && verifiedSession.isActive()) {
                currentAttemptRecord?.finalOutcome = "SESSION_VERIFIED"
                connectionState = ConnectionState.SESSION_VERIFIED
                logDiagnosticEvent("COORDINATOR_SESSION_VERIFIED")"""

exec_attempt_new = """            connectionState = ConnectionState.WAITING_FOR_GROUP_FORMATION
            
            logDiagnosticEvent("EXPECTED_COORDINATOR_SESSION_WAIT_STARTED")
            
            val sessionVerified = waitForConditionOrTimeout(30000L) {
                if (currentAttemptRecord?.finalOutcome?.startsWith("TRANSPORT_FAILURE") == true) return@waitForConditionOrTimeout true
                if (currentAttemptRecord?.finalOutcome == "GROUP_FORMED_FALSE_AFTER_BROADCAST") return@waitForConditionOrTimeout true
                if (currentAttemptRecord?.finalOutcome == "IDENTITY_ASSOCIATION_MISMATCH") return@waitForConditionOrTimeout true
                
                val verifiedSession = com.qualcomm.meshmind.network.transport.PeerSessionRegistry.getInstance().getSession(electedCoordinatorNodeId ?: "")
                verifiedSession?.isActive() == true
            }
            
            if (currentAttemptRecord?.finalOutcome == "GROUP_FORMED_FALSE_AFTER_BROADCAST") {
                failActiveCandidate(generation, "GROUP_FORMED_FALSE_AFTER_BROADCAST")
                return@launch
            }
            
            if (currentAttemptRecord?.finalOutcome == "IDENTITY_ASSOCIATION_MISMATCH") {
                failActiveCandidate(generation, "COORDINATOR_IDENTITY_MISMATCH")
                return@launch
            }
            
            if (currentAttemptRecord?.finalOutcome?.startsWith("TRANSPORT_FAILURE") == true) {
                failActiveCandidate(generation, currentAttemptRecord?.finalOutcome ?: "TRANSPORT_FAILURE")
                return@launch
            }
            
            if (!sessionVerified) {
                failActiveCandidate(generation, "NEGOTIATION_TIMEOUT_NO_TRANSPORT_SESSION")
                return@launch
            }
            
            val verifiedSession = com.qualcomm.meshmind.network.transport.PeerSessionRegistry.getInstance().getSession(electedCoordinatorNodeId ?: "")
            if (verifiedSession != null && verifiedSession.isActive()) {
                currentAttemptRecord?.finalOutcome = "SESSION_VERIFIED"
                connectionState = ConnectionState.SESSION_VERIFIED
                logDiagnosticEvent("EXPECTED_COORDINATOR_SESSION_VERIFIED")"""

content = content.replace(exec_attempt_old, exec_attempt_new)

with open(file_path, 'w') as f:
    f.write(content)

print("Done")
