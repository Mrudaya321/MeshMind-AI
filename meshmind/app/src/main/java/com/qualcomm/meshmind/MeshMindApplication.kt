package com.qualcomm.meshmind

import android.app.Application
import com.qualcomm.meshmind.configuration.ConfigManager
import com.qualcomm.meshmind.core.dependency.ServiceLocator
import com.qualcomm.meshmind.core.lifecycle.LifecycleCoordinator
import com.qualcomm.meshmind.core.runtime.SubsystemManager
import com.qualcomm.meshmind.database.DatabaseManager
import com.qualcomm.meshmind.diagnostics.DiagnosticsManager
import com.qualcomm.meshmind.diagnostics.HealthMonitor
import com.qualcomm.meshmind.digitaltwin.communication.DigitalTwinClient
import com.qualcomm.meshmind.classification.EmergencyClassifier
import com.qualcomm.meshmind.logging.MeshLogger
import com.qualcomm.meshmind.state.ApplicationState
import com.qualcomm.meshmind.storage.StorageManager
import com.qualcomm.meshmind.telemetry.SystemTelemetryCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Custom application class driving boot initializations in Kotlin.
 */
class MeshMindApplication : Application() {

    companion object {
        val processInstanceId = java.util.UUID.randomUUID().toString().take(8)
        val processStartedAtElapsedRealtime = android.os.SystemClock.elapsedRealtime()
    }

    private val applicationScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate() {
        super.onCreate()

        // 1. Logging framework boot up
        MeshLogger.initialize(filesDir)
        MeshLogger.i("MeshMindApplication", "Starting bootloader sequence...")

        // 2. Lifecycle coordinator connection
        LifecycleCoordinator.getInstance().startMonitoring()

        // 3. Subsystem allocation & Dependency registration
        val storageManager = StorageManager(this)
        val configManager = ConfigManager(this)
        val databaseManager = DatabaseManager(this)
        val telemetryCollector = SystemTelemetryCollector(this)
        val mmpRoutingEngine = com.qualcomm.meshmind.network.routing.MmpRoutingEngineImpl()
        val relayManager = com.qualcomm.meshmind.communication.RelayManager.getInstance()
        val bleDiscoveryManager = com.qualcomm.meshmind.network.discovery.BleDiscoveryManagerImpl(this)
        val wifiDirectTransport = com.qualcomm.meshmind.network.transport.WifiDirectTransportManager(this)
        val wifiDirectConnection = com.qualcomm.meshmind.network.transport.WifiDirectConnectionManager(this)
        val digitalTwinClient = DigitalTwinClient()
        val telemetryManager = com.qualcomm.meshmind.telemetry.TelemetryManager.getInstance(this)
        val arduinoManager = com.qualcomm.meshmind.arduino.ArduinoIntegrationManager.getInstance(this)
        val performanceManager = com.qualcomm.meshmind.performance.PerformanceManager.getInstance(this)
        val validationManager = com.qualcomm.meshmind.validation.ValidationManager.getInstance()
        val releaseManager = com.qualcomm.meshmind.release.ReleaseManager.getInstance()
        val versionManager = com.qualcomm.meshmind.release.VersionManager.getInstance()
        val diagnosticsManager = DiagnosticsManager()
        val healthMonitor = HealthMonitor()
        
        // Initialize AI Classifier
        val emergencyClassifier = EmergencyClassifier(this)
        applicationScope.launch {
            emergencyClassifier.initialize()
        }
        
        // Phase 13 additions
        val packetManager = com.qualcomm.meshmind.packet.PacketManager.getInstance()
        val heartbeatAdvertiser = com.qualcomm.meshmind.network.heartbeat.HeartbeatAdvertiserImpl()

        ServiceLocator.register(StorageManager::class.java, storageManager)
        ServiceLocator.register(ConfigManager::class.java, configManager)
        ServiceLocator.register(DatabaseManager::class.java, databaseManager)
        ServiceLocator.register(SystemTelemetryCollector::class.java, telemetryCollector)
        ServiceLocator.register(com.qualcomm.meshmind.network.routing.RoutingEngine::class.java, mmpRoutingEngine)
        ServiceLocator.register(com.qualcomm.meshmind.communication.RelayManager::class.java, relayManager)
        ServiceLocator.register(com.qualcomm.meshmind.network.discovery.DiscoveryManager::class.java, bleDiscoveryManager)
        ServiceLocator.register(com.qualcomm.meshmind.network.transport.TransportManager::class.java, wifiDirectTransport)
        ServiceLocator.register(com.qualcomm.meshmind.network.transport.WifiDirectTransportManager::class.java, wifiDirectTransport)
        ServiceLocator.register(com.qualcomm.meshmind.network.transport.WifiDirectConnectionManager::class.java, wifiDirectConnection)
        ServiceLocator.register(DigitalTwinClient::class.java, digitalTwinClient)
        ServiceLocator.register(com.qualcomm.meshmind.telemetry.TelemetryManager::class.java, telemetryManager)
        ServiceLocator.register(com.qualcomm.meshmind.arduino.ArduinoIntegrationManager::class.java, arduinoManager)
        ServiceLocator.register(com.qualcomm.meshmind.performance.PerformanceManager::class.java, performanceManager)
        ServiceLocator.register(com.qualcomm.meshmind.validation.ValidationManager::class.java, validationManager)
        ServiceLocator.register(com.qualcomm.meshmind.release.ReleaseManager::class.java, releaseManager)
        ServiceLocator.register(com.qualcomm.meshmind.release.VersionManager::class.java, versionManager)
        ServiceLocator.register(DiagnosticsManager::class.java, diagnosticsManager)
        ServiceLocator.register(HealthMonitor::class.java, healthMonitor)
        ServiceLocator.register(EmergencyClassifier::class.java, emergencyClassifier)
        ServiceLocator.register(com.qualcomm.meshmind.packet.PacketManager::class.java, packetManager)
        ServiceLocator.register(com.qualcomm.meshmind.network.heartbeat.HeartbeatAdvertiser::class.java, heartbeatAdvertiser)

        // 4. Registry operations
        val manager = SubsystemManager.getInstance()
        manager.registerSubsystem(storageManager)
        manager.registerSubsystem(configManager)
        manager.registerSubsystem(databaseManager)
        manager.registerSubsystem(telemetryCollector)
        manager.registerSubsystem(mmpRoutingEngine)
        manager.registerSubsystem(relayManager)
        manager.registerSubsystem(bleDiscoveryManager)
        manager.registerSubsystem(wifiDirectTransport)
        manager.registerSubsystem(wifiDirectConnection)
        manager.registerSubsystem(digitalTwinClient)
        manager.registerSubsystem(telemetryManager)
        manager.registerSubsystem(arduinoManager)
        manager.registerSubsystem(performanceManager)
        manager.registerSubsystem(validationManager)
        manager.registerSubsystem(releaseManager)
        manager.registerSubsystem(versionManager)
        manager.registerSubsystem(diagnosticsManager)
        manager.registerSubsystem(healthMonitor)
        manager.registerSubsystem(packetManager)
        manager.registerSubsystem(heartbeatAdvertiser)

        // Register repositories to ServiceLocator
        com.qualcomm.meshmind.repository.RepositoryRegistry.registerAll()
        
        // Register DeviceIdentityManager
        val identityManager = com.qualcomm.meshmind.identity.DeviceIdentityManager.getInstance(this)
        ServiceLocator.register(com.qualcomm.meshmind.identity.DeviceIdentityManager::class.java, identityManager)

        // 5. Orchestrated Boot Loader startup sequence
        com.qualcomm.meshmind.core.runtime.RuntimeCoordinator.getInstance(this).startInitialization()
    }

    override fun onTerminate() {
        super.onTerminate()
        SubsystemManager.getInstance().shutdownAll()
        MeshLogger.i("MeshMindApplication", "Node execution terminated.")
    }
}
