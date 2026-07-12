package online.timefiles.vantaloom

import android.content.Context
import android.provider.Settings
import java.io.File
import java.util.UUID

/**
 * Stable per-device identifier, mirroring the retired Rust shell's `device_id`.
 *
 * Resolution order:
 *   1. a value cached in <filesDir>/device-id (stable within an install);
 *   2. the hardware SSAID (Settings.Secure.ANDROID_ID), which survives
 *      reinstall as long as the signing key is unchanged — then cached;
 *   3. a persisted random UUID fallback.
 *
 * NOTE: this is the device's HARDWARE id (`android-<ssaid>`), used by the
 * frontend as the Hub registration hardwareId. It is NOT the overlay machineID —
 * that is the Hub-assigned machine.id returned from registration, which the
 * frontend passes to __loomBridge.startNode.
 */
object DeviceId {
    // A notorious non-unique SSAID some old/rooted devices report.
    private const val KNOWN_BAD_SSAID = "9774d56d682e549c"

    @Volatile
    private var cached: String? = null

    @Synchronized
    fun get(context: Context): String {
        cached?.let { return it }

        val file = File(context.filesDir, "device-id")
        runCatching { file.readText().trim() }.getOrNull()?.let {
            if (it.isNotEmpty()) {
                cached = it
                return it
            }
        }

        val ssaid = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()?.trim()

        val id = if (!ssaid.isNullOrEmpty() && ssaid != KNOWN_BAD_SSAID) {
            "android-$ssaid"
        } else {
            "dev-${UUID.randomUUID()}"
        }

        runCatching { file.writeText(id) }
        cached = id
        return id
    }
}
