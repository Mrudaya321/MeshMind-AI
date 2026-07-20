package com.qualcomm.meshmind.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.qualcomm.meshmind.exceptions.MeshMindException.SecurityValidationException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface CryptographicProvider {
    fun encrypt(plainText: ByteArray): Pair<ByteArray, ByteArray>
    fun decrypt(iv: ByteArray, cipherText: ByteArray): ByteArray
}

/**
 * Keystore-backed AES-256-GCM encryption provider in Kotlin.
 */
class SecureCryptoProvider : CryptographicProvider {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "meshmind_master_key"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128
    }

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
    }

    init {
        ensureKeyExists()
    }

    private fun ensureKeyExists() {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )
            val keySpec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            
            keyGenerator.init(keySpec)
            keyGenerator.generateKey()
        }
    }

    private fun getSecretKey(): SecretKey {
        val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        return entry?.secretKey ?: throw SecurityValidationException("Failed to retrieve secret key from Android Keystore")
    }

    override fun encrypt(plainText: ByteArray): Pair<ByteArray, ByteArray> {
        return try {
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            val iv = ByteArray(GCM_IV_LENGTH_BYTES).apply {
                SecureRandom().nextBytes(this)
            }
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            
            cipher.init(Cipher.ENCRYPT_MODE, getSecretKey(), spec)
            val cipherText = cipher.doFinal(plainText)
            
            Pair(iv, cipherText)
        } catch (e: Exception) {
            throw SecurityValidationException("AES-GCM encryption operation failed", e)
        }
    }

    override fun decrypt(iv: ByteArray, cipherText: ByteArray): ByteArray {
        return try {
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)
            cipher.doFinal(cipherText)
        } catch (e: Exception) {
            throw SecurityValidationException("AES-GCM decryption operation failed", e)
        }
    }
}
