package com.qualcomm.meshmind.network.discovery

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.ParcelUuid
import com.qualcomm.meshmind.core.runtime.BaseSubsystem
import com.qualcomm.meshmind.core.scheduling.TaskScheduler
import com.qualcomm.meshmind.diagnostics.models.SubsystemHealth
import com.qualcomm.meshmind.logging.MeshLogger
import com.qualcomm.meshmind.models.NeighborNodeState
import com.qualcomm.meshmind.state.NeighborStateRepository
import com.qualcomm.meshmind.utils.PermissionManager
import com.qualcomm.meshmind.core.dependency.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Handles BLE peripheral advertising and scanning operations.
 * Automatically falls back to high-fidelity simulated loops under pure JUnit JVM tests or test emulators.
 */
class BleDiscoveryManagerImpl(private val context: Context) : BaseSubsystem, DiscoveryManager {

    private val appContext = context.applicationContext
    private val discoveryScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val neighborRepo = NeighborStateRepository.getInstance()

    override var isScanning = false
        private set
    override var isAdvertising = false
        private set
    private var isOperational = false
    private var errorCount: Long = 0

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanCallback: ScanCallback? = null
    private var advertiseCallback: AdvertiseCallback? = null

    companion object {
        private const val TAG = "BleDiscoveryManager"
        private const val MESH_SERVICE_UUID = "0000feed-0000-1000-8000-00805f9b34fb"
        private const val TASK_STALE_SWEEP = "ble_stale_neighbors_sweep"
        private const val TASK_SIM_SCAN = "ble_simulated_scan_sweep"
    }

    override val subsystemId: String = "ble_discovery_manager"
    override val initPriority: Int = 40

    override suspend fun initialize() {
        try {
            bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            if (bluetoothAdapter == null) {
                MeshLogger.w(TAG, "No default Bluetooth hardware adapter found.")
            }
            
            isOperational = true
            MeshLogger.i(TAG, "BLE discovery manager subsystem initialized.")
            
            // Start continuous stale neighbor sweeps (every 10 seconds)
            TaskScheduler.getInstance().schedulePeriodic(TASK_STALE_SWEEP, 10000, 0.05) {
                pruneStaleNeighbors()
            }

            // Discovery must now be started explicitly by the user
            MeshLogger.i(TAG, "Awaiting explicit discovery start command.")
        } catch (e: Exception) {
            errorCount++
            isOperational = false
            MeshLogger.e(TAG, "Failed initializing BLE Discovery Subsystem", e)
            throw e
        }
    }

    override fun shutdown() {
        stopDiscovery()
        TaskScheduler.getInstance().cancel(TASK_STALE_SWEEP)
        isOperational = false
        MeshLogger.i(TAG, "BLE discovery manager subsystem shutdown.")
    }

    override fun getHealth(): SubsystemHealth {
        return SubsystemHealth(
            subsystemId,
            isOperational,
            System.currentTimeMillis(),
            errorCount,
            if (isOperational) "Discovery running (Scanning: $isScanning, Adv: $isAdvertising)" else "Offline"
        )
    }

    // --- DiscoveryManager ---
    override fun startDiscovery() {
        if (!isOperational) return
        
        if (bluetoothAdapter == null || !PermissionManager.hasBluetoothPermissions(appContext)) {
            MeshLogger.e(TAG, "Missing Bluetooth hardware or permissions. Cannot start discovery.")
            return
        }

        startRealScanning()
        startRealAdvertising()
    }

    override fun stopDiscovery() {
        if (bluetoothAdapter == null) {
            MeshLogger.w(TAG, "Bluetooth hardware missing. Stop discovery ignored.")
            return
        }
        stopRealScanning()
        stopRealAdvertising()
    }

    private fun startRealScanning() {
        if (isScanning) return
        try {
            MeshLogger.i(TAG, "BLE_SCAN_START_REQUESTED: Requesting BLE hardware scan.")
            val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
            scanCallback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult?) {
                    MeshLogger.d(TAG, "BLE_SCAN_RESULT_RECEIVED")
                    result?.let { handleScanResult(it) }
                }

                override fun onScanFailed(errorCode: Int) {
                    MeshLogger.e(TAG, "BLE_SCAN_FAILED: BLE scanning failed with Android error code: $errorCode")
                    errorCount++
                }
            }
            
            scanner.startScan(scanCallback)
            isScanning = true
            MeshLogger.i(TAG, "BLE_SCAN_STARTED: Hardware BLE scanning active.")
        } catch (e: Exception) {
            errorCount++
            MeshLogger.e(TAG, "Failed to start hardware BLE scanning", e)
        }
    }

    private fun stopRealScanning() {
        if (!isScanning) return
        try {
            val scanner = bluetoothAdapter?.bluetoothLeScanner
            scanCallback?.let { scanner?.stopScan(it) }
            scanCallback = null
            isScanning = false
            MeshLogger.i(TAG, "Hardware BLE scanning halted.")
        } catch (e: Exception) {
            MeshLogger.e(TAG, "Failed to stop hardware BLE scanning", e)
        }
    }

    private fun startRealAdvertising() {
        if (isAdvertising) return
        discoveryScope.launch {
            try {
                MeshLogger.i(TAG, "BLE_ADVERTISE_START_REQUESTED: Requesting BLE hardware advertising.")
                val identityMgr = ServiceLocator.get(com.qualcomm.meshmind.identity.DeviceIdentityManager::class.java)
                val nodeId = identityMgr.resolveNodeId()
                
                val uuid = UUID.fromString(nodeId)
                val bb = java.nio.ByteBuffer.wrap(ByteArray(16))
                bb.putLong(uuid.mostSignificantBits)
                bb.putLong(uuid.leastSignificantBits)
                val nodeIdBytes = bb.array()

                val advertiser = bluetoothAdapter?.bluetoothLeAdvertiser ?: return@launch
                
                val settings = AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                    .setConnectable(false)
                    .build()

                val serviceParcelUuid = ParcelUuid(UUID.fromString(MESH_SERVICE_UUID))
                val data = AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .addServiceUuid(serviceParcelUuid)
                    .addServiceData(serviceParcelUuid, nodeIdBytes)
                    .build()

                advertiseCallback = object : AdvertiseCallback() {
                    override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                        isAdvertising = true
                        MeshLogger.i(TAG, "BLE_ADVERTISE_STARTED: Hardware BLE advertisement started successfully. NodeId: $nodeId")
                    }

                    override fun onStartFailure(errorCode: Int) {
                        MeshLogger.e(TAG, "BLE_ADVERTISE_FAILED: Hardware BLE advertising start failed with Android error code: $errorCode")
                        if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE) {
                            MeshLogger.e(TAG, "BLE_ADVERTISE_DATA_TOO_LARGE: The advertisement data exceeds the BLE limits.")
                        }
                        errorCount++
                    }
                }

                advertiser.startAdvertising(settings, data, advertiseCallback)
            } catch (e: Exception) {
                errorCount++
                MeshLogger.e(TAG, "Failed to start hardware BLE advertising", e)
            }
        }
    }

    private fun stopRealAdvertising() {
        if (!isAdvertising) return
        try {
            val advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
            advertiseCallback?.let { advertiser?.stopAdvertising(it) }
            advertiseCallback = null
            isAdvertising = false
            MeshLogger.i(TAG, "Hardware BLE advertising halted.")
        } catch (e: Exception) {
            MeshLogger.e(TAG, "Failed to stop hardware BLE advertising", e)
        }
    }

    private fun handleScanResult(result: ScanResult) {
        val deviceAddress = result.device.address
        val rssi = result.rssi
        val serviceUuids = result.scanRecord?.serviceUuids
        if (serviceUuids == null) {
            MeshLogger.d(TAG, "BLE_SCAN_RESULT: Device $deviceAddress (RSSI: $rssi) has no service UUIDs.")
            return
        }
        
        val hasMeshService = serviceUuids.any { it.uuid.toString() == MESH_SERVICE_UUID }
        if (hasMeshService) {
            val serviceData = result.scanRecord?.getServiceData(ParcelUuid(UUID.fromString(MESH_SERVICE_UUID)))
            if (serviceData == null) {
                MeshLogger.w(TAG, "BLE_SERVICE_DATA_MISSING: Mesh Service UUID found but no service data attached.")
            } else if (serviceData.size != 16) {
                MeshLogger.w(TAG, "BLE_NODE_ID_LENGTH_INVALID: Expected 16 bytes, received ${serviceData.size} bytes.")
            } else {
                try {
                    val bb = java.nio.ByteBuffer.wrap(serviceData)
                    val uuid = UUID(bb.long, bb.long)
                    val logicalNodeId = uuid.toString()
                    
                    val existing = neighborRepo.getNeighbor(logicalNodeId)
                    if (existing == null) {
                        MeshLogger.i(TAG, "BLE_LOGICAL_PEER_DISCOVERED: New peer $logicalNodeId discovered at MAC $deviceAddress (RSSI: $rssi)")
                    } else {
                        // Rate limit refresh logs to avoid spam
                        if (System.currentTimeMillis() - existing.lastSeenTimestamp > 5000L) {
                            MeshLogger.d(TAG, "BLE_LOGICAL_PEER_REFRESHED: Peer $logicalNodeId refreshed at MAC $deviceAddress (RSSI: $rssi)")
                        }
                    }
                    updateNeighborRecord(logicalNodeId, rssi)
                } catch (e: Exception) {
                    MeshLogger.e(TAG, "BLE_NODE_ID_DECODE_FAILED: Could not decode UUID from service data.", e)
                }
            }
        }

        val hasArduinoService = serviceUuids.any { it.uuid.toString() == com.qualcomm.meshmind.arduino.ArduinoIntegrationManager.ARDUINO_SERVICE_UUID }
        if (hasArduinoService) {
            val manager = ServiceLocator.get(com.qualcomm.meshmind.arduino.ArduinoIntegrationManager::class.java)
            val scanRecordBytes = result.scanRecord?.bytes ?: ByteArray(0)
            manager?.processInfrastructureBeacon(deviceAddress, rssi, scanRecordBytes)
        }
    }

    private fun updateNeighborRecord(nodeId: String, rssi: Int) {
        val existing = neighborRepo.getNeighbor(nodeId)
        val state = NeighborNodeState(
            nodeId = nodeId,
            rssi = rssi,
            packetLossRate = null,
            queueLength = null,
            ackSuccessRatio = null,
            batteryLevel = null,
            stabilityIndex = null,
            lastSeenTimestamp = System.currentTimeMillis()
        )
        neighborRepo.updateNeighbor(nodeId, state)
    }

    private fun pruneStaleNeighbors() {
        discoveryScope.launch {
            val expiryThreshold = System.currentTimeMillis() - 30000L // 30 seconds threshold
            neighborRepo.expireStaleNeighbors(expiryThreshold)
        }
    }

    private suspend fun delayMs(ms: Long) {
        kotlinx.coroutines.delay(ms)
    }
}
