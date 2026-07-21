package online.timefiles.vantaloom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Relaunches the background runtime after a reboot (or an in-place app update),
 * but ONLY when the user opted into boot autostart AND the runtime was
 * intentionally running (RuntimePrefs.shouldRun). targetSdk 28 exempts the app
 * from the API 31 "no foreground-service start from the background" restriction,
 * so starting the foreground service straight from BOOT_COMPLETED is permitted —
 * the same low-targetSdk exemption the proot sandbox relies on.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }
        if (!RuntimePrefs.bootAutostart(context) || !RuntimePrefs.shouldRun(context)) return
        runCatching { LoomForegroundService.startForRelaunch(context) }
            .onFailure { Log.w("VantaloomBoot", "runtime relaunch failed", it) }
    }
}
