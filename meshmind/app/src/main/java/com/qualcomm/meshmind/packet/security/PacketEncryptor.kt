package com.qualcomm.meshmind.packet.security

import com.qualcomm.meshmind.exceptions.MeshMindException.SecurityValidationException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Executes AES-256-GCM hardware-friendly packet payload encryption in Kotlin.
 */
object PacketEncryptor {

    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val GCM_IV_LENGTH_BYTES = 12

    /**
     * Encrypts a message payload using AES-256-GCM.
     */
    @Throws(SecurityValidationException::class)
    fun encrypt(plainText: ByteArray, aesKeyBytes: ByteArray): ByteArray {
        try {
            val iv = ByteArray(GCM_IV_LENGTH_BYTES).apply {
                SecureRandom().nextBytes(this)
            }

            val cipher = Cipher.getInstance(ALGORITHM)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            val keySpec = SecretKeySpec(aesKeyBytes, "AES")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, spec)

            val cipherText = cipher.doFinal(plainText)
            
            // Package as IV + CipherText
            val encryptedFrame = ByteArray(iv.size + cipherText.size)
            System.arraycopy(iv, 0, encryptedFrame, 0, iv.size)
            System.arraycopy(cipherText, 0, encryptedFrame, iv.size, cipherText.size)
            
            return encryptedFrame
        } catch (e: Exception) {
            throw SecurityValidationException("AES-GCM encryption step failed", e)
        }
    }

    /**
     * Decrypts an AES-256-GCM encrypted payload.
     */
    @Throws(SecurityValidationException::class)
    fun decrypt(ivAndCipherText: ByteArray?, aesKeyBytes: ByteArray): ByteArray {
        try {
            if (ivAndCipherText == null || ivAndCipherText.size < GCM_IV_LENGTH_BYTES) {
                throw IllegalArgumentException("Payload size is too short to extract the IV.")
            }

            val iv = ByteArray(GCM_IV_LENGTH_BYTES)
            System.arraycopy(ivAndCipherText, 0, iv, 0, iv.size)

            val cipherTextSize = ivAndCipherText.size - GCM_IV_LENGTH_BYTES
            val cipherText = ByteArray(cipherTextSize)
            System.arraycopy(ivAndCipherText, iv.size, cipherText, 0, cipherTextSize)

            val cipher = Cipher.getInstance(ALGORITHM)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            val keySpec = SecretKeySpec(aesKeyBytes, "AES")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, spec)

            return cipher.doFinal(cipherText)
        } catch (e: Exception) {
            throw SecurityValidationException("AES-GCM decryption verification failed", e)
        }
    }
}
