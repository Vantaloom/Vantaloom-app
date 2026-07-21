package online.timefiles.vantaloom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for the APK self-updater: the release tag → build-number
 * parse and the version comparison. These never touch Android APIs, so they run
 * as a plain JVM unit test (mirrors ShellSecurityTest). The network/download/
 * install paths are exercised on-device.
 */
class AppUpdateTest {
    @Test
    fun parsesRunNumberFromApkBuildTag() {
        assertEquals(186L, AppUpdate.parseBuildFromTag("apk-build186-73c7f35"))
        assertEquals(9L, AppUpdate.parseBuildFromTag("apk-build9-abc1234"))
        assertEquals(1234L, AppUpdate.parseBuildFromTag("apk-build1234-deadbee"))
    }

    @Test
    fun rejectsNonApkTags() {
        assertEquals(0L, AppUpdate.parseBuildFromTag("node-build42-abc1234"))
        assertEquals(0L, AppUpdate.parseBuildFromTag("desktop-build7-abc1234"))
        assertEquals(0L, AppUpdate.parseBuildFromTag("v0.15.10"))
        assertEquals(0L, AppUpdate.parseBuildFromTag("garbage"))
        assertEquals(0L, AppUpdate.parseBuildFromTag(""))
        assertEquals(0L, AppUpdate.parseBuildFromTag(null))
    }

    @Test
    fun updateAvailableOnlyForStrictlyNewerBuild() {
        assertTrue(AppUpdate.isUpdateAvailable(current = 185, latest = 186))
        // A fresh dev build (versionCode fallback 1) treats any real release as newer.
        assertTrue(AppUpdate.isUpdateAvailable(current = 1, latest = 186))
        assertFalse(AppUpdate.isUpdateAvailable(current = 186, latest = 186))
        assertFalse(AppUpdate.isUpdateAvailable(current = 186, latest = 185))
        // A failed/unknown parse (latest = 0) never claims an update.
        assertFalse(AppUpdate.isUpdateAvailable(current = 0, latest = 0))
        assertFalse(AppUpdate.isUpdateAvailable(current = 186, latest = 0))
    }
}
