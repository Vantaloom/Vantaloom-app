package online.timefiles.vantaloom

import android.content.Context
import android.net.ConnectivityManager
import android.system.Os
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * 手机本地运行时（0.14.29）：APK 里打包的完整 vantaloom-api（jniLibs 下的
 * libvantaloom.so —— 纯 Go 交叉编译产物，命名成 lib*.so 是为了让系统把它解压
 * 到 nativeLibraryDir，这是 Android 10+ 唯一可 exec 的应用自带路径；V2rayNG /
 * Termux 生态同款手法）以子进程方式启动。子进程崩溃不连坐壳；下次进入本地
 * 模式自动重拉。
 *
 * 关键环境：
 *  - VANTALOOM_DNS：Android 无 /etc/resolv.conf 且 netlink 被拒，Go 纯解析器
 *    会失聪——壳侧从 ConnectivityManager 取真实系统 DNS 传给运行时（缺席时
 *    运行时退公共 DNS）。
 *  - HOME/TMPDIR：Go 的 os.TempDir / UserHomeDir 在裸 Android 进程里没有可写
 *    缺省，指到应用私有目录。
 *  - VANTALOOM_LOOPBACK_CREDENTIALS_FILE：每次新建子进程都轮换两枚不同的
 *    256-bit 随机值，经应用私有 0600 一次性文件交给 Go；Go 读取即删除，避免
 *    普通 shell/终端从子进程环境或 /proc 读取 bearer/HMAC key。
 *  - VANTALOOM_ANDROID_*：每次拉起都按当前 nativeLibraryDir 原子重建能力清单；
 *    APK 内运行时数据按 manifest hash 完整校验后装入版本化 bundle，只有入口
 *    与对应数据都真实存在的 Python/Node 才会宣告可用；Go 仅需真实 Yaegi 入口。
 *  - 端口：--port 8780 起步 + ListenWithFallback 自动上探，实际端口经
 *    --port-file 写盘由壳读回（每次冷启动都可能不同，web 侧从不持久化端口）。
 */
object LocalRuntime {
    private const val TAG = "VantaloomRuntime"
    private const val LOOPBACK_CREDENTIAL_MODE = 384 // 0600

    @Volatile private var process: Process? = null
    @Volatile private var port: Int = 0
    @Volatile private var bearerToken: String? = null
    @Volatile private var capabilityToken: String? = null
    @Volatile private var stopRequested: Boolean = false

    data class Endpoint(
        val port: Int,
        val bearerToken: String,
        val capabilityToken: String,
    ) {
        val baseUrl: String = "http://127.0.0.1:$port"
    }

    /** 幂等启动：已在跑直接返回端点；死进程清理后重拉。阻塞至健康或超时。 */
    @Synchronized
    fun ensureStarted(context: Context): Endpoint {
        stopRequested = false
        val existing = process
        val existingToken = bearerToken
        val existingCapability = capabilityToken
        if (
            existing != null && existing.isAlive && port > 0 && existingToken != null &&
            existingCapability != null &&
            probeHealth(port, existingToken)
        ) {
            return Endpoint(port, existingToken, existingCapability)
        }
        existing?.destroy()
        process = null
        port = 0
        bearerToken = null
        capabilityToken = null

        val exe = File(context.applicationInfo.nativeLibraryDir, "libvantaloom.so")
        if (!exe.exists()) {
            throw IllegalStateException("本地运行时未随 APK 打包（libvantaloom.so 缺失）")
        }
        val dataDir = File(context.filesDir, "runtime-data").apply { mkdirs() }
        val portFile = File(context.cacheDir, "vantaloom-api-port")
        portFile.delete()
        val launchBearerToken = LoopbackAuth.newToken()
        val launchCapabilityToken = LoopbackAuth.newToken()
        check(launchCapabilityToken != launchBearerToken) { "本地运行时安全令牌生成失败" }
        val nativeLibraryDir = context.applicationInfo.nativeLibraryDir
        val runtimeBundle = RuntimeEngineAssets.prepare(context)
        val runtimeManifest = AndroidRuntimeManifest.rebuild(
            context.filesDir,
            nativeLibraryDir,
            runtimeBundle,
        )
        check(!stopRequested) { "本地运行时启动已取消" }
        purgeStaleLoopbackCredentials(context.cacheDir)
        var credentialsFile: File? = null
        var launchedProcess: Process? = null
        var launchCompleted = false
        var launchFailure: Throwable? = null
        try {
            val launchCredentialsFile = createLoopbackCredentialsFile(
                context,
                launchBearerToken,
                launchCapabilityToken,
            )
            credentialsFile = launchCredentialsFile
            val builder = ProcessBuilder(
                exe.absolutePath,
                "--host", "127.0.0.1",
                "--port", "8780",
                "--port-file", portFile.absolutePath,
                "--data-dir", dataDir.absolutePath,
            )
            builder.redirectErrorStream(true)
            replacePrivateRuntimeEnvironment(
                builder.environment(),
                runtimeEnvironment(
                    context,
                    launchCredentialsFile,
                    nativeLibraryDir,
                    runtimeManifest,
                    runtimeBundle,
                ),
            )

            val proc = builder.start()
            launchedProcess = proc
            process = proc
            check(!stopRequested) { "本地运行时启动已取消" }

            // 运行时日志进 logcat——手机上唯一的诊断通路。通知标记行被截获转成
            // 系统通知（RuntimeNotifications），不进 logcat。
            val appContext = context.applicationContext
            Thread {
                try {
                    proc.inputStream.bufferedReader().forEachLine { line ->
                        if (!RuntimeNotifications.handleMarkerLine(appContext, line)) {
                            Log.i(TAG, line)
                        }
                    }
                } catch (_: Throwable) {
                    // 进程退出时流关闭，正常收尾。
                }
            }.apply {
                isDaemon = true
                name = "vantaloom-runtime-log"
            }.start()

            // 就绪等待：端口文件落盘 + 健康探测。首启含数据目录初始化，放宽到 45s。
            val deadline = System.currentTimeMillis() + 45_000
            var readPort = 0
            while (System.currentTimeMillis() < deadline) {
                check(!stopRequested) { "本地运行时启动已取消" }
                if (!proc.isAlive) {
                    throw IllegalStateException("本地运行时进程已退出（logcat 过滤 $TAG 查看原因）")
                }
                readPort = runCatching { portFile.readText().trim().toInt() }.getOrDefault(0)
                if (readPort > 0 && probeHealth(readPort, launchBearerToken)) break
                readPort = 0
                Thread.sleep(200)
            }
            check(readPort > 0) { "本地运行时启动超时（45s 内未就绪）" }
            check(!stopRequested && proc.isAlive) { "本地运行时启动已取消" }

            port = readPort
            bearerToken = launchBearerToken
            capabilityToken = launchCapabilityToken
            launchCompleted = true
            Log.i(TAG, "local runtime ready on 127.0.0.1:$readPort")
            return Endpoint(readPort, launchBearerToken, launchCapabilityToken)
        } catch (error: Throwable) {
            launchFailure = error
            throw error
        } finally {
            val cleanupFailure = credentialsFile?.let(::deleteLoopbackCredentialsFile)
            if (!launchCompleted || cleanupFailure != null) {
                launchedProcess?.destroy()
                if (process === launchedProcess) {
                    process = null
                }
                port = 0
                bearerToken = null
                capabilityToken = null
            }
            if (cleanupFailure != null) {
                val failure = launchFailure
                if (failure != null) {
                    failure.addSuppressed(cleanupFailure)
                } else {
                    throw cleanupFailure
                }
            }
        }
    }

    fun requestStop() {
        stopRequested = true
        process?.destroy()
    }

    fun stop() {
        requestStop()
        synchronized(this) {
            process?.destroy()
            process = null
            port = 0
            bearerToken = null
            capabilityToken = null
        }
    }

    @Synchronized
    fun isRunning(): Boolean =
        !stopRequested && process?.isAlive == true && port > 0 &&
            bearerToken != null && capabilityToken != null

    /** Rehydrates a newly loaded WebView document without rotating the live process token. */
    @Synchronized
    fun currentEndpoint(): Endpoint? {
        val token = bearerToken
        val capability = capabilityToken
        if (
            !stopRequested && process?.isAlive == true && port > 0 &&
            token != null && capability != null
        ) {
            return Endpoint(port, token, capability)
        }
        if (process?.isAlive != true) {
            process = null
            port = 0
            bearerToken = null
            capabilityToken = null
        }
        return null
    }

    fun authorizeLocalRuntimeUrl(raw: String): String {
        val endpoint = currentEndpoint() ?: return raw
        return LoopbackCapabilitySigner.authorize(
            raw,
            endpoint.port,
            endpoint.capabilityToken,
        ) ?: raw
    }

    private fun probeHealth(candidate: Int, token: String): Boolean = try {
        val conn = URL("http://127.0.0.1:$candidate/v1/hub/status")
            .openConnection() as HttpURLConnection
        conn.connectTimeout = 800
        conn.readTimeout = 800
        conn.setRequestProperty("Authorization", "Bearer $token")
        val code = conn.responseCode
        conn.disconnect()
        code in 200..299
    } catch (_: Throwable) {
        false
    }

    /** Single extension seam for all shell-to-runtime launch capabilities. */
    private fun runtimeEnvironment(
        context: Context,
        credentialsFile: File,
        nativeLibraryDir: String,
        runtimeManifest: File,
        runtimeBundle: RuntimeEngineBundle?,
    ): Map<String, String> = linkedMapOf(
        "HOME" to context.filesDir.absolutePath,
        "TMPDIR" to context.cacheDir.absolutePath,
        "VANTALOOM_DNS" to systemDnsServers(context),
        LoopbackAuth.credentialsFileEnvironmentVariable to credentialsFile.absolutePath,
        AndroidRuntimeManifest.nativeLibraryDirEnvironment to nativeLibraryDir,
        AndroidRuntimeManifest.manifestEnvironment to runtimeManifest.absolutePath,
        AndroidRuntimeManifest.runtimeDataDirEnvironment to File(
            context.filesDir,
            "runtime-toolchains/state",
        ).absolutePath,
        AndroidRuntimeManifest.appFilesDirEnvironment to context.filesDir.absolutePath,
        "VANTALOOM_VERSION" to (
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: "android"
        ),
    ).apply {
        if (runtimeBundle != null) {
            put(
                AndroidRuntimeManifest.runtimeBundleDirEnvironment,
                runtimeBundle.bundleDir.absolutePath,
            )
            put(
                AndroidRuntimeManifest.packageManifestEnvironment,
                runtimeBundle.packageManifest.absolutePath,
            )
        }
    }

    internal fun replacePrivateRuntimeEnvironment(
        environment: MutableMap<String, String>,
        runtimeValues: Map<String, String>,
    ) {
        val privateNames = setOf(
            LoopbackAuth.bearerEnvironmentVariable,
            LoopbackAuth.capabilityEnvironmentVariable,
            LoopbackAuth.credentialsFileEnvironmentVariable,
            "VANTALOOM_HUB_TOKEN",
            "HUB_JWT_SECRET",
        )
        environment.keys
            .filter { key -> privateNames.any { it.equals(key, ignoreCase = true) } }
            .forEach(environment::remove)
        environment.putAll(runtimeValues)
    }

    internal fun purgeStaleLoopbackCredentials(cacheDir: File) {
        cacheDir.listFiles()
            ?.filter { it.name.startsWith(".vantaloom-loopback-") }
            ?.forEach { stale ->
                if (!stale.delete() && stale.exists()) {
                    throw IllegalStateException("无法清理上次遗留的本地运行时凭据")
                }
            }
    }

    private fun createLoopbackCredentialsFile(
        context: Context,
        bearerToken: String,
        capabilityToken: String,
    ): File {
        val target = File.createTempFile(
            ".vantaloom-loopback-",
            ".json",
            context.cacheDir,
        )
        return try {
            Os.chmod(target.absolutePath, LOOPBACK_CREDENTIAL_MODE)
            val payload = JSONObject()
                .put("bearerToken", bearerToken)
                .put("capabilityToken", capabilityToken)
                .toString()
                .toByteArray(Charsets.UTF_8)
            FileOutputStream(target).use { output ->
                output.write(payload)
                output.flush()
                output.fd.sync()
            }
            target
        } catch (error: Throwable) {
            deleteLoopbackCredentialsFile(target)?.let(error::addSuppressed)
            throw error
        }
    }

    private fun deleteLoopbackCredentialsFile(target: File): Throwable? {
        if (target.delete() || !target.exists()) {
            return null
        }
        return IllegalStateException("本地运行时凭据文件清理失败")
    }

    /** 当前网络的真实 DNS（逗号分隔），供运行时的 android resolver 使用。 */
    private fun systemDnsServers(context: Context): String = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val props = cm.getLinkProperties(cm.activeNetwork)
        props?.dnsServers?.mapNotNull { it.hostAddress }?.joinToString(",") ?: ""
    } catch (_: Throwable) {
        ""
    }
}
