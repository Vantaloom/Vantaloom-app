package online.timefiles.vantaloom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Modifier
import java.nio.file.Files

class LocalRuntimeStateTest {
    @Test
    fun reloadCanRestoreOnlyALiveRuntimeCapability() {
        val token = "c".repeat(64)
        val capability = "e".repeat(64)
        setState(FakeProcess(alive = true), 9137, token, capability)
        try {
            val endpoint = LocalRuntime.currentEndpoint()
            assertEquals(9137, endpoint?.port)
            assertEquals(token, endpoint?.bearerToken)
            assertEquals(capability, endpoint?.capabilityToken)

            setState(FakeProcess(alive = false), 9137, token, capability)
            assertNull(LocalRuntime.currentEndpoint())
            assertNull(LocalRuntime.currentEndpoint())
        } finally {
            setState(null, 0, null, null)
        }
    }

    @Test
    fun explicitStopDestroysTheLiveChildAndClearsCapability() {
        val process = FakeProcess(alive = true)
        setState(process, 9137, "d".repeat(64), "f".repeat(64))
        try {
            LocalRuntime.stop()
            assertFalse(process.isAlive)
            assertNull(LocalRuntime.currentEndpoint())
        } finally {
            setState(null, 0, null, null)
        }
    }

    @Test
    fun childEnvironmentDropsInheritedPrivateCredentials() {
        val environment = linkedMapOf(
            "PATH" to "/system/bin",
            "vantaloom_loopback_bearer_token" to "legacy-bearer",
            "VANTALOOM_LOOPBACK_CAPABILITY_TOKEN" to "legacy-capability",
            "VANTALOOM_LOOPBACK_CREDENTIALS_FILE" to "/cache/old.json",
            "VANTALOOM_HUB_TOKEN" to "hub-token",
            "HUB_JWT_SECRET" to "hub-secret",
        )
        LocalRuntime.replacePrivateRuntimeEnvironment(
            environment,
            mapOf(
                "HOME" to "/data/user/0/app/files",
                LoopbackAuth.credentialsFileEnvironmentVariable to "/cache/new.json",
            ),
        )

        assertEquals("/system/bin", environment["PATH"])
        assertEquals("/data/user/0/app/files", environment["HOME"])
        assertEquals(
            "/cache/new.json",
            environment[LoopbackAuth.credentialsFileEnvironmentVariable],
        )
        assertFalse(environment.keys.any { it.equals(LoopbackAuth.bearerEnvironmentVariable, true) })
        assertFalse(environment.keys.any { it.equals(LoopbackAuth.capabilityEnvironmentVariable, true) })
        assertFalse(environment.containsKey("VANTALOOM_HUB_TOKEN"))
        assertFalse(environment.containsKey("HUB_JWT_SECRET"))
    }

    @Test
    fun staleCredentialFilesArePurgedWithoutTouchingOtherCacheFiles() {
        val root = Files.createTempDirectory("vantaloom-loopback-test-").toFile()
        try {
            val stale = File(root, ".vantaloom-loopback-stale.json").apply {
                writeText("secret")
            }
            val unrelated = File(root, "keep.json").apply { writeText("keep") }

            LocalRuntime.purgeStaleLoopbackCredentials(root)

            assertFalse(stale.exists())
            assertTrue(unrelated.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun setState(
        process: Process?,
        port: Int,
        bearerToken: String?,
        capabilityToken: String?,
    ) {
        setField("process", process)
        setField("port", port)
        setField("bearerToken", bearerToken)
        setField("capabilityToken", capabilityToken)
        setField("stopRequested", false)
    }

    private fun setField(name: String, value: Any?) {
        val field = LocalRuntime::class.java.getDeclaredField(name).apply { isAccessible = true }
        field.set(if (Modifier.isStatic(field.modifiers)) null else LocalRuntime, value)
    }

    private class FakeProcess(private var alive: Boolean) : Process() {
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun waitFor(): Int {
            alive = false
            return 0
        }

        override fun exitValue(): Int {
            if (alive) throw IllegalThreadStateException("still alive")
            return 0
        }

        override fun destroy() {
            alive = false
        }

        override fun isAlive(): Boolean = alive
    }
}
