package com.qualcomm.meshmind.viewmodel

import androidx.lifecycle.viewModelScope
import com.qualcomm.meshmind.core.dependency.ServiceLocator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Presentation state holder for the Dashboard screen in Kotlin.
 */
class DashboardViewModel : BaseViewModel() {

    private val _meshStatus = MutableStateFlow("Offline - No Mesh Connected")
    val meshStatus: StateFlow<String> = _meshStatus.asStateFlow()

    private val _discoveredPeersCount = MutableStateFlow(0)
    val discoveredPeersCount: StateFlow<Int> = _discoveredPeersCount.asStateFlow()

    private val _connectedPeersCount = MutableStateFlow(0)
    val connectedPeersCount: StateFlow<Int> = _connectedPeersCount.asStateFlow()
    
    private val _buildMeshMessage = MutableStateFlow<String?>(null)
    val buildMeshMessage: StateFlow<String?> = _buildMeshMessage.asStateFlow()

    init {
        startMetricsUpdates()
    }

    private fun startMetricsUpdates() {
        viewModelScope.launch {
            while (true) {
                try {
                    val neighborRepo = com.qualcomm.meshmind.state.NeighborStateRepository.getInstance()
                    val activeNeighbors = neighborRepo.getActiveNeighbors(15000L)
                    _discoveredPeersCount.value = activeNeighbors.size
                    
                    val activeSessionsCount = com.qualcomm.meshmind.network.transport.PeerSessionRegistry.getInstance().getActiveSessionCount()
                    _connectedPeersCount.value = activeSessionsCount
                    
                    val tm = runCatching { ServiceLocator.get(com.qualcomm.meshmind.network.transport.TransportManager::class.java) }.getOrNull()
                    
                    
                    val dm = runCatching { ServiceLocator.get(com.qualcomm.meshmind.network.discovery.DiscoveryManager::class.java) }.getOrNull()
                    
                    
                    val wifiConnMgr = runCatching { ServiceLocator.get(com.qualcomm.meshmind.network.transport.WifiDirectConnectionManager::class.java) }.getOrNull()
                    
                    _meshStatus.value = when {
                        activeSessionsCount > 0 -> "Transport Online"
                        activeNeighbors.isEmpty() -> "No Active Peer - Run Discovery"
                        wifiConnMgr?.connectionCycleId == 0L -> "Peer Discovered - Ready to Build Mesh"
                        wifiConnMgr?.connectionState == com.qualcomm.meshmind.network.transport.WifiDirectConnectionManager.ConnectionState.WAITING_FOR_ELECTED_INITIATOR -> "Waiting for Elected Initiator"
                        wifiConnMgr?.connectionState == com.qualcomm.meshmind.network.transport.WifiDirectConnectionManager.ConnectionState.CANDIDATES_EXHAUSTED -> "Physical Candidates Exhausted"
                        wifiConnMgr?.connectRequestAccepted == true && !wifiConnMgr.isConnected -> "Wi-Fi Direct Negotiating"
                        wifiConnMgr?.isConnected == true && activeSessionsCount == 0 -> "P2P Group Formed - Establishing Transport"
                        wifiConnMgr?.connectionState == com.qualcomm.meshmind.network.transport.WifiDirectConnectionManager.ConnectionState.CONNECT_REQUEST_SUBMITTED -> "Probing Physical Candidates"
                        else -> "Peer Discovered - Ready to Build Mesh"
                    }
                } catch (ignored: Exception) {}
                delay(1000)
            }
        }
    }

    fun startDiscovery() {
        try {
            val dm = ServiceLocator.get(com.qualcomm.meshmind.network.discovery.DiscoveryManager::class.java)
            dm.startDiscovery()
        } catch (e: Exception) {
            // Ignored
        }
    }
    
    fun buildMesh() {
        // Build mesh triggers an explicit cycle start
        try {
            val wifiConnMgr = ServiceLocator.get(com.qualcomm.meshmind.network.transport.WifiDirectConnectionManager::class.java)
            val result = wifiConnMgr.startMeshBuildCycle()
            _buildMeshMessage.value = result.name
            
            // Clear message after short delay
            viewModelScope.launch {
                delay(3000)
                _buildMeshMessage.value = null
            }
        } catch (e: Exception) {}
    }
}
