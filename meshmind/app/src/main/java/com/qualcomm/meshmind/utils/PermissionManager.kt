package com.qualcomm.meshmind.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Abstracts Android permission checks and Bluetooth scans/Wi-Fi APIs checks in Kotlin.
 */
object PermissionManager {

    /**
     * Checks if all permissions required for local BLE peer discovery are granted.
     */
    fun hasBluetoothPermissions(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkPermission(context, Manifest.permission.BLUETOOTH_SCAN) &&
                    checkPermission(context, Manifest.permission.BLUETOOTH_CONNECT) &&
                    checkPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            checkPermission(context, Manifest.permission.BLUETOOTH) &&
                    checkPermission(context, Manifest.permission.BLUETOOTH_ADMIN) &&
                    checkPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    /**
     * Checks if all permissions required for Wi-Fi Direct connections are granted.
     */
    fun hasWifiDirectPermissions(context: Context): Boolean {
        val basePermissions = checkPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) &&
                checkPermission(context, Manifest.permission.CHANGE_WIFI_STATE)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return basePermissions && checkPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        return basePermissions
    }

    /**
     * Checks if permissions for foreground services and notifications are granted.
     */
    fun hasSystemExecutionPermissions(context: Context): Boolean {
        val baseForeground = checkPermission(context, Manifest.permission.FOREGROUND_SERVICE)
        val notificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }
        return baseForeground && notificationPermission
    }

    private fun checkPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}
