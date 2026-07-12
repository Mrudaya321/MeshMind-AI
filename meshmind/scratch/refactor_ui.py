import re

file_path = r'D:\meshmind\meshmind\app\src\main\java\com\qualcomm\meshmind\ui\SettingsFragment.kt'
with open(file_path, 'r') as f:
    content = f.read()

content = content.replace('val config = ServiceLocator.get(ConfigManager::class.java)', '')

# Add new diagnostics to SettingsFragment
diag_old = """                    val attemptedCount = wifiManager?.getAttemptedCandidateCount() ?: 0
                    
                    val transportManager = runCatching { ServiceLocator.get(com.qualcomm.meshmind.network.transport.WifiDirectTransportManager::class.java) }.getOrNull()"""

diag_new = """                    val attemptedCount = wifiManager?.getAttemptedCandidateCount() ?: 0
                    
                    val transportManager = runCatching { ServiceLocator.get(com.qualcomm.meshmind.network.transport.WifiDirectTransportManager::class.java) }.getOrNull()
                    
                    val dnsSdAdvState = if (wifiStateStr.contains("SERVICE_ADVERTISEMENT")) wifiStateStr else if (wifiStateStr == "ACCEPTING_MEMBERS") "SERVICE_ADVERTISEMENT_ACTIVE" else "IDLE"
                    val dnsSdDiscState = if (wifiStateStr == "SERVICE_DISCOVERING") "SERVICE_DISCOVERING" else "IDLE"
                    val discoveredServicesCount = wifiManager?.getDiscoveredServicesCount() ?: 0
                    val boundCoordinatorDevice = wifiManager?.currentProbingCandidate?.deviceAddress ?: "NONE"
                    val serviceDiscoveryGeneration = wifiManager?.connectionCycleId ?: 0"""

content = content.replace(diag_old, diag_new)

sb_old = """                    sb.append("• Wi-Fi Direct Peer Count: $peerCount\n")
                    sb.append("• Connection Cycle ID: $cycleId\n")"""

sb_new = """                    sb.append("• Wi-Fi Direct Peer Count: $peerCount\n")
                    sb.append("• Connection Cycle ID: $cycleId\n")
                    sb.append("• DNS-SD Service Advertisement State: $dnsSdAdvState\n")
                    sb.append("• DNS-SD Service Discovery State: $dnsSdDiscState\n")
                    sb.append("• Service Discovery Generation: $serviceDiscoveryGeneration\n")
                    sb.append("• Discovered Mesh Services Count: $discoveredServicesCount\n")
                    sb.append("• Matched Coordinator Service Node ID: $expectedLogicalCandidate\n")
                    sb.append("• Bound Coordinator P2P Device Address: $boundCoordinatorDevice\n")"""

content = content.replace(sb_old, sb_new)

with open(file_path, 'w') as f:
    f.write(content)

file_path = r'D:\meshmind\meshmind\app\src\main\java\com\qualcomm\meshmind\viewmodel\DashboardViewModel.kt'
with open(file_path, 'r') as f:
    content = f.read()

content = content.replace('val isTransportActive = (tm as? com.qualcomm.meshmind.core.runtime.BaseSubsystem)?.getHealth()?.isOperational ?: false', '')
content = content.replace('val isScanning = (dm as? com.qualcomm.meshmind.core.runtime.BaseSubsystem)?.getHealth()?.isOperational ?: false', '')

with open(file_path, 'w') as f:
    f.write(content)

print("Done UI")
