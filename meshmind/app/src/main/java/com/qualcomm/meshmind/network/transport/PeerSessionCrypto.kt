package com.qualcomm.meshmind.network.transport

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Handles ECDH key agreement and session-aware MMF wire encryption per peer connection.
 */
class PeerSessionCrypto {
    private val keyPair: KeyPair
    private var sessionKey: SecretKey? = null

    init {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        keyPair = kpg.generateKeyPair()
    }

    /**
     * Retrieves the X.509 encoded public key for exchange during handshake.
     */
    fun getPublicKey(): ByteArray {
        return keyPair.public.encoded
    }

    /**
     * Derives the symmetric AES session key using HKDF-SHA256 after exchanging EC public keys.
     */
    fun deriveSessionKey(remotePublicKeyBytes: ByteArray, localNodeId: String, remoteNodeId: String) {
        val keyFactory = KeyFactory.getInstance("EC")
        val x509KeySpec = X509EncodedKeySpec(remotePublicKeyBytes)
        val remotePublicKey = keyFactory.generatePublic(x509KeySpec)

        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(keyPair.private)
        keyAgreement.doPhase(remotePublicKey, true)
        val sharedSecret = keyAgreement.generateSecret()

        val canonicalLocal = localNodeId.lowercase(java.util.Locale.ROOT).trim()
        val canonicalRemote = remoteNodeId.lowercase(java.util.Locale.ROOT).trim()
        val info = if (canonicalLocal < canonicalRemote) {
            (canonicalLocal + canonicalRemote).toByteArray(Charsets.UTF_8)
        } else {
            (canonicalRemote + canonicalLocal).toByteArray(Charsets.UTF_8)
        }

        val salt = ByteArray(32) // Zero-filled salt
        sessionKey = hkdfExtractAndExpand(salt, sharedSecret, info, 32)
    }

    /**
     * Encrypts the payload using AES/GCM/NoPadding with the derived session key.
     */
    fun encryptPayload(payload: ByteArray): Pair<ByteArray, ByteArray> {
        val key = sessionKey ?: throw IllegalStateException("Session key not derived")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val nonce = ByteArray(12)
        SecureRandom().nextBytes(nonce)
        val spec = GCMParameterSpec(128, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, key, spec)
        val ciphertext = cipher.doFinal(payload)
        return Pair(nonce, ciphertext)
    }

    /**
     * Decrypts the payload using AES/GCM/NoPadding with the derived session key.
     */
    fun decryptPayload(nonce: ByteArray, ciphertext: ByteArray): ByteArray {
        val key = sessionKey ?: throw IllegalStateException("Session key not derived")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, nonce)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        return cipher.doFinal(ciphertext)
    }

    private fun hkdfExtractAndExpand(salt: ByteArray, ikm: ByteArray, info: ByteArray, outLen: Int): SecretKey {
        // Standard HKDF implementation via JCA Mac (HmacSHA256)
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info)
        mac.update(1.toByte())
        val okm = mac.doFinal() // 32 bytes from SHA256
        
        val keyBytes = okm.copyOf(outLen)
        return SecretKeySpec(keyBytes, "AES")
    }

    /**
     * Returns a truncated SHA-256 fingerprint of the derived session key for diagnostics.
     */
    fun getSessionKeyFingerprint(): String {
        val key = sessionKey ?: return "none"
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(key.encoded)
        return hash.take(4).joinToString("") { "%02x".format(it) }
    }
}
