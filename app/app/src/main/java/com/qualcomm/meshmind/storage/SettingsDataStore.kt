package com.qualcomm.meshmind.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "meshmind_settings")

/**
 * Persists application settings and configurations reactively using Jetpack DataStore in Kotlin.
 */
class SettingsDataStore(private val context: Context) {

    companion object {
        val KEY_NODE_ID = stringPreferencesKey("node_id")
        val KEY_DEV_MODE = booleanPreferencesKey("developer_mode")
        val KEY_EMERGENCY_MODE = booleanPreferencesKey("emergency_mode")
        val KEY_TELEMETRY_INTERVAL = longPreferencesKey("telemetry_interval_ms")
        val KEY_INFERENCE_INTERVAL = longPreferencesKey("inference_interval_ms")
        
        @Volatile
        private var instance: SettingsDataStore? = null

        fun getInstance(context: Context): SettingsDataStore {
            return instance ?: synchronized(this) {
                instance ?: SettingsDataStore(context.applicationContext).also { instance = it }
            }
        }
    }

    val nodeIdFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_NODE_ID]
    }

    val developerModeFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_DEV_MODE] ?: false
    }

    val emergencyModeFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_EMERGENCY_MODE] ?: false
    }

    val telemetryIntervalFlow: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[KEY_TELEMETRY_INTERVAL] ?: 5000L
    }

    val inferenceIntervalFlow: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[KEY_INFERENCE_INTERVAL] ?: 10000L
    }

    suspend fun saveNodeId(nodeId: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_NODE_ID] = nodeId
        }
    }

    suspend fun setDeveloperMode(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DEV_MODE] = enabled
        }
    }

    suspend fun setEmergencyMode(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_EMERGENCY_MODE] = enabled
        }
    }

    suspend fun setTelemetryInterval(intervalMs: Long) {
        context.dataStore.edit { prefs ->
            prefs[KEY_TELEMETRY_INTERVAL] = intervalMs
        }
    }

    suspend fun setInferenceInterval(intervalMs: Long) {
        context.dataStore.edit { prefs ->
            prefs[KEY_INFERENCE_INTERVAL] = intervalMs
        }
    }
}
