package online.timefiles.vantaloom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that keeps the app process (and thus the in-process Go
 * overlay node) alive while the app is backgrounded. It carries no logic of its
 * own — the user-started overlay and local runtime live in [Loom] and
 * [LocalRuntime]. Android 14 classifies this as specialUse rather than dataSync:
 * it is an interactive development/device-connection session, not a bounded data
 * transfer. The notification always offers an explicit stop action.
 *
 * The retired Tauri shell lacked this (a known TODO), so the tunnel died on
 * backgrounding; this restores background survival without any VpnService.
 */
class LoomForegroundService : Service() {

    private val stopInProgress = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                requestStop(startId)
                return START_NOT_STICKY
            }
            ACTION_START -> Unit
            else -> {
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
        }

        ensureChannel(this)
        val notification = buildNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Never leave runtime work alive without its user-visible foreground
        // notification. A later explicit bridge call can start a fresh session.
        LocalRuntime.requestStop()
        runCatching { Loom.stop() }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "vantaloom_loom"
        private const val NOTIF_ID = 1001
        private const val STOP_REQUEST_CODE = 1002
        private const val CONTENT_REQUEST_CODE = 1003
        private const val ACTION_START = "online.timefiles.vantaloom.action.START_BACKGROUND_SESSION"
        private const val ACTION_STOP = "online.timefiles.vantaloom.action.STOP_BACKGROUND_SESSION"

        fun start(context: Context) {
            val intent = Intent(context, LoomForegroundService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopIfIdle(context: Context) {
            if (!Loom.isStarted() && !LocalRuntime.isRunning()) {
                context.stopService(Intent(context, LoomForegroundService::class.java))
            }
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
            val contentIntent = PendingIntent.getActivity(
                context,
                CONTENT_REQUEST_CODE,
                Intent(context, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val stopIntent = PendingIntent.getService(
                context,
                STOP_REQUEST_CODE,
                Intent(context, LoomForegroundService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.loom_notification_title))
                .setContentText(context.getString(R.string.loom_notification_text))
                .setSmallIcon(android.R.drawable.stat_notify_sync) // framework icon; avoids bundling one
                .setContentIntent(contentIntent)
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    context.getString(R.string.loom_notification_stop),
                    stopIntent,
                )
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        }
    }

    private fun stopAllWork() {
        LocalRuntime.requestStop()
        runCatching { Loom.stop() }
        LocalRuntime.stop()
    }

    private fun requestStop(startId: Int) {
        if (!stopInProgress.compareAndSet(false, true)) return
        Thread {
            stopAllWork()
            mainHandler.post {
                if (stopSelfResult(startId)) {
                    removeForegroundNotification()
                }
                stopInProgress.set(false)
            }
        }.apply {
            isDaemon = true
            name = "vantaloom-background-stop"
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun removeForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }
}
