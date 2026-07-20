package com.qualcomm.meshmind.diagnostics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

data class ChatDeliveryTrace(
    val traceId: String,
    val sourceNodeId: String,
    val destinationNodeId: String,
    var furthestStage: String,
    var resolvedNextHop: String? = null,
    var isDirectVerifiedSessionFound: Boolean = false,
    var isRouteLookupSuccessful: Boolean = false,
    var isSocketWriteSuccessful: Boolean = false,
    var isRemoteFrameObserved: Boolean = false,
    var terminalFailureReason: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun getFormattedLine(): String {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val timeStr = sdf.format(Date(timestamp))
        return "[$timeStr] Trace: ${traceId.take(8)}...\n  Src: ${sourceNodeId.take(8)}... -> Dest: ${destinationNodeId.take(8)}...\n  Stage: $furthestStage\n  DirectSession: $isDirectVerifiedSessionFound | RouteLookup: $isRouteLookupSuccessful\n  NextHop: ${resolvedNextHop?.take(8) ?: "NONE"}\n  SocketWrite: $isSocketWriteSuccessful | RemoteFrame: $isRemoteFrameObserved\n  TerminalReason: ${terminalFailureReason ?: "NONE"}"
    }
}

object ChatDeliveryDiagnostics {
    private val traces = ConcurrentHashMap<String, ChatDeliveryTrace>()
    private val traceOrder = ConcurrentLinkedQueue<String>()
    
    fun logEvent(
        traceId: String,
        sourceNodeId: String = "UNKNOWN",
        destinationNodeId: String = "UNKNOWN",
        stage: String,
        nextHop: String? = null,
        directSessionFound: Boolean? = null,
        routeLookupSuccess: Boolean? = null,
        socketWriteSuccess: Boolean? = null,
        remoteFrameObserved: Boolean? = null,
        terminalReason: String? = null
    ) {
        val trace = traces.getOrPut(traceId) {
            traceOrder.add(traceId)
            while (traceOrder.size > 20) {
                val oldId = traceOrder.poll()
                oldId?.let { traces.remove(it) }
            }
            ChatDeliveryTrace(traceId, sourceNodeId, destinationNodeId, stage)
        }
        
        trace.furthestStage = stage
        nextHop?.let { trace.resolvedNextHop = it }
        directSessionFound?.let { trace.isDirectVerifiedSessionFound = it }
        routeLookupSuccess?.let { trace.isRouteLookupSuccessful = it }
        socketWriteSuccess?.let { trace.isSocketWriteSuccessful = it }
        remoteFrameObserved?.let { trace.isRemoteFrameObserved = it }
        terminalReason?.let { trace.terminalFailureReason = it }
    }
    
    fun getRecentTraces(): List<ChatDeliveryTrace> {
        return traceOrder.mapNotNull { traces[it] }.toList().reversed()
    }
}
