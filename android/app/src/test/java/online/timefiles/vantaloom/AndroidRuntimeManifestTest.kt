package online.timefiles.vantaloom

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class AndroidRuntimeManifestTest {
    @Test
    fun manifestListsOnlyVerifiedDataWithRealNativeEntries() {
        withTempDirectory { root ->
            val filesDir = File(root, "files").apply { mkdirs() }
            val nativeDir = File(root, "native").apply { mkdirs() }
            val bundleDir = File(root, "bundle").apply { mkdirs() }
            File(bundleDir, "python").mkdirs()
            File(bundleDir, "node").mkdirs()
            val packageManifest = File(bundleDir, "manifest.json").apply { writeText("{}") }
            val pythonEntry = File(nativeDir, "libvantaloom_python.so").apply {
                writeBytes(byteArrayOf(1, 2, 3, 4, 5))
            }
            val goEntry = File(nativeDir, "libvantaloom_go.so").apply {
                writeBytes(byteArrayOf(9))
            }
            File(nativeDir, "libpython3.14.so").writeBytes(byteArrayOf(6))
            File(nativeDir, "libvantaloom_node.so").writeBytes(byteArrayOf(7, 8))

            val target = AndroidRuntimeManifest.rebuild(
                filesDir,
                nativeDir.absolutePath,
                RuntimeEngineBundle(
                    bundleDir,
                    packageManifest,
                    mapOf("python" to "3.14.6", "node" to "24.18.0", "go" to "0.16.1"),
                    mapOf("python" to 17L, "node" to 23L),
                    mapOf("python" to 101L, "node" to 202L, "go" to 303L),
                ),
                ::moveAtomicallyForTest,
            )

            assertEquals(File(filesDir, "runtime-toolchains/manifest.json").absolutePath, target.absolutePath)
            val manifest = JSONObject(target.readText())
            assertEquals(1, manifest.getInt("schema"))
            assertEquals(nativeDir.absolutePath, manifest.getString("nativeLibraryDir"))
            val engines = manifest.getJSONArray("engines")
            assertEquals(3, engines.length())
            val python = engineById(engines, "python")
            assertEquals("python", python.getString("id"))
            assertEquals("cpython", python.getString("kind"))
            assertEquals("3.14.6", python.getString("version"))
            assertEquals(pythonEntry.absolutePath, python.getString("entryPoint"))
            assertEquals("python", python.getJSONArray("entryArgs").getString(0))
            assertEquals("--version", python.getJSONArray("versionArgs").getString(0))
            assertEquals(101L, python.getLong("bundleBytes"))
            assertEquals("PSF-2.0", python.getString("license"))
            assertTrue(python.getString("source").contains("3.14.6"))
            assertTrue(python.getString("packageInstallPolicy").isNotBlank())
            assertTrue(python.getBoolean("supportsHttpServer"))
            assertTrue(python.getBoolean("loopbackEnforced"))
            val environment = python.getJSONObject("environment")
            assertEquals(
                File(filesDir, "runtime-toolchains/state").absolutePath,
                environment.getString(AndroidRuntimeManifest.runtimeDataDirEnvironment),
            )
            assertEquals(
                bundleDir.absolutePath,
                environment.getString(AndroidRuntimeManifest.runtimeBundleDirEnvironment),
            )
            assertEquals(
                filesDir.absolutePath,
                environment.getString(AndroidRuntimeManifest.appFilesDirEnvironment),
            )
            assertEquals(
                packageManifest.absolutePath,
                environment.getString(AndroidRuntimeManifest.packageManifestEnvironment),
            )
            val go = engineById(engines, "go")
            assertEquals("yaegi", go.getString("kind"))
            assertEquals("0.16.1", go.getString("version"))
            assertEquals(goEntry.absolutePath, go.getString("entryPoint"))
            assertEquals(303L, go.getLong("bundleBytes"))
            assertEquals("Apache-2.0", go.getString("license"))
            assertFalse(go.getBoolean("supportsHttpServer"))
            assertFalse(go.getBoolean("loopbackEnforced"))
            assertFalse(go.has("entryArgs"))
            assertEquals(
                filesDir.absolutePath,
                go.getJSONObject("environment")
                    .getString(AndroidRuntimeManifest.appFilesDirEnvironment),
            )
            assertTrue(go.getString("packageInstallPolicy").contains("os/exec"))
            val node = engineById(engines, "node")
            assertEquals(pythonEntry.absolutePath, node.getString("entryPoint"))
            assertEquals("node", node.getJSONArray("entryArgs").getString(0))
            assertEquals("--version", node.getJSONArray("versionArgs").getString(0))
            assertTrue(node.getString("packageInstallPolicy").contains("生命周期脚本永久禁用"))
        }
    }

    @Test
    fun goRunnerNeedsOnlyItsRealNativeEntry() {
        withTempDirectory { root ->
            val nativeDir = File(root, "native").apply { mkdirs() }
            File(nativeDir, "libvantaloom_go.so").writeBytes(byteArrayOf(1, 2, 3))

            val engines = JSONObject(
                AndroidRuntimeManifest.buildJson(
                    File(root, "files").apply { mkdirs() },
                    nativeDir.absolutePath,
                    bundle = null,
                ),
            ).getJSONArray("engines")

            assertEquals(1, engines.length())
            val go = engines.getJSONObject(0)
            assertEquals("go", go.getString("id"))
            assertEquals("0.16.1", go.getString("version"))
            assertEquals(3L, go.getLong("bundleBytes"))
            assertFalse(go.getBoolean("supportsHttpServer"))
        }
    }

    @Test
    fun missingEntryDependencyOrEngineDataIsNeverAdvertised() {
        withTempDirectory { root ->
            val nativeDir = File(root, "native").apply { mkdirs() }
            File(nativeDir, "libvantaloom_python.so").writeBytes(byteArrayOf(1))
            val bundleDir = File(root, "bundle").apply { mkdirs() }
            val packageManifest = File(bundleDir, "manifest.json").apply { writeText("{}") }
            val bundle = RuntimeEngineBundle(
                bundleDir,
                packageManifest,
                mapOf("python" to "3.14.6", "node" to "24.18.0"),
                mapOf("python" to 0L, "node" to 0L),
            )

            val withoutData = JSONObject(
                AndroidRuntimeManifest.buildJson(File(root, "files"), nativeDir.absolutePath, bundle),
            )
            assertEquals(0, withoutData.getJSONArray("engines").length())

            File(bundleDir, "python").mkdirs()
            val withoutSharedLibrary = JSONObject(
                AndroidRuntimeManifest.buildJson(File(root, "files"), nativeDir.absolutePath, bundle),
            ).getJSONArray("engines")
            assertEquals(0, withoutSharedLibrary.length())

            File(nativeDir, "libpython3.14.so").writeBytes(byteArrayOf(2))
            val pythonOnly = JSONObject(
                AndroidRuntimeManifest.buildJson(File(root, "files"), nativeDir.absolutePath, bundle),
            ).getJSONArray("engines")
            assertEquals(1, pythonOnly.length())
            assertEquals("python", pythonOnly.getJSONObject(0).getString("id"))

            File(bundleDir, "node").mkdirs()
            val withoutNodeRuntime = JSONObject(
                AndroidRuntimeManifest.buildJson(File(root, "files"), nativeDir.absolutePath, bundle),
            ).getJSONArray("engines")
            assertEquals(1, withoutNodeRuntime.length())

            File(nativeDir, "libvantaloom_node.so").writeBytes(byteArrayOf(3))
            val complete = JSONObject(
                AndroidRuntimeManifest.buildJson(File(root, "files"), nativeDir.absolutePath, bundle),
            ).getJSONArray("engines")
            assertEquals(2, complete.length())
        }
    }

    @Test
    fun rebuildAtomicallyReplacesStaleNativeLibraryDir() {
        withTempDirectory { root ->
            val filesDir = File(root, "files").apply { mkdirs() }
            val target = File(filesDir, "runtime-toolchains/manifest.json").apply {
                parentFile?.mkdirs()
                writeText("""{"schema":1,"nativeLibraryDir":"/stale/install","engines":[]}""")
            }
            val nativeDir = File(root, "current-native").apply { mkdirs() }

            AndroidRuntimeManifest.rebuild(
                filesDir,
                nativeDir.absolutePath,
                bundle = null,
                atomicMove = ::moveAtomicallyForTest,
            )

            val manifest = JSONObject(target.readText())
            assertEquals(nativeDir.absolutePath, manifest.getString("nativeLibraryDir"))
            assertEquals(0, manifest.getJSONArray("engines").length())
        }
    }

    @Test
    fun assetManifestRejectsTraversalAndKeepsOnlyAssetData() {
        val valid = JSONObject()
            .put("schemaVersion", 1)
            .put(
                "engines",
                JSONObject().put(
                    "python",
                    JSONObject().put("version", "3.14.6").put("bundleBytes", 42),
                ).put(
                    "go",
                    JSONObject()
                        .put("version", "0.1.0")
                        .put("interpreter", "Yaegi v0.16.1")
                        .put("bundleBytes", 50),
                ),
            )
            .put(
                "files",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("path", "assets/runtime-engines/python/os.py")
                            .put("size", 2)
                            .put("sha256", "a".repeat(64)),
                    )
                    .put(
                        JSONObject()
                            .put("path", "jniLibs/arm64-v8a/libvantaloom_python.so")
                            .put("size", 3)
                            .put("sha256", "b".repeat(64)),
                    ),
            )
            .toString()
            .toByteArray()
        val parsed = RuntimeEngineAssets.parseManifest(valid)
        assertEquals(mapOf("python" to "3.14.6", "go" to "0.16.1"), parsed.engineVersions)
        assertEquals(mapOf("python" to 2L, "go" to 0L), parsed.engineDataBytes)
        assertEquals(mapOf("python" to 42L, "go" to 50L), parsed.engineBundleBytes)
        assertEquals(1, parsed.records.size)
        assertEquals("python/os.py", parsed.records.single().relativePath)

        val traversal = JSONObject(String(valid))
        traversal.getJSONArray("files").getJSONObject(0)
            .put("path", "assets/runtime-engines/python/../../escape")
        val rejected = runCatching {
            RuntimeEngineAssets.parseManifest(traversal.toString().toByteArray())
        }.isFailure
        assertTrue(rejected)
    }

    @Test
    fun completeBundleDetectsSameSizeTamperingBeforeReuse() {
        withTempDirectory { root ->
            val payload = "ok".toByteArray()
            val raw = JSONObject()
                .put("schemaVersion", 1)
                .put("engines", JSONObject().put("python", JSONObject().put("version", "3.14.6")))
                .put(
                    "files",
                    JSONArray().put(
                        JSONObject()
                            .put("path", "assets/runtime-engines/python/os.py")
                            .put("size", payload.size)
                            .put("sha256", sha256ForTest(payload)),
                    ),
                )
                .toString()
                .toByteArray()
            val parsed = RuntimeEngineAssets.parseManifest(raw)
            val bundle = File(root, "bundle").apply { mkdirs() }
            File(bundle, "python").mkdirs()
            val installed = File(bundle, "python/os.py").apply { writeBytes(payload) }
            File(bundle, "manifest.json").writeBytes(raw)
            File(bundle, ".complete").writeText(parsed.hash)

            assertTrue(RuntimeEngineAssets.isComplete(bundle, parsed))
            val injected = File(bundle, "python/sitecustomize.py").apply { writeText("pass") }
            assertFalse(RuntimeEngineAssets.isComplete(bundle, parsed))
            injected.delete()
            assertTrue(RuntimeEngineAssets.isComplete(bundle, parsed))
            installed.writeText("NO")
            assertEquals(payload.size.toLong(), installed.length())
            assertFalse(RuntimeEngineAssets.isComplete(bundle, parsed))
        }
    }

    private fun moveAtomicallyForTest(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun engineById(engines: JSONArray, id: String): JSONObject =
        (0 until engines.length())
            .map { engines.getJSONObject(it) }
            .first { it.getString("id") == id }

    private fun sha256ForTest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun withTempDirectory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("vantaloom-runtime-manifest-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
