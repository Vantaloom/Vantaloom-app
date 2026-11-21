package online.timefiles.vantaloom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The runtime reports the APK's versionName as the host version
 * (`VANTALOOM_VERSION`, LocalRuntime.kt), and plugin installs gate on
 * minHostVersion — so versionName must equal the version of the bundled
 * libvantaloom.so, recorded next to it in jniLibs as core-version.txt by the
 * main repo's build-android-runtime.ps1. This test fails the moment the two
 * sources drift again (a stale literal once froze the reported version at
 * 0.16.63 while the .so was already 0.23.20).
 */
class CoreVersionTest {
    @Test
    fun apkVersionNameFollowsBundledCoreVersion() {
        val expected = File("src/main/jniLibs/arm64-v8a/core-version.txt").readText().trim()
        assertTrue("core-version.txt is empty", expected.isNotEmpty())
        assertEquals(expected, BuildConfig.VERSION_NAME)
    }
}
