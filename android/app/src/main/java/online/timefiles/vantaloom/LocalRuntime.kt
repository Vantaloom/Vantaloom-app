package online.timefiles.vantaloom

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

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
 *  - 端口：--port 8780 起步 + ListenWithFallback 自动上探，实际端口经
 *    --port-file 写盘由壳读回（每次冷启动都可能不同，web 侧从不持久化端口）。
 */
object LocalRuntime {
    private const val TAG = "VantaloomRuntime"

    @Volatile private var process: Process? = null
    @Volatile private var port: Int = 0

    /** 幂等启动：已在跑直接返回端口；死进程清理后重拉。阻塞至健康或超时。 */
    @Synchronized
    fun ensureStarted(context: Context): Int {
        val existing = process
        if (existing != null && existing.isAlive && port > 0 && probeHealth(port)) {
            return port
        }
        existing?.destroy()
        process = null
        port = 0

        val exe = File(context.applicationInfo.nativeLibraryDir, "libvantaloom.so")
        if (!exe.exists()) {
            throw IllegalStateException("本地运行时未随 APK 打包（libvantaloom.so 缺失）")
        }
        val dataDir = File(context.filesDir, "runtime-data").apply { mkdirs() }
        val portFile = File(context.cacheDir, "vantaloom-api-port")
        portFile.delete()

        val builder = ProcessBuilder(
            exe.absolutePath,
            "--host", "127.0.0.1",
            "--port", "8780",
            "--port-file", portFile.absolutePath,
            "--data-dir", dataDir.absolutePath,
        )
        builder.redirectErrorStream(true)
        val env = builder.environment()
        env["HOME"] = context.filesDir.absolutePath
        env["TMPDIR"] = context.cacheDir.absolutePath
        env["VANTALOOM_DNS"] = systemDnsServers(context)
        env["VANTALOOM_VERSION"] = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "android"

        val proc = builder.start()
        process = proc
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
            if (!proc.isAlive) {
                process = null
                throw IllegalStateException("本地运行时进程已退出（logcat 过滤 $TAG 查看原因）")
            }
            readPort = runCatching { portFile.readText().trim().toInt() }.getOrDefault(0)
            if (readPort > 0 && probeHealth(readPort)) break
            readPort = 0
            Thread.sleep(200)
        }
        if (readPort <= 0) {
            proc.destroy()
            process = null
            throw IllegalStateException("本地运行时启动超时（45s 内未就绪）")
        }
        port = readPort
        Log.i(TAG, "local runtime ready on 127.0.0.1:$readPort")
        return readPort
    }

    fun stop() {
        process?.destroy()
        process = null
        port = 0
    }

    private fun probeHealth(candidate: Int): Boolean = try {
        val conn = URL("http://127.0.0.1:$candidate/v1/hub/status")
            .openConnection() as HttpURLConnection
        conn.connectTimeout = 800
        conn.readTimeout = 800
        val code = conn.responseCode
        conn.disconnect()
        code in 200..499
    } catch (_: Throwable) {
        false
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
