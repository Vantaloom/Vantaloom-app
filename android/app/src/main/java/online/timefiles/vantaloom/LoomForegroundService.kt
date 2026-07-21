package online.timefiles.vantaloom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
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
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_STOP -> {
                requestStop(startId)
                return START_NOT_STICKY
            }
            // ACTION_START = an explicit bridge start; ACTION_RELAUNCH = boot /
            // app-update autostart; null = a sticky restart (the OS re-delivered
            // onStartCommand with no intent after reclaiming the process).
            ACTION_START, ACTION_RELAUNCH, null -> Unit
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
        acquireWakeLock()

        // Boot / sticky restart: bring the runtime back up if the user intended it
        // to be running. A normal ACTION_START does NOT relaunch here — the bridge
        // that sent it also calls LocalRuntime.ensureStarted (idempotent).
        if ((action == ACTION_RELAUNCH || action == null) &&
            RuntimePrefs.shouldRun(this) && !LocalRuntime.isRunning()
        ) {
            Thread {
                runCatching { LocalRuntime.ensureStarted(applicationContext) }
            }.apply { isDaemon = true; name = "vantaloom-runtime-relaunch" }.start()
        }

        // Keep-alive (default on): ask the OS to restart us after a low-memory kill.
        return if (RuntimePrefs.keepAlive(this)) START_STICKY else START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Termux-style: swiping the app from Recents must NOT kill a runtime the
        // user asked to keep running. Only stop when keep-alive is off or nothing
        // was intentionally running.
        if (!(RuntimePrefs.keepAlive(this) && RuntimePrefs.shouldRun(this))) {
            stopAllWork()
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        releaseWakeLock()
        instance = null
        // With keep-alive the OS restarts the service (START_STICKY) and the
        // null-intent path relaunches the child — so on a surprise destroy while
        // the user still wants it running, DON'T tear the runtime down (that would
        // be the very interruption keep-alive exists to prevent). Only clean up on
        // a genuine stop, where shouldRun has already been cleared.
        if (!RuntimePrefs.shouldRun(this)) {
            LocalRuntime.requestStop()
            runCatching { Loom.stop() }
        }
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        if (!RuntimePrefs.wakeLock(this)) return
        if (wakeLock?.isHeld != true) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vantaloom:runtime").apply {
                setReferenceCounted(false)
                runCatching { acquire() }
            }
        }
        if (wifiLock?.isHeld != true) {
            // WIFI_MODE_FULL_HIGH_PERF is deprecated but is the broadest-compat mode
            // that keeps Wi‑Fi awake for a LAN-reachable dev server under Doze.
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "vantaloom:runtime")?.apply {
                setReferenceCounted(false)
                runCatching { acquire() }
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wifiLock = null
    }

    /** Reconcile the wake lock with the current preference (live toggle). */
    fun reconcileWakeLock() {
        if (RuntimePrefs.wakeLock(this)) acquireWakeLock() else releaseWakeLock()
    }

    companion object {
        private const val CHANNEL_ID = "vantaloom_loom"
        private const val NOTIF_ID = 1001
        private const val STOP_REQUEST_CODE = 1002
        private const val CONTENT_REQUEST_CODE = 1003
        private const val ACTION_START = "online.timefiles.vantaloom.action.START_BACKGROUND_SESSION"
        private const val ACTION_STOP = "online.timefiles.vantaloom.action.STOP_BACKGROUND_SESSION"
        private const val ACTION_RELAUNCH = "online.timefiles.vantaloom.action.RELAUNCH_BACKGROUND_SESSION"

        // Live instance so a settings toggle can reconcile the wake lock without
        // restarting the service. Nulled in onDestroy; a Service outlives no
        // Activity leak here since it is cleared on teardown.
        @Volatile
        private var instance: LoomForegroundService? = null

        fun start(context: Context) {
            val intent = Intent(context, LoomForegroundService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Boot / app-update autostart: start the service and relaunch the runtime. */
        fun startForRelaunch(context: Context) {
            val intent = Intent(context, LoomForegroundService::class.java).setAction(ACTION_RELAUNCH)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Apply a changed wake-lock preference to the running service, if any. */
        fun applyWakeLockPreference(context: Context) {
            instance?.reconcileWakeLock()
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
        // A genuine stop clears the user's "keep running" intent so sticky restart
        // and boot autostart do not resurrect the runtime.
        RuntimePrefs.setShouldRun(this, false)
        releaseWakeLock()
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
