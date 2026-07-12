package com.qualcomm.meshmind.communication

sealed class SendResult {
    data class Enqueued(val packetId: String) : SendResult()
    data class LocalPersistenceFailed(val reason: String) : SendResult()
    data class EncryptionFailed(val reason: String) : SendResult()
    data class PacketEnqueueFailed(val reason: String) : SendResult()
}
