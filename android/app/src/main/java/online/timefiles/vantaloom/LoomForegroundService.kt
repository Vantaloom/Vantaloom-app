package online.timefiles.vantaloom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground service that keeps the app process (and thus the in-process Go
 * overlay node) alive while the app is backgrounded. It carries no logic of its
 * own — the node lives in [Loom]; this service exists only to hold a
 * dataSync-typed foreground notification so Android does not reap the process.
 *
 * The retired Tauri shell lacked this (a known TODO), so the tunnel died on
 * backgrounding; this restores background survival without any VpnService.
 */
class LoomForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        val notification = buildNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        // START_STICKY: if the OS kills us under pressure, restart so the node's
        // process is brought back (the frontend re-runs the idempotent StartNode).
        return START_STICKY
    }

    companion object {
        private const val CHANNEL_ID = "vantaloom_loom"
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, LoomForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LoomForegroundService::class.java))
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.loom_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
            mgr.createNotificationChannel(channel)
        }

        private fun buildNotification(context: Context): Notification {
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.loom_notification_title))
                .setContentText(context.getString(R.string.loom_notification_text))
                .setSmallIcon(android.R.drawable.stat_notify_sync) // framework icon; avoids bundling one
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        }
    }
}
