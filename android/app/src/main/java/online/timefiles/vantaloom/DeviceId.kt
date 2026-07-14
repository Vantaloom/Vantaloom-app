package online.timefiles.vantaloom

import android.content.Context
import android.provider.Settings
import java.io.File
import java.util.UUID

/**
 * Stable per-device identifier, mirroring the retired Rust shell's `device_id`.
 *
 * Resolution order:
 *   1. a VALID value cached in <filesDir>/device-id (stable within an install);
 *   2. the hardware SSAID (Settings.Secure.ANDROID_ID), which survives
 *      reinstall as long as the signing key is unchanged — then cached;
 *   3. a persisted random UUID fallback.
 *
 * Validation is load-bearing: some OEM/privacy builds report the SSAID as
 * ALL-ZEROS ("0000000000000000" — seen in production 2026-07-14: a real phone
 * registered on the Hub as `android-0000000000000000`), and old/rooted devices
 * share the notorious 9774d56d682e549c. Either value is NOT device identity —
 * two such phones on one account would collide onto a single Hub machine row.
 * Both are rejected when reading the SSAID AND when reading the cache (devices
 * that already cached a bogus id heal themselves on next launch; the Hub-side
 * duplicate row is cleaned by the web layer's stale-registration sweep).
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
            if (isValidDeviceId(it)) {
                cached = it
                return it
            }
            // Cached value is bogus (pre-0.14.7 builds cached all-zero SSAIDs):
            // fall through and regenerate — never serve a non-identity forever.
        }

        val ssaid = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()?.trim()

        val id = if (isUsableSsaid(ssaid)) {
            "android-$ssaid"
        } else {
            "dev-${UUID.randomUUID()}"
        }

        runCatching { file.writeText(id) }
        cached = id
        return id
    }

    /** A cached id is valid unless it was derived from a bogus SSAID. */
    internal fun isValidDeviceId(id: String): Boolean {
        if (id.isEmpty()) return false
        if (!id.startsWith("android-")) return true // dev-<uuid> fallbacks
        return isUsableSsaid(id.removePrefix("android-"))
    }

    /** Non-empty, not all zeros, not the shared legacy value. */
    internal fun isUsableSsaid(ssaid: String?): Boolean {
        if (ssaid.isNullOrEmpty()) return false
        if (ssaid == KNOWN_BAD_SSAID) return false
        return ssaid.any { it != '0' }
    }
}
