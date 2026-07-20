package com.qualcomm.meshmind.ui

import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import android.widget.AdapterView
import android.widget.ArrayAdapter
import com.qualcomm.meshmind.core.dependency.ServiceLocator
import com.qualcomm.meshmind.databinding.FragmentSettingsBinding
import com.qualcomm.meshmind.configuration.ConfigManager
import com.qualcomm.meshmind.identity.DeviceIdentityManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SettingsFragment : BaseFragment() {

    private var binding: FragmentSettingsBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        binding!!.toolbar.title = "Mesh Configuration Settings"
        binding!!.toolbar.setNavigationOnClickListener { getNavController().popBackStack() }
        
        val roles = com.qualcomm.meshmind.classification.models.EmergencyResponseRole.values().map { it.name }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, roles)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding!!.spinnerRole.adapter = adapter
        
        val configManager = ServiceLocator.get(ConfigManager::class.java)
        val currentRole = configManager.getEmergencyResponseRole()
        binding!!.spinnerRole.setSelection(roles.indexOf(currentRole.name))
        
        binding!!.spinnerRole.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedRole = com.qualcomm.meshmind.classification.models.EmergencyResponseRole.valueOf(roles[position])
                if (selectedRole != configManager.getEmergencyResponseRole()) {
                    configManager.setEmergencyResponseRole(selectedRole)
                    
                    // Broadcast new role
                    viewLifecycleOwner.lifecycleScope.launch {
                        val identityMgr = ServiceLocator.get(com.qualcomm.meshmind.identity.DeviceIdentityManager::class.java)
                        val payload = com.qualcomm.meshmind.classification.models.EmergencyRoleAnnouncementV1(
                            announcementId = java.util.UUID.randomUUID().toString(),
                            canonicalNodeId = identityMgr.resolveNodeId(),
                            responseRole = selectedRole.name,
                            generation = System.currentTimeMillis(),
                            createdAt = System.currentTimeMillis()
                        )
                        com.qualcomm.meshmind.communication.ReliableCommunicationManager.getInstance().sendEmergencyRoleAnnouncement(payload)
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                try {
                    
                    val identity = ServiceLocator.get(DeviceIdentityManager::class.java)
                    
                    val bleManager = runCatching { ServiceLocator.get(com.qualcomm.meshmind.network.discovery.DiscoveryManager::class.java) }.getOrNull()
                    val isBleOperational = (bleManager as? com.qualcomm.meshmind.core.runtime.BaseSubsystem)?.getHealth()?.isOperational == true
                    
                    val bleAdv = if (bleManager == null) "UNKNOWN" else if (!isBleOperational) "START_FAILED" else if (bleManager.isAdvertising) "ACTIVE" else "INACTIVE"
                    val bleScan = if (bleManager == null) "UNKNOWN" else if (!isBleOperational) "START_FAILED" else if (bleManager.isScanning) "ACTIVE" else "INACTIVE"
                    
                    val neighborRepo = com.qualcomm.meshmind.state.NeighborStateRepository.getInstance()
                    val storedNeighborCount = neighborRepo.getAllNeighbors().size
                    val activeNeighborCount = neighborRepo.getActiveNeighbors(15000L).size
                    
                    val wifiManager = runCatching { ServiceLocator.get(com.qualcomm.meshmind.network.transport.WifiDirectConnectionManager::class.java) }.getOrNull()
                    val wifiHealth = wifiManager?.getHealth()
                    
                    val wifiDiscovery = if (wifiManager?.isDiscovering == true) "DISCOVERING" else "IDLE"
                    val wifiStateStr = wifiManager?.connectionState?.name ?: "UNKNOWN"
                    val wifiRole = if (wifiManager?.isGroupOwner == true) "GROUP_OWNER" else if (wifiManager?.isConnected == true) "CLIENT" else "UNKNOWN"
                    val peerCount = wifiHealth?.diagnosticMessage?.substringAfter("Peers: ")?.substringBefore(")") ?: "0"
                    
                    val probingCandidate = wifiManager?.currentProbingCandidate?.deviceAddress?.let {
                        it.take(8) + "..."
                    } ?: "NONE"
                    
                    val attemptedCount = wifiManager?.getAttemptedCandidateCount() ?: 0
                    
                    val transportManager = runCatching { ServiceLocator.get(com.qualcomm.meshmind.network.transport.WifiDirectTransportManager::class.java) }.getOrNull()
                    
                    val dnsSdAdvState = if (wifiStateStr.contains("SERVICE_ADVERTISEMENT")) wifiStateStr else if (wifiStateStr == "ACCEPTING_MEMBERS") "SERVICE_ADVERTISEMENT_ACTIVE" else "IDLE"
                    val dnsSdDiscState = if (wifiStateStr == "SERVICE_DISCOVERING") "SERVICE_DISCOVERING" else "IDLE"
                    val discoveredServicesCount = wifiManager?.getDiscoveredServicesCount() ?: 0
                    val boundCoordinatorDevice = wifiManager?.currentProbingCandidate?.deviceAddress ?: "NONE"
                    val serviceDiscoveryGeneration = wifiManager?.connectionCycleId ?: 0
                    val transportHealth = transportManager?.getHealth()
                    
                    val isListening = transportHealth?.diagnosticMessage?.contains("Listening: true") == true
                    val tcpServer = if (isListening) "LISTENING" else "STOPPED"
                    val tcpClient = if (wifiRole == "CLIENT") (if (transportHealth?.isOperational == true) "CONNECTED" else "CONNECTING") else "IDLE"
                    
                    val sessionCount = com.qualcomm.meshmind.network.transport.PeerSessionRegistry.getInstance().getActiveSessionCount()
                    
                    val cycleId = wifiManager?.connectionCycleId ?: 0
                    val connectAccepted = wifiManager?.connectRequestAccepted == true
                    
                    var lastError = transportHealth?.diagnosticMessage?.substringAfter("LastError: ")?.substringBefore(" |") ?: "NONE"
                    if (wifiManager?.connectionState == com.qualcomm.meshmind.network.transport.WifiDirectConnectionManager.ConnectionState.CANDIDATES_EXHAUSTED) {
                        lastError = wifiManager.getHealth().diagnosticMessage.substringAfter("LastError: ")
                    } else if (wifiManager?.connectionState == com.qualcomm.meshmind.network.transport.WifiDirectConnectionManager.ConnectionState.WAITING_FOR_ELECTED_INITIATOR) {
                        lastError = "WAITING_FOR_ELECTED_INITIATOR"
                    } else if (lastError == "NONE") {
                        val hwError = wifiHealth?.diagnosticMessage?.substringAfter("LastError: ") ?: "NONE"
                        if (hwError != "NONE") {
                            lastError = hwError
                        }
                    }
                    
                    val expectedLogicalCandidate = wifiManager?.electedCoordinatorNodeId ?: "NONE"
                    val localClusterRole = wifiManager?.localClusterRole ?: "NONE"
                    val targetPeers = wifiManager?.targetLogicalPeers?.joinToString(",") ?: "NONE"
                    val admissiblePeers = com.qualcomm.meshmind.network.transport.WifiDirectTransportManager.admissibleLogicalPeers.joinToString(",")
                    val processId = com.qualcomm.meshmind.MeshMindApplication.processInstanceId
                    val uptimeMs = SystemClock.elapsedRealtime() - com.qualcomm.meshmind.MeshMindApplication.processStartedAtElapsedRealtime
                    val uptimeStr = String.format("%02d:%02d", (uptimeMs / 1000) / 60, (uptimeMs / 1000) % 60)
                    
                    val configManager = ServiceLocator.get(ConfigManager::class.java)
                    val currentRole = configManager.getEmergencyResponseRole()

                    val sb = StringBuilder()
                    sb.append("System Identity:\n")
                    sb.append("• Resolved Edge Node ID: ${identity.resolveNodeId()}\n")
                    sb.append("• Process Instance ID: $processId\n")
                    sb.append("• Process Uptime: $uptimeStr\n")
                    sb.append("• Emergency Response Role: $currentRole\n\n")
                    
                    sb.append("Physical Transport Diagnostics:\n")
                    sb.append("========================================\n")
                    sb.append("• BLE Advertising: $bleAdv\n")
                    sb.append("• BLE Scanning: $bleScan\n")
                    sb.append("• Stored Logical BLE Neighbors: $storedNeighborCount\n")
                    sb.append("• Active BLE Neighbors (15s): $activeNeighborCount\n")
                    sb.append("• Local Cluster Role: $localClusterRole\n")
                    sb.append("• Elected Coordinator Node ID: $expectedLogicalCandidate\n")
                    sb.append("• Target Logical Peers: $targetPeers\n")
                    sb.append("• Admissible Peers: $admissiblePeers\n")
                    sb.append("• Wi-Fi Direct Discovery: $wifiDiscovery\n")
                    sb.append("• Wi-Fi Direct Peer Count: $peerCount\n")
                    sb.append("• Connection Cycle ID: $cycleId\n")
                    sb.append("• Build Cycle State: $wifiStateStr\n")
                    sb.append("• Selected P2P Candidate: $probingCandidate\n")
                    sb.append("• Connect Request Accepted: $connectAccepted\n")
                    sb.append("• Attempted Candidates This Cycle: $attemptedCount\n")
                    sb.append("• Wi-Fi Direct Role: $wifiRole\n")
                    sb.append("• TCP Server: $tcpServer\n")
                    sb.append("• TCP Client: $tcpClient\n")
                    sb.append("• Verified Transport Sessions: $sessionCount\n")
                    sb.append("• Last Terminal State: $lastError\n\n")
                    
                    sb.append("Candidate Attempt Records (Cycle $cycleId):\n")
                    sb.append("----------------------------------------\n")
                    
                    val records = wifiManager?.candidateAttemptRecords?.toList() ?: emptyList()
                    if (records.isEmpty()) {
                        sb.append("No candidates probed in this cycle.\n")
                    } else {
                        records.forEach { record ->
                            sb.append(record.getFormattedLine()).append("\n")
                        }
                    }
                    
                    binding?.tvStatusInfo?.text = sb.toString()
                } catch (ignored: Exception) {}
                
                delay(1000)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
