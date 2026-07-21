package online.timefiles.vantaloom

import android.content.Context

/**
 * Durable background-persistence state that the foreground service and the boot
 * receiver must consult WITHOUT the WebView or runtime being up. LocalRuntime's
 * live state is in-memory only; this SharedPreferences store records the user's
 * intent ("the runtime should be running") plus the keep-alive knobs so a sticky
 * restart or a reboot can decide whether to bring the runtime back.
 */
internal object RuntimePrefs {
    private const val FILE = "vantaloom_runtime"
    private const val KEY_SHOULD_RUN = "should_run"
    private const val KEY_KEEP_ALIVE = "keep_alive"
    private const val KEY_BOOT_AUTOSTART = "boot_autostart"
    private const val KEY_WAKE_LOCK = "wake_lock"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** True while the user has an intentionally-running local runtime (set on
     *  startLocalRuntime, cleared on an explicit stop). Consulted by sticky
     *  restart + boot autostart to decide whether to relaunch. */
    fun shouldRun(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOULD_RUN, false)

    fun setShouldRun(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_SHOULD_RUN, value).apply()

    /** Keep-alive (default ON — the "断不了" ask): START_STICKY + survive the app
     *  being swiped from Recents. */
    fun keepAlive(context: Context): Boolean = prefs(context).getBoolean(KEY_KEEP_ALIVE, true)

    fun setKeepAlive(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_KEEP_ALIVE, value).apply()

    /** Wake lock (default ON): hold a partial CPU wake lock + Wi‑Fi lock while the
     *  runtime runs so long background commands / dev servers keep working under Doze. */
    fun wakeLock(context: Context): Boolean = prefs(context).getBoolean(KEY_WAKE_LOCK, true)

    fun setWakeLock(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_WAKE_LOCK, value).apply()

    /** Boot autostart (default OFF — aggressive; opt-in): relaunch the runtime on
     *  device boot / after an in-place app update when it was running. */
    fun bootAutostart(context: Context): Boolean = prefs(context).getBoolean(KEY_BOOT_AUTOSTART, false)

    fun setBootAutostart(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_BOOT_AUTOSTART, value).apply()
}
