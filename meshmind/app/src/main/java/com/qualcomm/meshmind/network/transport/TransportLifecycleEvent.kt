package com.qualcomm.meshmind.network.transport

sealed class TransportLifecycleEvent {
    data class TcpClientConnectStarted(val targetIp: String) : TransportLifecycleEvent()
    data class TcpClientSocketConnected(val targetIp: String) : TransportLifecycleEvent()
    data class HandshakeStarted(val targetIp: String, val isServer: Boolean) : TransportLifecycleEvent()
    data class HandshakeRemoteIdReceived(val remoteNodeId: String) : TransportLifecycleEvent()
    data class SessionKeyDerived(val remoteNodeId: String) : TransportLifecycleEvent()
    data class PeerSessionRegisterStarted(val remoteNodeId: String) : TransportLifecycleEvent()
    data class PeerSessionRegistered(val remoteNodeId: String) : TransportLifecycleEvent()
    data class TransportFailure(val targetIp: String?, val remoteNodeId: String?, val reason: String) : TransportLifecycleEvent()
}
