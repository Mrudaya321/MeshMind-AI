package com.qualcomm.meshmind.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.qualcomm.meshmind.R
import com.qualcomm.meshmind.core.runtime.SubsystemManager
import com.qualcomm.meshmind.logging.MeshLogger
import com.qualcomm.meshmind.state.NeighborStateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Android Foreground Service orchestrating background mesh execution and power settings.
 */
class MeshRuntimeService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var connectivityReceiver: BroadcastReceiver? = null

    companion object {
        private const val TAG = "MeshRuntimeService"
        private const val NOTIFICATION_ID = 9015
        private const val CHANNEL_ID = "mesh_runtime_service_channel"
    }

    override fun onCreate() {
        super.onCreate()
        MeshLogger.i(TAG, "MeshRuntimeService foreground service initialization.")
        
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification("Initial connection..."))

        // 1. Acquire Wake Lock to guarantee operational stability under device sleep
        acquireWakeLock()

        // 2. Register local Connectivity Monitor
        registerConnectivityMonitor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MeshLogger.i(TAG, "MeshRuntimeService start command received.")
        
        // Asynchronously poll stats and update foreground notification message
        serviceScope.launch {
            val count = NeighborStateRepository.getInstance().getAllNeighbors().size
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.notify(NOTIFICATION_ID, buildForegroundNotification("Active Nodes: $count neighbors"))
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        unregisterConnectivityMonitor()
        MeshLogger.i(TAG, "MeshRuntimeService destroyed.")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MeshMind::RuntimeWakeLock")?.apply {
                acquire(300000) // Acquire with 5-minute timeout window
            }
            MeshLogger.i(TAG, "Acquired partial CPU wake lock.")
        } catch (e: Exception) {
            MeshLogger.e(TAG, "Failed acquiring wake lock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (ignored: Exception) {}
        wakeLock = null
    }

    private fun registerConnectivityMonitor() {
        connectivityReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                MeshLogger.i(TAG, "System connectivity status change callback intercepted.")
            }
        }
        val filter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
        registerReceiver(connectivityReceiver, filter)
    }

    private fun unregisterConnectivityMonitor() {
        connectivityReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (ignored: Exception) {}
        }
        connectivityReceiver = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "MeshMind Background Execution Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun buildForegroundNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MeshMind Autonomous Mesh")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
    }
}
