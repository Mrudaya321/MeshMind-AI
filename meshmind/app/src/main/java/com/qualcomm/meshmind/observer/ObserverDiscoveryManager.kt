package com.qualcomm.meshmind.observer

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.qualcomm.meshmind.logging.MeshLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ObserverEndpoint(val host: String, val port: Int)

class ObserverDiscoveryManager(private val context: Context) {
    companion object {
        private const val TAG = "ObserverDiscoveryManager"
        private const val SERVICE_TYPE = "_meshmind-observer._tcp"
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var resolveListener: NsdManager.ResolveListener? = null

    private val _endpoint = MutableStateFlow<ObserverEndpoint?>(null)
    val endpoint: StateFlow<ObserverEndpoint?> = _endpoint
    
    private val _discoveryState = MutableStateFlow("INACTIVE")
    val discoveryState: StateFlow<String> = _discoveryState

    fun startDiscovery() {
        if (discoveryListener != null) return
        
        _discoveryState.value = "SEARCHING"
        MeshLogger.i(TAG, "Starting mDNS Observer Discovery for $SERVICE_TYPE")

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                MeshLogger.i(TAG, "Service discovery started")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                MeshLogger.i(TAG, "Service found: ${service.serviceName}")
                if (service.serviceType.contains(SERVICE_TYPE)) {
                    resolveService(service)
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                MeshLogger.w(TAG, "Service lost: ${service.serviceName}")
                if (_endpoint.value?.port == service.port) {
                    _endpoint.value = null
                    _discoveryState.value = "SEARCHING"
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                MeshLogger.i(TAG, "Discovery stopped: $serviceType")
                _discoveryState.value = "INACTIVE"
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                MeshLogger.e(TAG, "Discovery failed: Error code: $errorCode")
                stopDiscovery()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                MeshLogger.e(TAG, "Stop discovery failed: Error code: $errorCode")
            }
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            MeshLogger.e(TAG, "Failed to start mDNS discovery", e)
            _discoveryState.value = "FAILED"
        }
    }

    private fun resolveService(service: NsdServiceInfo) {
        resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                MeshLogger.e(TAG, "Resolve failed: $errorCode")
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                MeshLogger.i(TAG, "Resolve Succeeded. ${serviceInfo.serviceName}")
                if (serviceInfo.host != null) {
                    val hostAddress = serviceInfo.host.hostAddress
                    val port = serviceInfo.port
                    if (hostAddress != null) {
                        MeshLogger.i(TAG, "Resolved Observer at $hostAddress:$port")
                        _endpoint.value = ObserverEndpoint(hostAddress, port)
                        _discoveryState.value = "FOUND"
                    }
                }
            }
        }
        try {
            nsdManager.resolveService(service, resolveListener)
        } catch (e: Exception) {
            MeshLogger.w(TAG, "Resolve already in progress: ${e.message}")
        }
    }

    fun stopDiscovery() {
        if (discoveryListener != null) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener)
            } catch (ignored: Exception) {}
            discoveryListener = null
        }
        _discoveryState.value = "INACTIVE"
    }
}
