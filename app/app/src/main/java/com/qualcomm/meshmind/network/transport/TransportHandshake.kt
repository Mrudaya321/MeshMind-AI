package com.qualcomm.meshmind.network.transport

import com.qualcomm.meshmind.logging.MeshLogger
import java.io.DataInputStream
import java.io.DataOutputStream

object TransportHandshake {
    private const val TAG = "TransportHandshake"
    private const val MAGIC: Short = 0x4D4D // "MM"
    private const val VERSION: Byte = 2
    private const val MAX_NODE_ID_LENGTH = 128
    private const val MAX_PUBLIC_KEY_LENGTH = 1024

    data class HandshakeResult(
        val remoteNodeId: String,
        val remotePublicKey: ByteArray
    )

    fun writeHandshake(dos: DataOutputStream, localNodeId: String, publicKeyBytes: ByteArray) {
        val idBytes = localNodeId.toByteArray(Charsets.UTF_8)
        if (idBytes.size > MAX_NODE_ID_LENGTH) {
            MeshLogger.e(TAG, "TRANSPORT_HANDSHAKE_FAILED: INVALID_NODE_ID (Too long)")
            throw IllegalArgumentException("NodeId too long")
        }
        if (publicKeyBytes.size > MAX_PUBLIC_KEY_LENGTH) {
            MeshLogger.e(TAG, "TRANSPORT_HANDSHAKE_FAILED: INVALID_PUBLIC_KEY_LENGTH (Too long)")
            throw IllegalArgumentException("Public key too long")
        }
        
        dos.writeShort(MAGIC.toInt())
        dos.writeByte(VERSION.toInt())
        
        dos.writeByte(idBytes.size)
        dos.write(idBytes)
        
        dos.writeShort(publicKeyBytes.size)
        dos.write(publicKeyBytes)
        
        dos.flush()
        MeshLogger.d(TAG, "TRANSPORT_HANDSHAKE_LOCAL_SENT: Sent local NodeId: $localNodeId")
    }

    fun readHandshake(dis: DataInputStream, localNodeId: String): HandshakeResult {
        val magic = dis.readShort()
        if (magic != MAGIC) {
            MeshLogger.e(TAG, "TRANSPORT_HANDSHAKE_FAILED: INVALID_MAGIC ($magic)")
            throw IllegalStateException("Invalid handshake magic: $magic")
        }
        
        val version = dis.readByte()
        if (version != VERSION) {
            MeshLogger.e(TAG, "TRANSPORT_HANDSHAKE_FAILED: INVALID_VERSION ($version)")
            throw IllegalStateException("Unsupported handshake version: $version")
        }
        
        val idLength = dis.readByte().toInt()
        if (idLength <= 0 || idLength > MAX_NODE_ID_LENGTH) {
            MeshLogger.e(TAG, "TRANSPORT_HANDSHAKE_FAILED: INVALID_NODE_ID_LENGTH ($idLength)")
            throw IllegalStateException("Invalid handshake NodeId length: $idLength")
        }
        
        val idBytes = ByteArray(idLength)
        dis.readFully(idBytes)
        val remoteNodeId = com.qualcomm.meshmind.identity.DeviceIdentityManager.canonicalNodeId(String(idBytes, Charsets.UTF_8))
        
        val pubKeyLength = dis.readShort().toInt()
        if (pubKeyLength <= 0 || pubKeyLength > MAX_PUBLIC_KEY_LENGTH) {
            MeshLogger.e(TAG, "TRANSPORT_HANDSHAKE_FAILED: INVALID_PUBLIC_KEY_LENGTH ($pubKeyLength)")
            throw IllegalStateException("Invalid handshake Public Key length: $pubKeyLength")
        }
        
        val remotePublicKey = ByteArray(pubKeyLength)
        dis.readFully(remotePublicKey)
        
        if (remoteNodeId == localNodeId) {
            MeshLogger.e(TAG, "TRANSPORT_HANDSHAKE_FAILED: SELF_CONNECT_DETECTED (Remote peer claimed our own NodeId)")
            throw IllegalStateException("Handshake failed: remote peer claimed our own NodeId")
        }
        
        MeshLogger.d(TAG, "TRANSPORT_HANDSHAKE_REMOTE_VALIDATED: Remote NodeId: $remoteNodeId")
        return HandshakeResult(remoteNodeId, remotePublicKey)
    }
}
