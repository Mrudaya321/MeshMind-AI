package com.qualcomm.meshmind.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks physical device radio connection states reactively.
 */
class ConnectivityState private constructor() {

    private val _isBluetoothEnabled = MutableStateFlow(false)
    val isBluetoothEnabled: StateFlow<Boolean> = _isBluetoothEnabled.asStateFlow()

    private val _isWifiDirectEnabled = MutableStateFlow(false)
    val isWifiDirectEnabled: StateFlow<Boolean> = _isWifiDirectEnabled.asStateFlow()

    private val _isNetworkInterfaceActive = MutableStateFlow(false)
    val isNetworkInterfaceActive: StateFlow<Boolean> = _isNetworkInterfaceActive.asStateFlow()

    companion object {
        @Volatile
        private var instance: ConnectivityState? = null

        fun getInstance(): ConnectivityState {
            return instance ?: synchronized(this) {
                instance ?: ConnectivityState().also { instance = it }
            }
        }
    }

    fun setBluetoothEnabled(enabled: Boolean) {
        _isBluetoothEnabled.value = enabled
    }

    fun setWifiDirectEnabled(enabled: Boolean) {
        _isWifiDirectEnabled.value = enabled
    }

    fun setNetworkInterfaceActive(active: Boolean) {
        _isNetworkInterfaceActive.value = active
    }
}
