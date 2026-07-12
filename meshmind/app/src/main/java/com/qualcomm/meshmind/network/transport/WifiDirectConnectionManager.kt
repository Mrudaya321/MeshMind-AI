package com.qualcomm.meshmind.network.transport

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.qualcomm.meshmind.core.dependency.ServiceLocator
import com.qualcomm.meshmind.core.runtime.BaseSubsystem
import com.qualcomm.meshmind.diagnostics.models.SubsystemHealth
import com.qualcomm.meshmind.identity.DeviceIdentityManager
import com.qualcomm.meshmind.logging.MeshLogger
import com.qualcomm.meshmind.state.NeighborStateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentLinkedQueue
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import java.util.concurrent.ConcurrentHashMap
import java.text.SimpleDateFormat
import java.util.Date

data class DiagnosticEvent(
    val timestamp: Long,
    val cycleId: Long,
    val state: String,
    val eventName: String,
    val candidateMac: String?,
    val attemptedCount: Int,
    val peerCount: Int,
    val hasActiveTimeoutJob: Boolean
) {
    override fun toString(): String {
        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        val timeStr = sdf.format(Date(timestamp))
        return "[$timeStr] Cycle:$cycleId | $eventName | State:$state | Cand:${candidateMac ?: "NONE"} | Attempted:$attemptedCount | Peers:$peerCount | TimeoutJob:$hasActiveTimeoutJob"
    }
}

data class DiscoveredMeshService(
    val deviceAddress: String,
    var device: WifiP2pDevice? = null,
    var instanceName: String? = null,
    var registrationType: String? = null,
    var nodeId: String? = null,
    var protocolVersion: Int? = null,
    var transportPort: Int? = null,
    var discoveryCycleId: Long = 0
)

data class CandidateAttemptRecord(
    val connectionCycleId: Long,
    val deviceAddress: String,
    val deviceName: String?,
    val attemptSeq: Int,
    val startElapsedRealtime: Long,
    var apiResult: String? = null,
    var connectedBroadcastReceived: Boolean = false,
    var groupFormed: Boolean = false,
    var finalOutcome: String = "PENDING"
) {
    fun getFormattedLine(): String {
        return "#$attemptSeq ${deviceAddress.take(8)}... API=${apiResult ?: "WAITING"} CONNECTED=$connectedBroadcastReceived GROUP=$groupFormed FINAL=$finalOutcome"
    }
}

class WifiDirectConnectionManager(private val context: Context) : BaseSubsystem {

    private val manager: WifiP2pManager? by lazy {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    }
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null

    private val connectionScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isOperational = false
    private var errorCount: Long = 0
    private var lastErrorStr: String = "NONE"

    private val peers = mutableListOf<WifiP2pDevice>()
    
    // Probing state
    private val candidateQueue = mutableListOf<WifiP2pDevice>()
    private val attemptedCandidates = mutableSetOf<String>()
    var currentProbingCandidate: WifiP2pDevice? = null
        private set
        
    var connectionState = ConnectionState.IDLE
        private set
    var isDiscovering = false
        private set
    var isGroupOwner = false
        private set
    var isConnected = false
        private set
        
    private var activeConnectionJob: Job? = null
    private var hasAttemptedStaleGroupRecovery = false
    private val isReconciling = AtomicBoolean(false)
    private var connectionAttemptGeneration: Long = 0L
    private var activeAttemptGeneration: Long? = null
    private var discoveryRetryJob: Job? = null
    private var transportEventJob: Job? = null
    private val discoveredServices = ConcurrentHashMap<String, DiscoveredMeshService>()
    private var serviceDiscoveryRetryJob: Job? = null


    
    // Explicit Build Cycle State
    var connectionCycleId: Long = 0
        private set
    var connectRequestAccepted = false
        private set
        
    var targetLogicalPeers: Set<String> = emptySet()
        private set
        
    var electedCoordinatorNodeId: String? = null
        private set
        
    var localClusterRole: String = "NONE"
        private set
        
    val diagnosticEvents = ConcurrentLinkedQueue<DiagnosticEvent>()
    val candidateAttemptRecords = ConcurrentLinkedQueue<CandidateAttemptRecord>()

    fun getDiscoveredServicesCount(): Int = discoveredServices.size

    private var currentAttemptRecord: CandidateAttemptRecord? = null
    private var attemptSequence = 0

    enum class ConnectionState {
        IDLE, DISCOVERING, PHYSICAL_CANDIDATE_AMBIGUOUS, CONNECT_REQUEST_SUBMITTED, CONNECT_REQUEST_ACCEPTED, WAITING_FOR_GROUP_FORMATION, GROUP_JOINED, TCP_CONNECTING, TCP_CONNECTED, HANDSHAKE_PENDING, SESSION_VERIFIED, CLEANUP_STARTED, CLEANUP_COMPLETED, ATTEMPT_FAILED, GROUP_CREATE_STARTED, GROUP_CREATE_REQUEST_ACCEPTED, GROUP_FORMED, GROUP_REMOVING, ACCEPTING_MEMBERS, MESH_OPERATIONAL, FAILED, CANDIDATES_EXHAUSTED, WAITING_FOR_ELECTED_INITIATOR, SERVICE_ADVERTISEMENT_STARTING, SERVICE_ADVERTISEMENT_ACTIVE, SERVICE_DISCOVERING, CANDIDATE_SELECTED, COORDINATOR_SERVICE_FOUND
    }

    enum class BuildCycleResult {
        ALREADY_CONNECTED,
        NO_ACTIVE_LOGICAL_PEERS,
        BUILD_ALREADY_IN_PROGRESS,
        STARTED
    }

    companion object {
        private const val TAG = "WifiDirectConnectionMgr"
        private const val ACTIVE_NEIGHBOR_CUTOFF_MS = 15000L
        const val DNS_SD_SERVICE_INSTANCE_NAME = "MeshMind"
        const val DNS_SD_SERVICE_REGISTRATION_TYPE = "_meshmind._tcp"
        const val DNS_SD_PROTOCOL_VERSION = 1

    }

    override val subsystemId: String = "wifi_direct_connection_manager"
    override val initPriority: Int = 45

    override suspend fun initialize() {
        try {
            if (manager == null) {
                MeshLogger.w(TAG, "WIFI_P2P_MANAGER_INITIALIZED: Wi-Fi P2P not supported on this device.")
                return
            }
            channel = manager?.initialize(context, Looper.getMainLooper(), null)
            MeshLogger.i(TAG, "WIFI_P2P_CHANNEL_INITIALIZED: P2P channel established.")
            setupDnsSdListeners()
            registerReceiver()
            MeshLogger.i(TAG, "WIFI_P2P_RECEIVER_REGISTERED: Listening for P2P events.")
            isOperational = true
            MeshLogger.i(TAG, "WifiDirectConnectionManager initialized.")
            
            checkStaleGroupAndRecover()
            observeNeighborState()
            observeTransportEvents()
        } catch (e: Exception) {
            errorCount++
            lastErrorStr = e.javaClass.simpleName
            isOperational = false
            MeshLogger.e(TAG, "Failed to initialize WifiDirectConnectionManager", e)
            throw e
        }
    }

    override fun shutdown() {
        activeConnectionJob?.cancel()
        discoveryRetryJob?.cancel()
        transportEventJob?.cancel()
        serviceDiscoveryRetryJob?.cancel()
        manager?.clearLocalServices(channel, null)
        manager?.clearServiceRequests(channel, null)
        discoveredServices.clear()
        unregisterReceiver()
        isOperational = false
        MeshLogger.i(TAG, "WifiDirectConnectionManager shut down.")
    }

    override fun getHealth(): SubsystemHealth {
        val role = if (isGroupOwner) "GROUP_OWNER" else if (isConnected) "CLIENT" else "NONE"
        return SubsystemHealth(
            subsystemId,
            isOperational,
            System.currentTimeMillis(),
            errorCount,
            if (isOperational) "State: $connectionState (Peers: ${peers.size}) | Role: $role | LastError: $lastErrorStr" else "Offline"
        )
    }
    
    fun getCandidateQueueSize(): Int = candidateQueue.size
    fun getAttemptedCandidateCount(): Int = attemptedCandidates.size

    
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

    
    private fun setupDnsSdListeners() {
        manager?.setDnsSdResponseListeners(channel,
            { instanceName, registrationType, srcDevice ->
                handleDnsSdServiceResponse(instanceName, registrationType, srcDevice)
            },
            { fullDomainName, record, srcDevice ->
                handleDnsSdTxtRecord(fullDomainName, record, srcDevice)
            }
        )
        logDiagnosticEvent("DNS_SD_LISTENERS_REGISTERED")
    }

    private fun handleDnsSdServiceResponse(instanceName: String, registrationType: String, srcDevice: WifiP2pDevice) {
        val entry = discoveredServices.getOrPut(srcDevice.deviceAddress) {
            DiscoveredMeshService(deviceAddress = srcDevice.deviceAddress)
        }
        entry.device = srcDevice
        entry.instanceName = instanceName
        entry.registrationType = registrationType
        entry.discoveryCycleId = connectionCycleId
        evaluateDiscoveredMeshService(srcDevice.deviceAddress)
    }

    private fun handleDnsSdTxtRecord(fullDomainName: String, record: Map<String, String>, srcDevice: WifiP2pDevice) {
        val entry = discoveredServices.getOrPut(srcDevice.deviceAddress) {
            DiscoveredMeshService(deviceAddress = srcDevice.deviceAddress)
        }
        entry.device = srcDevice
        entry.nodeId = record["nodeId"]
        entry.protocolVersion = record["protocolVersion"]?.toIntOrNull()
        entry.transportPort = record["transportPort"]?.toIntOrNull()
        entry.discoveryCycleId = connectionCycleId
        evaluateDiscoveredMeshService(srcDevice.deviceAddress)
    }

    private fun evaluateDiscoveredMeshService(deviceAddress: String) {
        if (localClusterRole != "MEMBER") return
        val service = discoveredServices[deviceAddress] ?: return
        
        if (service.discoveryCycleId != connectionCycleId) {
            logDiagnosticEvent("STALE_DNS_SD_CALLBACK_IGNORED")
            return
        }
        
        if (service.instanceName != DNS_SD_SERVICE_INSTANCE_NAME) return
        if (service.registrationType?.startsWith(DNS_SD_SERVICE_REGISTRATION_TYPE) != true) return
        
        if (service.nodeId == null || service.protocolVersion == null || service.transportPort == null) return

        val canonicalRemoteNodeId = DeviceIdentityManager.canonicalNodeId(service.nodeId!!)
        
        if (canonicalRemoteNodeId != electedCoordinatorNodeId) {
            logDiagnosticEvent("DNS_SD_NON_COORDINATOR_IGNORED")
            return
        }
        
        if (service.protocolVersion != DNS_SD_PROTOCOL_VERSION) {
            logDiagnosticEvent("DNS_SD_PROTOCOL_VERSION_REJECTED")
            return
        }
        
        if (service.transportPort != WifiDirectTransportManager.TRANSPORT_PORT) {
            logDiagnosticEvent("DNS_SD_TRANSPORT_PORT_REJECTED")
            return
        }
        
        if (service.device != null) {
            bindCoordinatorDevice(service.device!!)
        }
    }

    private fun bindCoordinatorDevice(device: WifiP2pDevice) {
        if (connectionState == ConnectionState.CANDIDATE_SELECTED || connectionState == ConnectionState.CONNECT_REQUEST_SUBMITTED || connectionState == ConnectionState.CONNECT_REQUEST_ACCEPTED || connectionState == ConnectionState.WAITING_FOR_GROUP_FORMATION || connectionState == ConnectionState.GROUP_JOINED || connectionState == ConnectionState.TCP_CONNECTING || connectionState == ConnectionState.TCP_CONNECTED || connectionState == ConnectionState.HANDSHAKE_PENDING || connectionState == ConnectionState.SESSION_VERIFIED || connectionState == ConnectionState.MESH_OPERATIONAL || activeAttemptGeneration != null) {
            return
        }
        
        serviceDiscoveryRetryJob?.cancel()
        logDiagnosticEvent("COORDINATOR_SERVICE_MATCHED")
        logDiagnosticEvent("COORDINATOR_P2P_DEVICE_BOUND")
        
        candidateQueue.clear()
        candidateQueue.add(device)
        connectionState = ConnectionState.COORDINATOR_SERVICE_FOUND
        probeNextCandidate()
    }

    private fun registerReceiver() {
        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                handleIntent(intent)
            }
        }
        context.registerReceiver(receiver, intentFilter)
    }

    private fun unregisterReceiver() {
        receiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {}
            receiver = null
        }
    }

    private fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    private fun observeNeighborState() {
        connectionScope.launch {
            NeighborStateRepository.getInstance().neighborListFlow.collect {
                MeshLogger.d(TAG, "NEIGHBOR_STATE_CHANGED: logical BLE neighbors updated.")
                reconcileConnectionState()
            }
        }
    }
    
    fun logDiagnosticEvent(eventName: String) {
        val event = DiagnosticEvent(
            timestamp = System.currentTimeMillis(),
            cycleId = connectionCycleId,
            state = connectionState.name,
            eventName = eventName,
            candidateMac = currentProbingCandidate?.deviceAddress?.take(8)?.plus("..."),
            attemptedCount = attemptedCandidates.size,
            peerCount = peers.size,
            hasActiveTimeoutJob = activeConnectionJob?.isActive == true
        )
        diagnosticEvents.add(event)
        while (diagnosticEvents.size > 50) {
            diagnosticEvents.poll()
        }
        MeshLogger.d(TAG, "EVENT: $event")
    }

    fun startMeshBuildCycle(): BuildCycleResult {
        val activeSessionCount = com.qualcomm.meshmind.network.transport.PeerSessionRegistry.getInstance().getActiveSessionCount()
        if (activeSessionCount > 0) {
            logDiagnosticEvent("BUILD_ALREADY_CONNECTED")
            return BuildCycleResult.ALREADY_CONNECTED
        }

        if (connectionState == ConnectionState.CONNECT_REQUEST_SUBMITTED || connectionState == ConnectionState.CONNECT_REQUEST_ACCEPTED) {
            logDiagnosticEvent("BUILD_ALREADY_IN_PROGRESS")
            return BuildCycleResult.BUILD_ALREADY_IN_PROGRESS
        }

        val repo = NeighborStateRepository.getInstance()
        val activeNeighbors = repo.getActiveNeighbors(ACTIVE_NEIGHBOR_CUTOFF_MS)

        if (activeNeighbors.isEmpty()) {
            logDiagnosticEvent("NO_ACTIVE_LOGICAL_PEERS")
            return BuildCycleResult.NO_ACTIVE_LOGICAL_PEERS
        }

        // Phase 11A Snapshot: Deduplicate and canonicalize
        val snapshotPeers = activeNeighbors.map { com.qualcomm.meshmind.identity.DeviceIdentityManager.canonicalNodeId(it.nodeId) }.toSet()
        targetLogicalPeers = snapshotPeers

        // Coordinator Election (Lowest UUID)
        val identityMgr = ServiceLocator.get(DeviceIdentityManager::class.java)
        val localNodeIdCanonical = com.qualcomm.meshmind.identity.DeviceIdentityManager.canonicalNodeId(identityMgr.getCachedNodeId() ?: "unknown_node")
        
        val candidateSet = snapshotPeers + localNodeIdCanonical
        electedCoordinatorNodeId = candidateSet.sorted().first()
        
        if (localNodeIdCanonical == electedCoordinatorNodeId) {
            localClusterRole = "COORDINATOR"
            MeshLogger.i(TAG, "LOCAL_NODE_IS_COORDINATOR: Elected as coordinator from snapshot size ${snapshotPeers.size}")
            logDiagnosticEvent("COORDINATOR_ELECTED")
            logDiagnosticEvent("LOCAL_NODE_IS_COORDINATOR")
        } else {
            localClusterRole = "MEMBER"
            MeshLogger.i(TAG, "LOCAL_NODE_IS_CLUSTER_MEMBER: Elected coordinator is $electedCoordinatorNodeId")
            logDiagnosticEvent("COORDINATOR_ELECTED")
            logDiagnosticEvent("LOCAL_NODE_IS_CLUSTER_MEMBER")
        }

        connectionCycleId++
        logDiagnosticEvent("BUILD_CYCLE_STARTED")
        
        activeConnectionJob?.cancel()
        activeConnectionJob = null
        discoveryRetryJob?.cancel()
        transportEventJob?.cancel()
        serviceDiscoveryRetryJob?.cancel()
        discoveryRetryJob = null
        discoveredServices.clear()
        manager?.clearLocalServices(channel, null)
        manager?.clearServiceRequests(channel, null)
        connectionAttemptGeneration = 0L
        activeAttemptGeneration = null
        candidateQueue.clear()
        attemptedCandidates.clear()
        candidateAttemptRecords.clear()
        currentProbingCandidate = null
        currentAttemptRecord = null
        attemptSequence = 0
        connectRequestAccepted = false
        connectionState = ConnectionState.IDLE
        lastErrorStr = "NONE"
        com.qualcomm.meshmind.network.transport.WifiDirectTransportManager.expectedCoordinatorNodeId = if (localClusterRole == "MEMBER") electedCoordinatorNodeId else null
        com.qualcomm.meshmind.network.transport.WifiDirectTransportManager.admissibleLogicalPeers = targetLogicalPeers

        if (!isDiscovering) {
            discoverPeers()
        }

        reconcileConnectionState()
        return BuildCycleResult.STARTED
    }

    @SuppressLint("MissingPermission")
    private fun checkStaleGroupAndRecover() {
        if (!hasPermissions()) return
        manager?.requestGroupInfo(channel) { group ->
            if (group != null) {
                MeshLogger.i(TAG, "WIFI_P2P_EXISTING_GROUP_FOUND: Owner=${group.owner?.deviceAddress}, Clients=${group.clientList.size}")
                val activeSessions = com.qualcomm.meshmind.network.transport.PeerSessionRegistry.getInstance().getActiveSessionCount()
                if (activeSessions == 0 && !hasAttemptedStaleGroupRecovery) {
                    MeshLogger.w(TAG, "WIFI_P2P_STALE_GROUP_RECOVERY: Zero verified sessions. Removing stale group.")
                    hasAttemptedStaleGroupRecovery = true
                    removeCurrentGroup {
                        connectionState = ConnectionState.IDLE
                        reconcileConnectionState()
                    }
                }
            } else {
                MeshLogger.i(TAG, "WIFI_P2P_NO_EXISTING_GROUP")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun removeCurrentGroup(onSuccessCallback: (() -> Unit)? = null) {
        if (!hasPermissions()) return
        connectionState = ConnectionState.GROUP_REMOVING
        manager?.clearLocalServices(channel, null)
        logDiagnosticEvent("SERVICE_ADVERTISEMENT_CLEARED")
        manager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                MeshLogger.i(TAG, "WIFI_P2P_GROUP_REMOVED")
                isConnected = false
                isGroupOwner = false
                connectionState = ConnectionState.IDLE
                onSuccessCallback?.invoke()
            }
            override fun onFailure(reason: Int) {
                MeshLogger.e(TAG, "WIFI_P2P_GROUP_REMOVE_FAILED: $reason")
                connectionState = ConnectionState.IDLE
                onSuccessCallback?.invoke()
            }
        })
    }

    fun handleIdentityMismatch() {
        val gen = activeAttemptGeneration
        if (gen != null) {
            MeshLogger.w(TAG, "IDENTITY_MISMATCH_RECEIVED: Tearing down invalid group and probing next candidate.")
            currentAttemptRecord?.finalOutcome = "IDENTITY_ASSOCIATION_MISMATCH"
        } else {
            connectionScope.launch { removeCurrentGroup { reconcileConnectionState() } }
        }
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                    MeshLogger.i(TAG, "WIFI_P2P_STATE_ENABLED")
                    discoverPeers()
                } else {
                    MeshLogger.w(TAG, "WIFI_P2P_STATE_DISABLED")
                }
            }
            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                MeshLogger.d(TAG, "WIFI_P2P_PEERS_CHANGED: Refreshing peer list.")
                logDiagnosticEvent("P2P_PEERS_UPDATED")
                requestPeers()
            }
            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                MeshLogger.d(TAG, "WIFI_P2P_CONNECTION_CHANGED")
                val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                val p2pInfo = intent.getParcelableExtra<WifiP2pInfo>(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                
                if (networkInfo?.isConnected == true) {
                    currentAttemptRecord?.connectedBroadcastReceived = true
                    if (connectionState != ConnectionState.GROUP_REMOVING && connectionState != ConnectionState.CLEANUP_STARTED) {
                        if (localClusterRole == "COORDINATOR" && connectionState == ConnectionState.GROUP_CREATE_REQUEST_ACCEPTED) {
                            connectionState = ConnectionState.GROUP_FORMED
                        } else if (localClusterRole == "MEMBER" && activeAttemptGeneration != null) {
                            connectionState = ConnectionState.GROUP_JOINED
                        }
                        isConnected = true
                        isGroupOwner = p2pInfo?.isGroupOwner == true
                        logDiagnosticEvent("CONNECTED_BROADCAST_RECEIVED")
                        MeshLogger.i(TAG, "WIFI_P2P_CONNECTION_AVAILABLE: P2P framework reports connected.")
                        requestConnectionInfo()
                        if (localClusterRole == "COORDINATOR") reconcileConnectionState()
                    }
                } else {
                    val wasConnected = isConnected
                    isConnected = false
                    isGroupOwner = false
                    
                    if (localClusterRole == "MEMBER" && activeAttemptGeneration != null) {
                        MeshLogger.w(TAG, "P2P_DISCONNECT_DURING_ACTIVE_ATTEMPT: Attempt $activeAttemptGeneration")
                        logDiagnosticEvent("P2P_DISCONNECT_DURING_ACTIVE_ATTEMPT")
                        currentAttemptRecord?.connectedBroadcastReceived = false
                    } else if (localClusterRole == "COORDINATOR" || activeAttemptGeneration == null) {
                        if (connectionState != ConnectionState.GROUP_REMOVING && connectionState != ConnectionState.CANDIDATES_EXHAUSTED) {
                            connectionState = ConnectionState.IDLE
                            MeshLogger.i(TAG, "WIFI_P2P_CONNECTION_LOST: P2P group disbanded.")
                            if (wasConnected) { discoverPeers() }
                            reconcileConnectionState()
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun discoverPeers() {
        if (!hasPermissions()) {
            MeshLogger.w(TAG, "WIFI_P2P_PERMISSION_MISSING")
            return
        }
        isDiscovering = true
        MeshLogger.i(TAG, "WIFI_P2P_DISCOVERY_REQUESTED")
        logDiagnosticEvent("P2P_DISCOVERY_REQUESTED")
        manager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                if (connectionState == ConnectionState.IDLE) {
                    connectionState = ConnectionState.DISCOVERING
                }
                MeshLogger.i(TAG, "WIFI_P2P_DISCOVERY_SUCCEEDED: Wi-Fi Direct discovery successfully started.")
            }
            override fun onFailure(reason: Int) {
                isDiscovering = false
                val reasonStr = when(reason) {
                    WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
                    WifiP2pManager.ERROR -> "ERROR"
                    WifiP2pManager.BUSY -> "BUSY"
                    else -> reason.toString()
                }
                lastErrorStr = reasonStr
                MeshLogger.w(TAG, "WIFI_P2P_DISCOVERY_FAILED: Reason code $reasonStr")
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun requestPeers() {
        if (!hasPermissions()) return
        manager?.requestPeers(channel) { peerList ->
            peers.clear()
            peers.addAll(peerList.deviceList)
            MeshLogger.d(TAG, "WIFI_P2P_PEER_DISCOVERED: Found ${peers.size} devices.")
            reconcileConnectionState()
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestConnectionInfo() {
        if (!hasPermissions()) return
        manager?.requestConnectionInfo(channel) { info ->
            MeshLogger.i(TAG, "WIFI_P2P_CONNECTION_INFO: groupFormed=${info.groupFormed}, isGroupOwner=${info.isGroupOwner}, groupOwnerAddress=${info.groupOwnerAddress?.hostAddress}")
            val transportMgr = ServiceLocator.get(WifiDirectTransportManager::class.java)
            if (info.groupFormed) {
                currentAttemptRecord?.groupFormed = true
                currentAttemptRecord?.finalOutcome = "GROUP_FORMED"
                
                // Connection successfully formed, cancel timeout
                activeConnectionJob?.cancel()
                activeConnectionJob = null
                
                if (info.isGroupOwner) {
                    MeshLogger.i(TAG, "WIFI_P2P_ROLE_GROUP_OWNER")
                    logDiagnosticEvent("GROUP_OWNER_CONFIRMED")
                    logDiagnosticEvent("TCP_SERVER_START_TRIGGERED")
                    transportMgr.startListening()
                    
                    if (localClusterRole == "COORDINATOR") {
                        connectionState = ConnectionState.SERVICE_ADVERTISEMENT_STARTING
                        logDiagnosticEvent("SERVICE_ADVERTISEMENT_STARTING")
                        connectionScope.launch {
                            val isServerReady = waitForConditionOrTimeout(5000L) { transportMgr.isServerListening }
                            if (!isServerReady) {
                                MeshLogger.e(TAG, "TCP server failed to start listening in time.")
                                logDiagnosticEvent("SERVICE_ADVERTISEMENT_FAILED")
                                connectionState = ConnectionState.FAILED
                                return@launch
                            }
                            
                            val record = mapOf(
                                "nodeId" to com.qualcomm.meshmind.identity.DeviceIdentityManager.canonicalNodeId(ServiceLocator.get(DeviceIdentityManager::class.java).getCachedNodeId() ?: "unknown_node"),
                                "protocolVersion" to DNS_SD_PROTOCOL_VERSION.toString(),
                                "transportPort" to WifiDirectTransportManager.TRANSPORT_PORT.toString()
                            )
                            val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(DNS_SD_SERVICE_INSTANCE_NAME, DNS_SD_SERVICE_REGISTRATION_TYPE, record)
                            
                            val addSuccess = suspendCancellableCoroutine<Boolean> { cont ->
                                manager?.addLocalService(channel, serviceInfo, object : WifiP2pManager.ActionListener {
                                    override fun onSuccess() {
                                        if (cont.isActive) cont.resume(true)
                                    }
                                    override fun onFailure(reason: Int) {
                                        if (cont.isActive) cont.resume(false)
                                    }
                                }) ?: cont.resume(false)
                            }
                            
                            if (addSuccess) {
                                connectionState = ConnectionState.ACCEPTING_MEMBERS
                                logDiagnosticEvent("SERVICE_ADVERTISEMENT_ACTIVE")
                                logDiagnosticEvent("ACCEPTING_MEMBERS")
                                MeshLogger.i(TAG, "WIFI_P2P_COORDINATOR_READY: Coordinator is now advertising DNS-SD and accepting members.")
                            } else {
                                MeshLogger.e(TAG, "WIFI_P2P_ADD_LOCAL_SERVICE_FAILED")
                                logDiagnosticEvent("SERVICE_ADVERTISEMENT_FAILED")
                                connectionState = ConnectionState.FAILED
                            }
                        }
                    }
                } else {
                    val groupOwnerAddress = info.groupOwnerAddress?.hostAddress
                    if (groupOwnerAddress != null) {
                        MeshLogger.i(TAG, "WIFI_P2P_ROLE_CLIENT: Connecting to TCP server at $groupOwnerAddress.")
                        logDiagnosticEvent("TCP_CLIENT_START_TRIGGERED")
                        transportMgr.connectToGroupOwner(groupOwnerAddress)
                    } else {
                        MeshLogger.w(TAG, "WIFI_P2P_ROLE_CLIENT: Group formed but groupOwnerAddress is null.")
                    }
                }
            } else {
                currentAttemptRecord?.groupFormed = false
                currentAttemptRecord?.finalOutcome = "GROUP_FORMED_FALSE_AFTER_BROADCAST"
                MeshLogger.w(TAG, "WIFI_P2P_CONNECTION_INFO: groupFormed is false despite a connected broadcast.")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun reconcileConnectionState() {
        if (!isReconciling.compareAndSet(false, true)) return
        
        connectionScope.launch {
            try {
                val activeVerifiedSessionCount = com.qualcomm.meshmind.network.transport.PeerSessionRegistry.getInstance().getActiveSessionCount()
                
                if (localClusterRole == "COORDINATOR") {
                    if (connectionState == ConnectionState.IDLE) {
                        connectionState = ConnectionState.GROUP_CREATE_STARTED
                        logDiagnosticEvent("GROUP_CREATE_STARTED")
                        executeGroupCreation(connectionCycleId)
                    } else if (activeVerifiedSessionCount > 0 && connectionState != ConnectionState.ACCEPTING_MEMBERS) {
                        connectionState = ConnectionState.ACCEPTING_MEMBERS
                    }
                    return@launch
                }
                
                if (localClusterRole == "MEMBER") {
                    if (connectionState == ConnectionState.MESH_OPERATIONAL) return@launch
                    if (activeVerifiedSessionCount > 0 && activeAttemptGeneration == null) return@launch
                    if (connectionCycleId == 0L || connectionState == ConnectionState.CANDIDATES_EXHAUSTED) return@launch
                    if (electedCoordinatorNodeId == null) return@launch
                    if (activeAttemptGeneration != null) return@launch // Attempt in progress
                    
                    if (serviceDiscoveryRetryJob == null || serviceDiscoveryRetryJob?.isActive == false) {
                        serviceDiscoveryRetryJob = connectionScope.launch {
                            while (localClusterRole == "MEMBER" && activeAttemptGeneration == null && electedCoordinatorNodeId != null && connectionState != ConnectionState.MESH_OPERATIONAL && isActive) {
                                connectionState = ConnectionState.SERVICE_DISCOVERING
                                logDiagnosticEvent("SERVICE_DISCOVERY_CYCLE_STARTED")
                                
                                logDiagnosticEvent("SERVICE_REQUEST_CLEAR_STARTED")
                                suspendCancellableCoroutine<Unit> { cont ->
                                    manager?.clearServiceRequests(channel, object : WifiP2pManager.ActionListener {
                                        override fun onSuccess() { if (cont.isActive) cont.resume(Unit) }
                                        override fun onFailure(r: Int) { if (cont.isActive) cont.resume(Unit) }
                                    }) ?: cont.resume(Unit)
                                }
                                logDiagnosticEvent("SERVICE_REQUEST_CLEARED")
                                
                                logDiagnosticEvent("SERVICE_REQUEST_ADD_STARTED")
                                val req = WifiP2pDnsSdServiceRequest.newInstance()
                                suspendCancellableCoroutine<Unit> { cont ->
                                    manager?.addServiceRequest(channel, req, object : WifiP2pManager.ActionListener {
                                        override fun onSuccess() { if (cont.isActive) cont.resume(Unit) }
                                        override fun onFailure(r: Int) { if (cont.isActive) cont.resume(Unit) }
                                    }) ?: cont.resume(Unit)
                                }
                                logDiagnosticEvent("SERVICE_REQUEST_ADDED")
                                
                                logDiagnosticEvent("SERVICE_DISCOVERY_API_STARTED")
                                val discoverSuccess = suspendCancellableCoroutine<Boolean> { cont ->
                                    manager?.discoverServices(channel, object : WifiP2pManager.ActionListener {
                                        override fun onSuccess() { if (cont.isActive) cont.resume(true) }
                                        override fun onFailure(r: Int) { if (cont.isActive) cont.resume(false) }
                                    }) ?: cont.resume(false)
                                }
                                
                                if (discoverSuccess) {
                                    logDiagnosticEvent("SERVICE_DISCOVERY_API_ACCEPTED")
                                } else {
                                    logDiagnosticEvent("SERVICE_DISCOVERY_API_FAILED")
                                }
                                
                                delay(5000L)
                                logDiagnosticEvent("SERVICE_DISCOVERY_RETRY")
                            }
                            logDiagnosticEvent("SERVICE_DISCOVERY_CANCELLED")
                        }
                    }
                    
                    // Generic discoverPeers is only for fallback/diagnostics now.
                    // We DO NOT populate candidateQueue from peers for MEMBER role anymore.
                    logDiagnosticEvent("MEMBER_CONNECT_REQUIRES_DNS_SD_COORDINATOR_BINDING")

                    
                    if (candidateQueue.isNotEmpty()) {
                        if (peers.size > 1 && currentProbingCandidate == null) {
                            connectionState = ConnectionState.PHYSICAL_CANDIDATE_AMBIGUOUS
                        }
                        probeNextCandidate()
                    } else {
                        if (currentProbingCandidate == null && activeAttemptGeneration == null) {
                            MeshLogger.w(TAG, "All candidates exhausted for cycle $connectionCycleId.")
                            connectionState = ConnectionState.CANDIDATES_EXHAUSTED
                            val summaryStr = candidateAttemptRecords.filter { it.connectionCycleId == connectionCycleId }
                                .groupingBy { it.finalOutcome }
                                .eachCount().map { "${it.value} ${it.key}" }.joinToString(", ")
                            lastErrorStr = "${candidateAttemptRecords.size} candidates: $summaryStr"
                            logDiagnosticEvent("CANDIDATES_EXHAUSTED")
                        }
                    }
                }
            } finally {
                isReconciling.set(false)
            }
        }
    }

    private fun probeNextCandidate() {
        if (candidateQueue.isEmpty() || activeAttemptGeneration != null) return
        
        val candidate = candidateQueue.removeAt(0)
        attemptedCandidates.add(candidate.deviceAddress)
        currentProbingCandidate = candidate
        
        val generation = ++connectionAttemptGeneration
        activeAttemptGeneration = generation
        connectionState = ConnectionState.CANDIDATE_SELECTED
        connectRequestAccepted = false
        
        attemptSequence++
        currentAttemptRecord = CandidateAttemptRecord(
            connectionCycleId = connectionCycleId,
            deviceAddress = candidate.deviceAddress,
            deviceName = candidate.deviceName,
            attemptSeq = attemptSequence,
            startElapsedRealtime = SystemClock.elapsedRealtime()
        ).also {
            candidateAttemptRecords.add(it)
        }
        
        logDiagnosticEvent("CANDIDATE_ATTEMPT_CREATED")
        logDiagnosticEvent("PHYSICAL_CANDIDATE_PROBED")
        MeshLogger.i(TAG, "CANDIDATE_PROBING: Attempting to connect to candidate ${candidate.deviceAddress} (Gen $generation)")
        executeConnectionAttempt(candidate, connectionCycleId, generation)
    }

    @SuppressLint("MissingPermission")
    private fun executeGroupCreation(cycleId: Long) {
        if (!hasPermissions()) {
            MeshLogger.w(TAG, "WIFI_P2P_CREATE_GROUP_FAILED: Reason: PERMISSION_MISSING")
            return
        }
        
        manager?.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                connectionScope.launch {
                    if (cycleId != connectionCycleId) return@launch
                    connectionState = ConnectionState.GROUP_CREATE_REQUEST_ACCEPTED
                    logDiagnosticEvent("GROUP_CREATE_API_ACCEPTED")
                    MeshLogger.i(TAG, "WIFI_P2P_CREATE_GROUP_ACTION_SUCCEEDED: Framework accepted, waiting for group broadcast.")
                }
            }
            override fun onFailure(reason: Int) {
                connectionScope.launch {
                    if (cycleId != connectionCycleId) return@launch
                    val reasonStr = when(reason) {
                        WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
                        WifiP2pManager.ERROR -> "ERROR"
                        WifiP2pManager.BUSY -> "BUSY"
                        else -> reason.toString()
                    }
                    MeshLogger.w(TAG, "WIFI_P2P_CREATE_GROUP_ACTION_FAILED: Reason code $reasonStr")
                    logDiagnosticEvent("GROUP_CREATE_API_FAILED")
                    connectionState = ConnectionState.FAILED
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun executeConnectionAttempt(device: WifiP2pDevice, cycleId: Long, generation: Long) {
        if (!hasPermissions()) {
            connectionScope.launch { failActiveCandidate(generation, "CONNECT_API_FAILED_PERMISSION_MISSING") }
            return
        }
        
        activeConnectionJob = connectionScope.launch {
            val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress }
            connectionState = ConnectionState.CONNECT_REQUEST_SUBMITTED
            logDiagnosticEvent("P2P_CONNECT_API_INVOKED")
            
            val apiSuccess = suspendCancellableCoroutine<Boolean> { cont ->
                manager?.connect(channel, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (cont.isActive) cont.resume(true)
                    }
                    override fun onFailure(reason: Int) {
                        val reasonStr = when(reason) {
                            WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
                            WifiP2pManager.ERROR -> "ERROR"
                            WifiP2pManager.BUSY -> "BUSY"
                            else -> reason.toString()
                        }
                        lastErrorStr = reasonStr
                        if (cont.isActive) cont.resume(false)
                    }
                }) ?: cont.resume(false)
            }
            
            if (!apiSuccess) {
                logDiagnosticEvent("P2P_CONNECT_ACTION_FAILED")
                currentAttemptRecord?.apiResult = lastErrorStr
                failActiveCandidate(generation, "CONNECT_API_FAILED_$lastErrorStr")
                return@launch
            }
            
            connectionState = ConnectionState.CONNECT_REQUEST_ACCEPTED
            connectRequestAccepted = true
            currentAttemptRecord?.apiResult = "ACCEPTED"
            logDiagnosticEvent("P2P_CONNECT_API_ACCEPTED")
            
            val broadcastReceived = waitForConditionOrTimeout(15000L) {
                currentAttemptRecord?.connectedBroadcastReceived == true
            }
            
            if (!broadcastReceived) {
                failActiveCandidate(generation, "NEGOTIATION_TIMEOUT_NO_CONNECTED_BROADCAST")
                return@launch
            }
            
            connectionState = ConnectionState.WAITING_FOR_GROUP_FORMATION
            
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
                logDiagnosticEvent("EXPECTED_COORDINATOR_SESSION_VERIFIED")
                
                discoveryRetryJob?.cancel()
        transportEventJob?.cancel()
                activeAttemptGeneration = null
                candidateQueue.clear()
                currentProbingCandidate = null
                connectionState = ConnectionState.MESH_OPERATIONAL
                logDiagnosticEvent("MEMBER_MESH_OPERATIONAL")
            } else {
                failActiveCandidate(generation, "UNEXPECTED_VERIFIED_SESSION")
            }
        }
    }

    private suspend fun failActiveCandidate(generation: Long, reason: String) {
        if (activeAttemptGeneration != generation) {
            MeshLogger.w(TAG, "CANDIDATE_ATTEMPT_STALE_CALLBACK_IGNORED: Generation $generation (Active: $activeAttemptGeneration). Ignored.")
            return
        }
        MeshLogger.w(TAG, "ATTEMPT_FAILED: Candidate attempt $generation failed. Reason: $reason")
        connectionState = ConnectionState.CLEANUP_STARTED
        logDiagnosticEvent("FRAMEWORK_CLEANUP_STARTED")
        
        currentAttemptRecord?.finalOutcome = reason
        lastErrorStr = reason
        
        suspendCancelConnect()
        val isStaleGroup = suspendCheckStaleGroup()
        if (isStaleGroup) suspendRemoveGroup()
        
        connectionState = ConnectionState.CLEANUP_COMPLETED
        logDiagnosticEvent("FRAMEWORK_CLEANUP_COMPLETED")
        
        currentProbingCandidate = null
        activeAttemptGeneration = null
        connectionState = ConnectionState.IDLE
        
        reconcileConnectionState()
    }

    @SuppressLint("MissingPermission")
    private suspend fun suspendCancelConnect() = suspendCancellableCoroutine<Unit> { cont ->
        if (!hasPermissions() || channel == null) {
            cont.resume(Unit)
            return@suspendCancellableCoroutine
        }
        logDiagnosticEvent("CANCEL_CONNECT_STARTED")
        manager?.cancelConnect(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                logDiagnosticEvent("CANCEL_CONNECT_SUCCEEDED")
                if (cont.isActive) cont.resume(Unit)
            }
            override fun onFailure(reason: Int) {
                logDiagnosticEvent("CANCEL_CONNECT_FAILED")
                if (cont.isActive) cont.resume(Unit)
            }
        }) ?: cont.resume(Unit)
    }
    
    @SuppressLint("MissingPermission")
    private suspend fun suspendRemoveGroup() = suspendCancellableCoroutine<Unit> { cont ->
        if (!hasPermissions() || channel == null) {
            cont.resume(Unit)
            return@suspendCancellableCoroutine
        }
        logDiagnosticEvent("REMOVE_GROUP_STARTED")
        manager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                logDiagnosticEvent("REMOVE_GROUP_SUCCEEDED")
                isConnected = false
                isGroupOwner = false
                if (cont.isActive) cont.resume(Unit)
            }
            override fun onFailure(reason: Int) {
                logDiagnosticEvent("REMOVE_GROUP_FAILED")
                if (cont.isActive) cont.resume(Unit)
            }
        }) ?: cont.resume(Unit)
    }

    @SuppressLint("MissingPermission")
    private suspend fun suspendCheckStaleGroup(): Boolean = suspendCancellableCoroutine { cont ->
        if (!hasPermissions() || channel == null) {
            cont.resume(false)
            return@suspendCancellableCoroutine
        }
        manager?.requestConnectionInfo(channel) { info ->
            cont.resume(info?.groupFormed == true)
        }
    }

    private suspend fun waitForConditionOrTimeout(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (condition()) return true
            delay(500)
        }
        return false
    }
    
    fun resetConnectionCycle() {
        startMeshBuildCycle()
    }
}
