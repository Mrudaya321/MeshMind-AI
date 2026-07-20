package com.qualcomm.meshmind.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.qualcomm.meshmind.logging.MeshLogger

/**
 * Restart the foreground mesh services automatically when the device boots.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
            MeshLogger.i(TAG, "Device booted. Starting MeshRuntimeService...")
            val serviceIntent = Intent(context, MeshRuntimeService::class.java)
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                MeshLogger.e(TAG, "Failed to start service on boot", e)
            }
        }
    }
}
