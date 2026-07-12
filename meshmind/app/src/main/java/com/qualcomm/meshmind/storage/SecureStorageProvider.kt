package com.qualcomm.meshmind.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.qualcomm.meshmind.logging.MeshLogger
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * Provides access to hardware-backed, encrypted SharedPreferences on the device.
 */
class SecureStorageProvider(context: Context) {

    private val sharedPreferences: SharedPreferences

    companion object {
        private const val TAG = "SecureStorageProvider"
        private const val PREF_FILE_NAME = "meshmind_secure_prefs"
    }

    init {
        var prefs: SharedPreferences? = null
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            prefs = EncryptedSharedPreferences.create(
                context,
                PREF_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            MeshLogger.i(TAG, "EncryptedSharedPreferences initialized successfully.")
        } catch (e: Exception) {
            when (e) {
                is GeneralSecurityException, is IOException -> {
                    MeshLogger.e(TAG, "Failed to initialize EncryptedSharedPreferences. Falling back to normal mode.", e)
                    // Fallback to standard private preference file in case Keystore fails (device limits)
                    prefs = context.getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE)
                }
                else -> throw e
            }
        }
        sharedPreferences = prefs!!
    }

    fun putString(key: String, value: String?) {
        sharedPreferences.edit().putString(key, value).apply()
    }

    fun getString(key: String, defaultValue: String?): String? {
        return sharedPreferences.getString(key, defaultValue)
    }

    fun putInt(key: String, value: Int) {
        sharedPreferences.edit().putInt(key, value).apply()
    }

    fun getInt(key: String, defaultValue: Int): Int {
        return sharedPreferences.getInt(key, defaultValue)
    }

    fun putBoolean(key: String, value: Boolean) {
        sharedPreferences.edit().putBoolean(key, value).apply()
    }

    fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return sharedPreferences.getBoolean(key, defaultValue)
    }

    fun remove(key: String) {
        sharedPreferences.edit().remove(key).apply()
    }

    fun clear() {
        sharedPreferences.edit().clear().apply()
    }
}
