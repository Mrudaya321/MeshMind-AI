package com.qualcomm.meshmind.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.qualcomm.meshmind.R
import com.qualcomm.meshmind.logging.MeshLogger

/**
 * Foreground Service responsible for maintaining active mesh routing and sockets.
 */
class MeshMindService : Service() {

    companion object {
        private const val TAG = "MeshMindService"
        private const val NOTIFICATION_ID = 9001
        private const val CHANNEL_ID = "meshmind_service_channel"
    }

    override fun onCreate() {
        super.onCreate()
        MeshLogger.i(TAG, "MeshMindService created.")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MeshLogger.i(TAG, "MeshMindService started command.")
        // Keep service alive
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        MeshLogger.i(TAG, "MeshMindService destroyed.")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "MeshMind Platform Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MeshMind Active Node")
            .setContentText("Participating in decentralized edge mesh communication...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
    }
}
