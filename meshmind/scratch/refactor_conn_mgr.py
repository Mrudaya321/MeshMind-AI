import re

file_path = r'D:\meshmind\meshmind\app\src\main\java\com\qualcomm\meshmind\network\transport\WifiDirectConnectionManager.kt'
with open(file_path, 'r') as f:
    content = f.read()

# Imports
imports_to_add = """import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import java.util.concurrent.ConcurrentHashMap
"""
content = content.replace('import java.text.SimpleDateFormat', imports_to_add + 'import java.text.SimpleDateFormat')

# Constants
constants = """        const val DNS_SD_SERVICE_INSTANCE_NAME = "MeshMind"
        const val DNS_SD_SERVICE_REGISTRATION_TYPE = "_meshmind._tcp"
        const val DNS_SD_PROTOCOL_VERSION = 1
"""
content = content.replace('private const val ACTIVE_NEIGHBOR_CUTOFF_MS = 15000L', 'private const val ACTIVE_NEIGHBOR_CUTOFF_MS = 15000L\n' + constants)

# Data Class
data_class = """data class DiscoveredMeshService(
    val deviceAddress: String,
    var device: WifiP2pDevice? = null,
    var instanceName: String? = null,
    var registrationType: String? = null,
    var nodeId: String? = null,
    var protocolVersion: Int? = null,
    var transportPort: Int? = null,
    var discoveryCycleId: Long = 0
)
"""
content = content.replace('data class CandidateAttemptRecord', data_class + '\ndata class CandidateAttemptRecord')

# Variables
vars_to_add = """    private val discoveredServices = ConcurrentHashMap<String, DiscoveredMeshService>()
    private var serviceDiscoveryRetryJob: Job? = null
"""
content = content.replace('private var transportEventJob: Job? = null', 'private var transportEventJob: Job? = null\n' + vars_to_add)

# States
content = content.replace('WAITING_FOR_ELECTED_INITIATOR', 'WAITING_FOR_ELECTED_INITIATOR, SERVICE_ADVERTISEMENT_STARTING, SERVICE_ADVERTISEMENT_ACTIVE, SERVICE_DISCOVERING, CANDIDATE_SELECTED, COORDINATOR_SERVICE_FOUND')

# Initialize
init_str = """            channel = manager?.initialize(context, Looper.getMainLooper(), null)
            MeshLogger.i(TAG, "WIFI_P2P_CHANNEL_INITIALIZED: P2P channel established.")
            registerReceiver()"""
init_new = """            channel = manager?.initialize(context, Looper.getMainLooper(), null)
            MeshLogger.i(TAG, "WIFI_P2P_CHANNEL_INITIALIZED: P2P channel established.")
            setupDnsSdListeners()
            registerReceiver()"""
content = content.replace(init_str, init_new)

# Setup DNS-SD Listeners
setup_listeners = """
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
        
        if (canonicalRemoteNodeId != expectedCoordinatorNodeId) {
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
"""
content = content.replace('private fun registerReceiver() {', setup_listeners + '\n    private fun registerReceiver() {')

# Shutdown
shutdown_str = """        activeConnectionJob?.cancel()
        discoveryRetryJob?.cancel()
        transportEventJob?.cancel()"""
shutdown_new = """        activeConnectionJob?.cancel()
        discoveryRetryJob?.cancel()
        transportEventJob?.cancel()
        serviceDiscoveryRetryJob?.cancel()
        manager?.clearLocalServices(channel, null)
        manager?.clearServiceRequests(channel, null)
        discoveredServices.clear()"""
content = content.replace(shutdown_str, shutdown_new)

# startMeshBuildCycle
start_str = """        discoveryRetryJob?.cancel()
        transportEventJob?.cancel()
        discoveryRetryJob = null"""
start_new = """        discoveryRetryJob?.cancel()
        transportEventJob?.cancel()
        serviceDiscoveryRetryJob?.cancel()
        discoveryRetryJob = null
        discoveredServices.clear()
        manager?.clearLocalServices(channel, null)
        manager?.clearServiceRequests(channel, null)"""
content = content.replace(start_str, start_new)

# requestConnectionInfo (Coordinator local service)
request_conn_old = """                    if (localClusterRole == "COORDINATOR") {
                        connectionState = ConnectionState.ACCEPTING_MEMBERS
                        logDiagnosticEvent("ACCEPTING_MEMBERS")
                        MeshLogger.i(TAG, "WIFI_P2P_COORDINATOR_READY: Coordinator is now accepting members.")
                    }"""
request_conn_new = """                    if (localClusterRole == "COORDINATOR") {
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
                    }"""
content = content.replace(request_conn_old, request_conn_new)


# Reconcile Connection State
recon_old = """                    if (peers.isEmpty()) {
                        if (connectionState == ConnectionState.IDLE) {
                            if (discoveryRetryJob == null || discoveryRetryJob?.isActive == false) {
                                logDiagnosticEvent("MEMBER_ZERO_PEERS_RETRY_SCHEDULED")
                                discoveryRetryJob = connectionScope.launch {
                                    while (peers.isEmpty() && connectionState == ConnectionState.IDLE && activeAttemptGeneration == null && localClusterRole == "MEMBER") {
                                        delay(3000)
                                        if (peers.isEmpty()) {
                                            logDiagnosticEvent("MEMBER_ZERO_PEERS_DISCOVERY_RETRY")
                                            if (!isDiscovering) discoverPeers()
                                        }
                                    }
                                    logDiagnosticEvent("MEMBER_ZERO_PEERS_RETRY_CANCELLED")
                                }
                            }
                        }
                        return@launch
                    }
                    
                    if (connectionState == ConnectionState.IDLE) {
                        for (peer in peers) {
                            if ((peer.status == WifiP2pDevice.AVAILABLE || peer.status == WifiP2pDevice.CONNECTED) 
                                && !attemptedCandidates.contains(peer.deviceAddress)
                                && !candidateQueue.any { it.deviceAddress == peer.deviceAddress }) {
                                candidateQueue.add(peer)
                            }
                        }
                    }"""
recon_new = """                    if (serviceDiscoveryRetryJob == null || serviceDiscoveryRetryJob?.isActive == false) {
                        serviceDiscoveryRetryJob = connectionScope.launch {
                            while (localClusterRole == "MEMBER" && activeAttemptGeneration == null && expectedCoordinatorNodeId != null && connectionState != ConnectionState.MESH_OPERATIONAL && isActive) {
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
"""
content = content.replace(recon_old, recon_new)

# Remove current group callback clearing services
remove_group_str = """        connectionState = ConnectionState.GROUP_REMOVING
        manager?.removeGroup(channel, object : WifiP2pManager.ActionListener {"""
remove_group_new = """        connectionState = ConnectionState.GROUP_REMOVING
        manager?.clearLocalServices(channel, null)
        logDiagnosticEvent("SERVICE_ADVERTISEMENT_CLEARED")
        manager?.removeGroup(channel, object : WifiP2pManager.ActionListener {"""
content = content.replace(remove_group_str, remove_group_new)


with open(file_path, 'w') as f:
    f.write(content)

print("Done")
