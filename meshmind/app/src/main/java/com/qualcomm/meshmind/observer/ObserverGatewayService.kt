package com.qualcomm.meshmind.observer

import android.content.Context
import com.qualcomm.meshmind.core.runtime.BaseSubsystem
import com.qualcomm.meshmind.logging.MeshLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ObserverGatewayService(private val context: Context) : BaseSubsystem {
    override val subsystemId: String = "observer_gateway_service"
    override val initPriority: Int = 90

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val discoveryManager = ObserverDiscoveryManager(context)
    private val connectionManager = ObserverConnectionManager()
    
    private var connectionJob: Job? = null
    
    val discoveryState = discoveryManager.discoveryState
    val endpoint = discoveryManager.endpoint
    val connectionState = connectionManager.connectionState
    val lastFailureReason = connectionManager.lastFailureReason

    companion object {
        private const val TAG = "ObserverGatewayService"
        
        @Volatile
        private var instance: ObserverGatewayService? = null
        
        fun getInstance(context: Context): ObserverGatewayService {
            return instance ?: synchronized(this) {
                instance ?: ObserverGatewayService(context).also { instance = it }
            }
        }
    }

    override suspend fun initialize() {
        MeshLogger.i(TAG, "Initializing Observer Gateway Service")
        
        // Start mDNS Discovery
        discoveryManager.startDiscovery()
        
        // Monitor for discovered endpoints and manage connection lifecycle
        serviceScope.launch {
            discoveryManager.endpoint.collect { endpoint ->
                connectionJob?.cancel()
                connectionJob = null
                connectionManager.disconnect()
                
                if (endpoint != null) {
                    MeshLogger.i(TAG, "New endpoint discovered. Starting connection job.")
                    connectionJob = launch {
                        connectionManager.maintainConnection(endpoint)
                    }
                }
            }
        }
    }

    fun getDroppedCount() = ObserverPacketTap.droppedCopyCount
    fun getForwardedCount() = ObserverPacketTap.forwardedCount
    
    override fun shutdown() {
        MeshLogger.i(TAG, "Shutting down Observer Gateway Service")
        discoveryManager.stopDiscovery()
        connectionJob?.cancel()
        connectionManager.disconnect()
    }
    
    override fun getHealth(): com.qualcomm.meshmind.diagnostics.models.SubsystemHealth {
        return com.qualcomm.meshmind.diagnostics.models.SubsystemHealth(
            subsystemId,
            true,
            System.currentTimeMillis(),
            0,
            "Gateway Active, Queue Depth: ${ObserverPacketTap.getQueueDepth()}"
        )
    }
}
