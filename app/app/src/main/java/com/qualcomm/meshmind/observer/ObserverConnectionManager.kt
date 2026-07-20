package com.qualcomm.meshmind.observer

import com.qualcomm.meshmind.logging.MeshLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.OutputStream
import java.net.Socket
import kotlin.math.min

class ObserverConnectionManager {
    companion object {
        private const val TAG = "ObserverConnectionManager"
        private const val INITIAL_BACKOFF_MS = 1000L
        private const val MAX_BACKOFF_MS = 30000L
        private const val HEARTBEAT_INTERVAL_MS = 10000L
        private const val SOCKET_TIMEOUT_MS = 5000
    }

    private val _connectionState = MutableStateFlow("INACTIVE")
    val connectionState: StateFlow<String> = _connectionState
    
    private val _lastFailureReason = MutableStateFlow("NONE")
    val lastFailureReason: StateFlow<String> = _lastFailureReason

    private var currentSocket: Socket? = null
    private var outputStream: OutputStream? = null

    suspend fun maintainConnection(endpoint: ObserverEndpoint) = withContext(Dispatchers.IO) {
        var backoff = INITIAL_BACKOFF_MS
        
        while (isActive) {
            _connectionState.value = "CONNECTING"
            try {
                MeshLogger.i(TAG, "Attempting to connect to Observer at ${endpoint.host}:${endpoint.port}")
                val socket = Socket(endpoint.host, endpoint.port)
                socket.soTimeout = SOCKET_TIMEOUT_MS
                currentSocket = socket
                outputStream = socket.getOutputStream()
                
                _connectionState.value = "ACTIVE"
                _lastFailureReason.value = "NONE"
                backoff = INITIAL_BACKOFF_MS // Reset backoff on success
                
                MeshLogger.i(TAG, "Observer Gateway ACTIVE. Pumping queue...")
                pumpQueue(outputStream!!)
                
            } catch (e: Exception) {
                _connectionState.value = "RECONNECTING"
                _lastFailureReason.value = e.javaClass.simpleName
                MeshLogger.e(TAG, "Observer connection failed: ${e.message}")
            } finally {
                cleanupSocket()
            }
            
            // Exponential backoff
            if (isActive) {
                delay(backoff)
                backoff = min(backoff * 2, MAX_BACKOFF_MS)
            }
        }
        _connectionState.value = "INACTIVE"
    }

    private suspend fun pumpQueue(outStream: OutputStream) {
        var lastHeartbeat = System.currentTimeMillis()
        while (currentSocket?.isConnected == true && !currentSocket!!.isClosed) {
            val record = withTimeoutOrNull(HEARTBEAT_INTERVAL_MS) {
                ObserverPacketTap.receiveObservation()
            }
            
            if (record != null) {
                val frame = ObserverFrameCodec.encodeFrame(record.recordType, record.metadataJson, record.canonicalPayload ?: ByteArray(0))
                if (frame != null) {
                    outStream.write(frame)
                    outStream.flush()
                    if (record.recordType == ObserverFrameCodec.TYPE_PACKET_OBSERVATION) {
                        ObserverPacketTap.forwardedCount++
                    }
                }
            } else {
                // Heartbeat timeout reached, send heartbeat
                val now = System.currentTimeMillis()
                if (now - lastHeartbeat >= HEARTBEAT_INTERVAL_MS) {
                    val hbMeta = "{\"timestamp\":$now,\"type\":\"GATEWAY_HEARTBEAT\"}"
                    val hbFrame = ObserverFrameCodec.encodeFrame(ObserverFrameCodec.TYPE_GATEWAY_HEARTBEAT, hbMeta)
                    if (hbFrame != null) {
                        outStream.write(hbFrame)
                        outStream.flush()
                    }
                    lastHeartbeat = now
                }
            }
        }
    }

    private fun cleanupSocket() {
        try {
            outputStream?.close()
        } catch (ignored: Exception) {}
        try {
            currentSocket?.close()
        } catch (ignored: Exception) {}
        currentSocket = null
        outputStream = null
    }
    
    fun disconnect() {
        cleanupSocket()
        _connectionState.value = "INACTIVE"
    }
}
