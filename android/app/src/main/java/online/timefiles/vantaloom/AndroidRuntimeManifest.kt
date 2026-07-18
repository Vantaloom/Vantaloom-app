package online.timefiles.vantaloom

import android.content.Context
import android.system.Os
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

internal data class RuntimeEngineBundle(
    val bundleDir: File,
    val packageManifest: File,
    val engineVersions: Map<String, String>,
    val engineDataBytes: Map<String, Long> = emptyMap(),
    val engineBundleBytes: Map<String, Long> = emptyMap(),
)

/** Installs immutable APK runtime data into a hash-addressed, atomically activated bundle. */
internal object RuntimeEngineAssets {
    private const val TAG = "VantaloomRuntimeAssets"
    private const val ASSET_MANIFEST = "runtime-engines/manifest.json"
    private const val ASSET_RECORD_PREFIX = "assets/runtime-engines/"
    private const val MANIFEST_SCHEMA = 1
    private const val MAX_MANIFEST_BYTES = 16 * 1024 * 1024
    private const val MAX_FILE_COUNT = 100_000
    private const val MAX_BUNDLE_BYTES = 1536L * 1024 * 1024
    private const val COPY_BUFFER_BYTES = 64 * 1024

    internal data class AssetRecord(
        val assetPath: String,
        val relativePath: String,
        val size: Long,
        val sha256: String,
    )

    internal data class ParsedAssetManifest(
        val raw: ByteArray,
        val hash: String,
        val records: List<AssetRecord>,
        val engineVersions: Map<String, String>,
        val engineDataBytes: Map<String, Long>,
        val engineBundleBytes: Map<String, Long>,
    )

    fun prepare(context: Context): RuntimeEngineBundle? {
        return try {
            val raw = context.assets.open(ASSET_MANIFEST).use {
                readBounded(it, MAX_MANIFEST_BYTES)
            }
            prepareParsed(context, parseManifest(raw))
        } catch (_: FileNotFoundException) {
            null
        } catch (error: Exception) {
            Log.e(TAG, "runtime engine bundle install failed", error)
            null
        }
    }

    internal fun parseManifest(raw: ByteArray): ParsedAssetManifest {
        val root = JSONObject(String(raw, StandardCharsets.UTF_8))
        require(root.optInt("schemaVersion", 0) == MANIFEST_SCHEMA) {
            "unsupported runtime asset manifest schema"
        }

        val enginesObject = root.optJSONObject("engines") ?: JSONObject()
        val engineVersions = linkedMapOf<String, String>()
        val engineBundleBytes = linkedMapOf<String, Long>()
        for (engineId in listOf("python", "node", "go")) {
            val engine = enginesObject.optJSONObject(engineId) ?: continue
            val version = if (engineId == "go") {
                Regex("(?i)^Yaegi\\s+v?(.+)$")
                    .matchEntire(engine.optString("interpreter").trim())
                    ?.groupValues
                    ?.get(1)
                    ?.trim()
                    .orEmpty()
                    .ifEmpty { engine.optString("version").trim() }
            } else {
                engine.optString("version").trim()
            }
            require(version.isNotEmpty()) { "runtime asset $engineId version is missing" }
            engineVersions[engineId] = version
            val declaredBundleBytes = engine.optLong("bundleBytes", -1)
            if (declaredBundleBytes >= 0) engineBundleBytes[engineId] = declaredBundleBytes
        }

        val files = root.optJSONArray("files") ?: JSONArray()
        require(files.length() <= MAX_FILE_COUNT) { "runtime asset manifest has too many files" }
        val records = ArrayList<AssetRecord>(files.length())
        var totalBytes = 0L
        for (index in 0 until files.length()) {
            val record = files.getJSONObject(index)
            val packagedPath = record.getString("path")
            if (!packagedPath.startsWith(ASSET_RECORD_PREFIX)) continue
            val relativePath = packagedPath.removePrefix(ASSET_RECORD_PREFIX)
            requireSafeRelativePath(relativePath)
            val size = record.getLong("size")
            val sha256 = record.getString("sha256").lowercase()
            require(size >= 0) { "runtime asset file size is invalid" }
            require(sha256.matches(Regex("[0-9a-f]{64}"))) { "runtime asset sha256 is invalid" }
            totalBytes = Math.addExact(totalBytes, size)
            require(totalBytes <= MAX_BUNDLE_BYTES) { "runtime asset bundle is too large" }
            records += AssetRecord(
                assetPath = packagedPath.removePrefix("assets/"),
                relativePath = relativePath,
                size = size,
                sha256 = sha256,
            )
        }
        for (engineId in engineVersions.keys.intersect(setOf("python", "node"))) {
            require(records.any { it.relativePath.startsWith("$engineId/") }) {
                "runtime asset $engineId data is missing"
            }
        }
        val engineDataBytes = engineVersions.keys.associateWith { engineId ->
            records.filter { it.relativePath.startsWith("$engineId/") }
                .fold(0L) { total, record -> Math.addExact(total, record.size) }
        }
        return ParsedAssetManifest(
            raw = raw.copyOf(),
            hash = sha256(raw),
            records = records,
            engineVersions = engineVersions,
            engineDataBytes = engineDataBytes,
            engineBundleBytes = engineBundleBytes,
        )
    }

    private fun prepareParsed(context: Context, manifest: ParsedAssetManifest): RuntimeEngineBundle {
        val toolchainsRoot = File(context.filesDir, "runtime-toolchains").apply {
            require(mkdirs() || isDirectory) { "cannot create runtime-toolchains directory" }
        }
        val bundlesRoot = File(toolchainsRoot, "bundles").apply {
            require(mkdirs() || isDirectory) { "cannot create runtime bundle directory" }
        }
        val bundleName = "bundle-${manifest.hash.take(24)}"
        val activeBundle = File(bundlesRoot, bundleName)
        if (isComplete(activeBundle, manifest)) {
            activeBundle.setLastModified(System.currentTimeMillis())
            return RuntimeEngineBundle(
                activeBundle,
                File(activeBundle, "manifest.json"),
                manifest.engineVersions,
                manifest.engineDataBytes,
                manifest.engineBundleBytes,
            )
        }

        val staging = File(bundlesRoot, ".$bundleName.tmp-${UUID.randomUUID()}")
        require(staging.mkdirs()) { "cannot create runtime bundle staging directory" }
        try {
            for (record in manifest.records) {
                extractRecord(context, staging, record)
            }
            writeSynced(File(staging, "manifest.json"), manifest.raw)
            writeSynced(File(staging, ".complete"), (manifest.hash + "\n").toByteArray())
            require(isComplete(staging, manifest)) { "runtime bundle verification failed" }
            activateBundle(staging, activeBundle)
            activeBundle.setLastModified(System.currentTimeMillis())
            cleanupOldBundles(bundlesRoot, activeBundle)
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
        return RuntimeEngineBundle(
            activeBundle,
            File(activeBundle, "manifest.json"),
            manifest.engineVersions,
            manifest.engineDataBytes,
            manifest.engineBundleBytes,
        )
    }

    private fun activateBundle(staging: File, activeBundle: File) {
        var displaced: File? = null
        if (activeBundle.exists()) {
            displaced = File(
                activeBundle.parentFile,
                ".${activeBundle.name}.replaced-${UUID.randomUUID()}",
            )
            Os.rename(activeBundle.absolutePath, displaced.absolutePath)
        }
        try {
            Os.rename(staging.absolutePath, activeBundle.absolutePath)
        } catch (error: Throwable) {
            if (displaced != null && displaced.exists() && !activeBundle.exists()) {
                runCatching { Os.rename(displaced.absolutePath, activeBundle.absolutePath) }
            }
            throw error
        }
        displaced?.deleteRecursively()
    }

    private fun extractRecord(context: Context, staging: File, record: AssetRecord) {
        val destination = File(staging, record.relativePath)
        require(destination.parentFile?.mkdirs() != false || destination.parentFile?.isDirectory == true) {
            "cannot create runtime asset directory"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        context.assets.open(record.assetPath).use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    copied += count
                    require(copied <= record.size) { "runtime asset exceeds declared size" }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
                output.flush()
                output.fd.sync()
            }
        }
        require(copied == record.size) { "runtime asset size mismatch" }
        require(hex(digest.digest()) == record.sha256) { "runtime asset sha256 mismatch" }
    }

    internal fun isComplete(bundle: File, manifest: ParsedAssetManifest): Boolean {
        return try {
            if (!bundle.isDirectory) return false
            if (File(bundle, ".complete").readTextOrNull()?.trim() != manifest.hash) return false
            if (sha256(File(bundle, "manifest.json")) != manifest.hash) return false
            if (!hasExactBundleTree(bundle, manifest.records)) return false
            manifest.records.all { record ->
                val file = File(bundle, record.relativePath)
                file.isFile && file.length() == record.size && sha256(file) == record.sha256
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun hasExactBundleTree(bundle: File, records: List<AssetRecord>): Boolean {
        val expectedFiles = records.mapTo(mutableSetOf()) { it.relativePath }
        expectedFiles += "manifest.json"
        expectedFiles += ".complete"
        val expectedDirectories = mutableSetOf("")
        for (path in expectedFiles) {
            val segments = path.split('/').dropLast(1)
            var current = ""
            for (segment in segments) {
                current = if (current.isEmpty()) segment else "$current/$segment"
                expectedDirectories += current
            }
        }

        val canonicalRoot = bundle.canonicalFile
        val pending = ArrayDeque<Pair<File, String>>()
        val canonicalDirectories = mutableSetOf(canonicalRoot.absolutePath)
        val canonicalFiles = mutableSetOf<String>()
        val seenFiles = mutableSetOf<String>()
        pending.add(bundle to "")
        while (pending.isNotEmpty()) {
            val (directory, prefix) = pending.removeFirst()
            val children = directory.listFiles() ?: return false
            for (child in children) {
                val relative = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                val canonical = child.canonicalFile
                if (!pathWithin(canonicalRoot, canonical)) return false
                when {
                    child.isDirectory -> {
                        if (relative !in expectedDirectories) return false
                        if (!canonicalDirectories.add(canonical.absolutePath)) return false
                        pending.add(child to relative)
                    }
                    child.isFile -> {
                        if (relative !in expectedFiles || !seenFiles.add(relative)) return false
                        if (!canonicalFiles.add(canonical.absolutePath)) return false
                    }
                    else -> return false
                }
            }
        }
        return seenFiles == expectedFiles
    }

    private fun pathWithin(root: File, candidate: File): Boolean {
        val rootPath = root.absolutePath.trimEnd(File.separatorChar)
        val candidatePath = candidate.absolutePath
        return candidatePath == rootPath || candidatePath.startsWith(rootPath + File.separator)
    }

    private fun cleanupOldBundles(bundlesRoot: File, activeBundle: File) {
        val oldBundles = bundlesRoot.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("bundle-") && it != activeBundle }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        oldBundles.drop(1).forEach { it.deleteRecursively() }
    }

    private fun requireSafeRelativePath(path: String) {
        require(path.isNotBlank() && !path.startsWith('/') && '\\' !in path) {
            "runtime asset path is invalid"
        }
        require(path.split('/').all { it.isNotBlank() && it != "." && it != ".." }) {
            "runtime asset path traversal is forbidden"
        }
    }

    private fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= maxBytes) { "runtime asset manifest is too large" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()

}

/** Builds the backend-facing capability manifest from verified data + real native entries. */
internal object AndroidRuntimeManifest {
    const val nativeLibraryDirEnvironment = "VANTALOOM_ANDROID_NATIVE_LIB_DIR"
    const val manifestEnvironment = "VANTALOOM_ANDROID_RUNTIME_MANIFEST"
    const val runtimeDataDirEnvironment = "VANTALOOM_RUNTIME_DATA_DIR"
    const val runtimeBundleDirEnvironment = "VANTALOOM_RUNTIME_BUNDLE_DIR"
    const val appFilesDirEnvironment = "VANTALOOM_APP_FILES_DIR"
    const val packageManifestEnvironment = "VANTALOOM_MOBILE_RUNTIME_MANIFEST"

    private const val MANIFEST_SCHEMA = 1
    private const val PYTHON_SHARED_LIBRARY = "libpython3.14.so"

    private data class EngineSpec(
        val id: String,
        val displayName: String,
        val kind: String,
        val entryFileName: String,
        val requiredNativeFiles: List<String>,
        val license: String,
        val packageInstallPolicy: String,
        val requiresDataBundle: Boolean,
        val supportsHttpServer: Boolean,
        val loopbackEnforced: Boolean,
        val entryArgs: List<String>,
        val fallbackVersion: String = "",
        val source: (String) -> String,
    )

    private val engineSpecs = listOf(
        EngineSpec(
            id = "python",
            displayName = "Python",
            kind = "cpython",
            entryFileName = "libvantaloom_python.so",
            requiredNativeFiles = listOf(PYTHON_SHARED_LIBRARY),
            license = "PSF-2.0",
            packageInstallPolicy = "仅支持纯 Python 包；原生扩展不可下载执行，安装到应用私有 state",
            requiresDataBundle = true,
            supportsHttpServer = true,
            loopbackEnforced = true,
            entryArgs = listOf("python"),
            source = { version ->
                "https://www.python.org/ftp/python/$version/python-$version-aarch64-linux-android.tar.gz"
            },
        ),
        EngineSpec(
            id = "node",
            displayName = "Node.js",
            kind = "node",
            entryFileName = "libvantaloom_python.so",
            requiredNativeFiles = listOf(PYTHON_SHARED_LIBRARY, "libvantaloom_node.so"),
            license = "MIT",
            packageInstallPolicy = "仅支持纯 JS npm 包；生命周期脚本永久禁用，不支持原生扩展或子进程",
            requiresDataBundle = true,
            supportsHttpServer = true,
            loopbackEnforced = true,
            entryArgs = listOf("node"),
            source = { version -> "https://nodejs.org/dist/v$version/node-v$version.tar.xz" },
        ),
        EngineSpec(
            id = "go",
            displayName = "Go（Yaegi）",
            kind = "yaegi",
            entryFileName = "libvantaloom_go.so",
            requiredNativeFiles = emptyList(),
            license = "Apache-2.0",
            packageInstallPolicy = "不提供 unsafe、syscall、os/exec 或 cgo；仅支持短命令，非安全沙箱",
            requiresDataBundle = false,
            supportsHttpServer = false,
            loopbackEnforced = false,
            entryArgs = emptyList(),
            fallbackVersion = "0.16.1",
            source = { version ->
                "https://proxy.golang.org/github.com/traefik/yaegi/@v/v$version.zip"
            },
        ),
    )

    fun rebuild(
        filesDir: File,
        nativeLibraryDir: String,
        bundle: RuntimeEngineBundle?,
        atomicMove: (source: File, target: File) -> Unit = ::atomicMove,
    ): File {
        require(File(nativeLibraryDir).isAbsolute) { "nativeLibraryDir must be absolute" }
        val root = File(filesDir, "runtime-toolchains").apply {
            require(mkdirs() || isDirectory) { "cannot create runtime-toolchains directory" }
        }
        runtimeStateDirectory(filesDir)
        val target = File(root, "manifest.json")
        val temporary = File.createTempFile(".manifest-", ".tmp", root)
        try {
            writeSynced(
                temporary,
                (buildJson(filesDir, nativeLibraryDir, bundle) + "\n")
                    .toByteArray(StandardCharsets.UTF_8),
            )
            atomicMove(temporary, target)
            return target
        } catch (error: Throwable) {
            temporary.delete()
            // Never leave a previous install's nativeLibraryDir available to a
            // newly started child when rebuilding the current contract failed.
            target.delete()
            throw error
        }
    }

    internal fun buildJson(
        filesDir: File,
        nativeLibraryDir: String,
        bundle: RuntimeEngineBundle?,
    ): String {
        val engines = JSONArray()
        val runtimeStateDir = runtimeStateDirectory(filesDir)
        for (spec in engineSpecs) {
            val entryPoint = File(nativeLibraryDir, spec.entryFileName)
            if (!entryPoint.isFile || spec.requiredNativeFiles.any { !File(nativeLibraryDir, it).isFile }) {
                continue
            }
            val version = bundle?.engineVersions?.get(spec.id)?.trim().orEmpty()
                .ifEmpty { spec.fallbackVersion }
            if (version.isEmpty()) continue

            val environment: JSONObject
            val fallbackBundleBytes: Long
            if (spec.requiresDataBundle) {
                if (bundle == null || !bundle.packageManifest.isFile) continue
                val engineData = File(bundle.bundleDir, spec.id)
                val engineDataBytes = bundle.engineDataBytes[spec.id] ?: continue
                if (!engineData.isDirectory) continue
                fallbackBundleBytes = Math.addExact(entryPoint.length(), engineDataBytes)
                environment = JSONObject()
                    .put(runtimeDataDirEnvironment, runtimeStateDir.absolutePath)
                    .put(runtimeBundleDirEnvironment, bundle.bundleDir.absolutePath)
                    .put(appFilesDirEnvironment, filesDir.absolutePath)
                    .put(packageManifestEnvironment, bundle.packageManifest.absolutePath)
            } else {
                fallbackBundleBytes = entryPoint.length()
                environment = JSONObject()
                    .put(runtimeDataDirEnvironment, runtimeStateDir.absolutePath)
                    .put(appFilesDirEnvironment, filesDir.absolutePath)
                if (bundle != null && bundle.packageManifest.isFile) {
                    environment
                        .put(runtimeBundleDirEnvironment, bundle.bundleDir.absolutePath)
                        .put(packageManifestEnvironment, bundle.packageManifest.absolutePath)
                }
            }
            val engine = JSONObject()
                .put("id", spec.id)
                .put("displayName", spec.displayName)
                .put("kind", spec.kind)
                .put("version", version)
                .put("entryPoint", entryPoint.absolutePath)
                .put("versionArgs", JSONArray().put("--version"))
                .put("source", spec.source(version))
                .put("license", spec.license)
                .put("bundleBytes", bundle?.engineBundleBytes?.get(spec.id) ?: fallbackBundleBytes)
                .put("supportsHttpServer", spec.supportsHttpServer)
                .put("loopbackEnforced", spec.loopbackEnforced)
                .put("packageInstallPolicy", spec.packageInstallPolicy)
            if (spec.entryArgs.isNotEmpty()) {
                val entryArgs = JSONArray()
                spec.entryArgs.forEach { entryArgs.put(it) }
                engine.put("entryArgs", entryArgs)
            }
            engine.put("environment", environment)
            engines.put(engine)
        }
        return JSONObject()
            .put("schema", MANIFEST_SCHEMA)
            .put("nativeLibraryDir", nativeLibraryDir)
            .put("engines", engines)
            .toString()
    }

    private fun atomicMove(source: File, target: File) {
        Os.rename(source.absolutePath, target.absolutePath)
    }

    private fun runtimeStateDirectory(filesDir: File): File =
        File(filesDir, "runtime-toolchains/state").apply {
            require(mkdirs() || isDirectory) { "cannot create runtime state directory" }
        }
}

private fun writeSynced(target: File, bytes: ByteArray) {
    require(target.parentFile?.mkdirs() != false || target.parentFile?.isDirectory == true) {
        "cannot create parent directory"
    }
    FileOutputStream(target).use { output ->
        output.write(bytes)
        output.flush()
        output.fd.sync()
    }
}

private fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return hex(digest.digest())
}

private fun hex(bytes: ByteArray): String {
    val alphabet = "0123456789abcdef"
    val result = CharArray(bytes.size * 2)
    bytes.forEachIndexed { index, value ->
        val unsigned = value.toInt() and 0xff
        result[index * 2] = alphabet[unsigned ushr 4]
        result[index * 2 + 1] = alphabet[unsigned and 0x0f]
    }
    return String(result)
}
