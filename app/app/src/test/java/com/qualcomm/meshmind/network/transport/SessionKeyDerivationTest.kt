package com.qualcomm.meshmind.network.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.UUID

class SessionKeyDerivationTest {

    @Test
    fun `deriveSessionKey establishes symmetric keys from ECDH public key exchange`() {
        val aliceNodeId = UUID.randomUUID().toString()
        val bobNodeId = UUID.randomUUID().toString()

        val aliceCrypto = PeerSessionCrypto()
        val bobCrypto = PeerSessionCrypto()

        val alicePubKey = aliceCrypto.getPublicKey()
        val bobPubKey = bobCrypto.getPublicKey()

        assertNotNull(alicePubKey)
        assertNotNull(bobPubKey)
        assertNotEquals(alicePubKey, bobPubKey)

        // Exchange keys and derive
        aliceCrypto.deriveSessionKey(bobPubKey, aliceNodeId, bobNodeId)
        bobCrypto.deriveSessionKey(alicePubKey, bobNodeId, aliceNodeId)

        val aliceFingerprint = aliceCrypto.getSessionKeyFingerprint()
        val bobFingerprint = bobCrypto.getSessionKeyFingerprint()

        assertNotEquals("none", aliceFingerprint)
        assertEquals("Fingerprints must match exactly, proving symmetric HKDF derivation", aliceFingerprint, bobFingerprint)

        // Verify encryption and decryption works symmetrically
        val plaintext = "Hello MeshMind!".toByteArray(Charsets.UTF_8)
        
        val (nonce, ciphertext) = aliceCrypto.encryptPayload(plaintext)
        val decrypted = bobCrypto.decryptPayload(nonce, ciphertext)

        assertArrayEquals(plaintext, decrypted)
    }
}
