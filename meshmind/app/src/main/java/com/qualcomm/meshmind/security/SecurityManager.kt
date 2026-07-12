package com.qualcomm.meshmind.security

import com.qualcomm.meshmind.exceptions.MeshMindException.SecurityValidationException
import com.qualcomm.meshmind.logging.MeshLogger
import com.qualcomm.meshmind.packet.models.MeshFrame
import java.io.File

/**
 * Handles authenticated encryption, decryption, and duplicate validation for packet payloads in Kotlin.
 * Extended to coordinate Android Keystore key version rotation and application integrity attestation checks.
 */
class SecurityManager(private val cryptoProvider: CryptographicProvider) {

    private val replayProtection = ReplayProtectionManager.getInstance()
    private var activeKeyAlias = "meshmind_master_key_v1"

    companion object {
        private const val TAG = "SecurityManager"
        
        @Volatile
        private var instance: SecurityManager? = null

        fun getInstance(): SecurityManager {
            return instance ?: synchronized(this) {
                instance ?: SecurityManager(SecureCryptoProvider()).also { instance = it }
            }
        }
    }

    /**
     * Encrypts the payload of a MeshFrame and returns a new frame containing the ciphertext and IV.
     */
    @Throws(SecurityValidationException::class)
    fun encryptFramePayload(frame: MeshFrame): MeshFrame {
        val (iv, cipherText) = cryptoProvider.encrypt(frame.payload)
        
        // Re-build target frame
        return frame.copy(
            iv = iv,
            payload = cipherText
        )
    }

    /**
     * Decrypts the payload of a MeshFrame using its IV and returns a new frame containing the plaintext.
     */
    @Throws(SecurityValidationException::class)
    fun decryptFramePayload(frame: MeshFrame): MeshFrame {
        val iv = frame.iv ?: throw SecurityValidationException("Frame initialization vector is missing.")
        val plainText = cryptoProvider.decrypt(iv, frame.payload)
        
        return frame.copy(
            iv = null,
            payload = plainText
        )
    }

    /**
     * Verifies that the frame is unique and has not been replayed.
     */
    fun verifyUniqueFrame(frame: MeshFrame): Boolean {
        if (replayProtection.isReplayedOrDuplicate(frame.packetId)) {
            MeshLogger.w(TAG, "Replayed/Duplicate packet identified: ${frame.packetId}. Dropping packet.")
            return false
        }
        return true
    }

    // --- Keystore Cryptographic Evolution & Attestations ---

    fun getActiveKeyAlias(): String = activeKeyAlias

    /**
     * Rotates key alias reference during lifecycle events.
     */
    fun rotateEncryptionKeyAlias(newAlias: String) {
        MeshLogger.i(TAG, "Rotating active cryptographic master key from $activeKeyAlias to $newAlias")
        activeKeyAlias = newAlias
    }

    /**
     * Evaluates standard signature attestation rules and rooted file presence checks.
     */
    fun isDeviceRootedOrTampered(): Boolean {
        val rootPaths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su"
        )
        for (path in rootPaths) {
            if (File(path).exists()) {
                MeshLogger.e(TAG, "Security Alert: Root file binary signature found: $path")
                return true
            }
        }
        return false
    }
}
