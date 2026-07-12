package com.qualcomm.meshmind.security

import com.qualcomm.meshmind.packet.builder.PacketBuilder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CryptoSecurityTest {

    private class FakeCryptoProvider : CryptographicProvider {
        // Fake XOR cipher for local JVM testing
        override fun encrypt(plainText: ByteArray): Pair<ByteArray, ByteArray> {
            val iv = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
            val cipher = ByteArray(plainText.size) { i -> (plainText[i].toInt() xor 0xFF).toByte() }
            return Pair(iv, cipher)
        }

        override fun decrypt(iv: ByteArray, cipherText: ByteArray): ByteArray {
            return ByteArray(cipherText.size) { i -> (cipherText[i].toInt() xor 0xFF).toByte() }
        }
    }

    @Test
    fun testSecurityManagerEncryptionDecryptionFlow() {
        val provider = FakeCryptoProvider()
        val securityManager = SecurityManager(provider)

        val plainText = "Confidential emergency payload".toByteArray(Charsets.UTF_8)
        
        val frame = PacketBuilder()
            .setSourceNodeId("node_x")
            .setDestinationNodeId("node_y")
            .setPayload(plainText)
            .build()

        assertNull(frame.iv)

        // 1. Encrypt frame
        val encryptedFrame = securityManager.encryptFramePayload(frame)
        assertNotNull(encryptedFrame.iv)
        assertEquals(12, encryptedFrame.iv?.size)
        
        // 2. Decrypt frame
        val decryptedFrame = securityManager.decryptFramePayload(encryptedFrame)
        assertNull(decryptedFrame.iv)
        assertArrayEquals(plainText, decryptedFrame.payload)
    }
}
