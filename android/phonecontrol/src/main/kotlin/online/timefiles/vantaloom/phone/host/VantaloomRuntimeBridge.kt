package online.timefiles.vantaloom.phone.host

import android.content.Context
import android.util.Log
import online.timefiles.vantaloom.phone.core.json.Json
import online.timefiles.vantaloom.phone.core.mcp.McpEndpoint
import online.timefiles.vantaloom.phone.core.ports.Clock
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Vantaloom runtime 接入适配器（设计文档 §4「Go 侧零改动」）。并入 Vantaloom-app 后由 LocalRuntime 在
 * runtime 就绪时 [onRuntimeReady]，runtime 退出时 [onRuntimeGone]。
 *
 * 做的事（全部幂等）：
 *  1. 把 assets/vantaloom-phone-plugin/plugin.json 物化到 files/phone-mcp/plugin/。
 *  2. `GET /v1/plugins` 看插件在不在、什么状态；不在 → `POST /v1/plugins/install {"path"}`；
 *     清单版本变了 → 重新 install（Upsert 语义，授权会按需回落）。
 *  3. 授权不够 → `POST /v1/plugins/vantaloom-phone/grant {"permissions":{"network":["127.0.0.1"]}}`。
 *  4. 没启用 → `POST /v1/plugins/vantaloom-phone/enable`。
 *  5. 每次端点变化 / runtime 重启 → `PATCH /v1/plugins/vantaloom-phone/config`
 *     `{"config":{"mcp":{"url":…,"headers":{"Authorization":"Bearer …"}}}}`。runtime 改配置会立即 reload 插件。
 *
 * 设计取舍：
 *  - **先 PATCH 再 enable**：enable 时插件就会去连 url；如果先 enable，会先连一次占位地址 127.0.0.1:1 失败。
 *  - **服务停了也不 disable 插件**：disable 会改变用户可见的插件开关；工具调用失败（连接被拒）本身就是
 *    「手机操控不可用」的正确信号，且下次服务起来 PATCH 一次即恢复。
 *  - 不打印 token：日志里只出现 URL 与 HTTP 状态码；请求体不记录。
 *  - HttpURLConnection + 有限超时 + 指数退避（最多 [MAX_ATTEMPTS] 次）；全部在单线程 [worker] 串行，
 *    新事件到来时旧的同步自然排在后面，最后一次一定是最新的端点。
 *
 * 可测性：HTTP 调用集中在 [Http] 接口，测试可注入假实现；时间走 [Clock]。
 */
class VantaloomRuntimeBridge(
    context: Context,
    private val clock: Clock = Clock.System,
    private val http: Http = UrlConnectionHttp(),
) : PhoneControlHost.EndpointListener {

    /** runtime 的 HTTP 接口。返回 (状态码, 响应体)。网络错误抛 IOException。 */
    interface Http {
        fun request(method: String, url: String, bearer: String, body: String?): Pair<Int, String>
    }

    /** 同步结果（demo / 前端显示）。 */
    data class SyncState(val ok: Boolean, val step: String, val message: String?, val atMs: Long)

    private val appContext = context.applicationContext
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "vantaloom-phone-bridge").apply { isDaemon = true }
    }

    @Volatile private var baseUrl: String? = null
    @Volatile private var bearer: String? = null
    @Volatile private var endpoint: McpEndpoint? = null
    @Volatile private var registered = false

    /** 最近一次同步的结果。 */
    @Volatile
    var lastState: SyncState? = null
        private set

    /** 开始监听端点变化（一般在宿主初始化时调用一次）。 */
    fun start() {
        PhoneControlHost.addEndpointListener(this)
    }

    /** 停止监听。 */
    fun stop() {
        PhoneControlHost.removeEndpointListener(this)
    }

    /**
     * runtime 就绪（或重启后就绪）。[runtimeBaseUrl] 形如 `http://127.0.0.1:<port>`，[runtimeBearer] 是
     * LocalRuntime.Endpoint 的 token。runtime 重启后插件记录还在但 url/token 已过期，所以这里一定重放 PATCH。
     */
    fun onRuntimeReady(runtimeBaseUrl: String, runtimeBearer: String) {
        baseUrl = runtimeBaseUrl.trimEnd('/')
        bearer = runtimeBearer
        registered = false
        schedule()
    }

    /** runtime 退出：记下来，等下次 [onRuntimeReady] 再同步。 */
    fun onRuntimeGone() {
        baseUrl = null
        bearer = null
        registered = false
    }

    override fun onEndpointChanged(endpoint: McpEndpoint?) {
        this.endpoint = endpoint
        if (endpoint != null) schedule()
    }

    private fun schedule() {
        worker.execute { syncWithRetry() }
    }

    private fun syncWithRetry() {
        var delay = INITIAL_BACKOFF_MS
        for (attempt in 1..MAX_ATTEMPTS) {
            val base = baseUrl ?: return
            val token = bearer ?: return
            try {
                sync(base, token)
                return
            } catch (e: FatalSync) {
                record(false, e.step, e.message)
                return
            } catch (e: Exception) {
                record(false, "retry", "第 $attempt 次失败：${e.javaClass.simpleName}: ${e.message}")
                if (attempt == MAX_ATTEMPTS) return
                clock.sleep(delay)
                delay = (delay * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    /** 一次完整同步。可重入、幂等。 */
    private fun sync(base: String, token: String) {
        if (!registered) {
            ensureRegistered(base, token)
            registered = true
        }
        val ep = endpoint
        if (ep == null) {
            record(true, "registered", "插件已就绪，等手机操控服务启动")
            return
        }
        patchConfig(base, token, ep)
        ensureEnabled(base, token)
        record(true, "synced", "已把端点 ${ep.url} 交给 runtime")
    }

    private fun ensureRegistered(base: String, token: String) {
        val manifest = materializePlugin()
        val bundledVersion = manifest.second
        val existing = findPlugin(base, token)
        val installedVersion = (existing?.get("manifest") as? Map<*, *>)?.get("version") as? String
        if (existing == null || installedVersion != bundledVersion) {
            val body = Json.stringify(Json.obj("path" to manifest.first.absolutePath))
            val (code, resp) = call("POST", "$base/v1/plugins/install", token, body)
            if (code !in 200..299) throw FatalSync("install", "安装插件失败（HTTP $code）：${errorOf(resp)}")
        }
        val after = findPlugin(base, token) ?: throw IOException("安装后仍查不到插件")
        if (after["needsGrant"] == true || existing == null) {
            val body = Json.stringify(
                Json.obj("permissions" to Json.obj("network" to listOf(LOOPBACK_HOST))),
            )
            val (code, resp) = call("POST", "$base/v1/plugins/$PLUGIN_ID/grant", token, body)
            if (code !in 200..299) throw FatalSync("grant", "授权失败（HTTP $code）：${errorOf(resp)}")
        }
    }

    private fun patchConfig(base: String, token: String, ep: McpEndpoint) {
        // 不写 stateless：Go 客户端在 stateless 模式下跳过 initialize，就拿不到服务端 instructions。
        val body = Json.stringify(
            Json.obj(
                "config" to Json.obj(
                    "mcp" to Json.obj(
                        "url" to ep.url,
                        "headers" to Json.obj("Authorization" to "Bearer ${ep.token}"),
                    ),
                ),
            ),
        )
        val (code, resp) = call("PATCH", "$base/v1/plugins/$PLUGIN_ID/config", token, body)
        if (code == 404) {
            // runtime 侧插件被用户卸载了：下一轮重新注册。
            registered = false
            throw IOException("插件不存在（可能被卸载），将重新安装")
        }
        if (code !in 200..299) throw IOException("PATCH config 失败（HTTP $code）：${errorOf(resp)}")
    }

    private fun ensureEnabled(base: String, token: String) {
        val p = findPlugin(base, token) ?: throw IOException("查不到插件")
        if (p["state"] == "enabled") return
        if (p["state"] == "disabled" && userDisabledRespected) {
            // 用户在 Vantaloom 的插件页里手动停用了它：尊重用户的选择，不强行启用。
            throw FatalSync("enable", "插件已被用户停用，未自动启用")
        }
        val (code, resp) = call("POST", "$base/v1/plugins/$PLUGIN_ID/enable", token, "{}")
        if (code !in 200..299) throw FatalSync("enable", "启用插件失败（HTTP $code）：${errorOf(resp)}")
    }

    /**
     * 用户手动停用插件后是否尊重（不再自动启用）。默认 true：自动启用只发生在首次安装后的 installed 状态。
     * 宿主若把「手机操控」做成独立开关，可以在用户打开开关时临时置 false 再同步一次。
     */
    @Volatile
    var userDisabledRespected: Boolean = true

    /** 用户在宿主界面明确要求启用时调用：本次同步会启用被停用的插件。 */
    fun forceEnable() {
        userDisabledRespected = false
        worker.execute {
            try {
                syncWithRetry()
            } finally {
                userDisabledRespected = true
            }
        }
    }

    private fun findPlugin(base: String, token: String): Map<*, *>? {
        val (code, resp) = call("GET", "$base/v1/plugins", token, null)
        if (code !in 200..299) throw IOException("GET /v1/plugins 失败（HTTP $code）")
        val root = try {
            Json.parseObject(resp)
        } catch (e: RuntimeException) {
            throw IOException("插件列表不是 JSON")
        }
        val list = root["plugins"] as? List<*> ?: return null
        for (item in list) {
            val m = item as? Map<*, *> ?: continue
            val id = (m["manifest"] as? Map<*, *>)?.get("id")
            if (id == PLUGIN_ID) return m
        }
        return null
    }

    /** 把 assets 里的 plugin.json 拷到 files/phone-mcp/plugin/，返回 (目录, 清单 version)。内容没变就不重写。 */
    private fun materializePlugin(): Pair<File, String?> {
        val text = appContext.assets.open(ASSET_MANIFEST).use { it.readBytes().toString(Charsets.UTF_8) }
        val dir = PhonePaths.pluginDir(appContext)
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, "plugin.json")
        if (!f.exists() || f.readText(Charsets.UTF_8) != text) f.writeText(text, Charsets.UTF_8)
        val version = try {
            Json.parseObject(text)["version"] as? String
        } catch (_: RuntimeException) {
            null
        }
        return dir to version
    }

    private fun call(method: String, url: String, token: String, body: String?): Pair<Int, String> {
        val r = http.request(method, url, token, body)
        // 只记方法、路径、状态码：url 里没有秘密，body 里有（PATCH 的 Authorization），绝不打印。
        Log.i(TAG, "$method ${url.substringAfter("/v1/", url)} → ${r.first}")
        return r
    }

    private fun errorOf(resp: String): String = try {
        (Json.parseObject(resp)["error"] as? String) ?: resp.take(200)
    } catch (_: RuntimeException) {
        resp.take(200)
    }

    private fun record(ok: Boolean, step: String, message: String?) {
        lastState = SyncState(ok, step, message, clock.nowMs())
        if (!ok) Log.w(TAG, "同步[$step]：$message")
    }

    /** 不重试的失败（4xx 业务错误、用户停用）。 */
    private class FatalSync(val step: String, override val message: String) : Exception(message)

    /** 默认 HTTP 实现：HttpURLConnection，连接 3s / 读 10s 超时。 */
    class UrlConnectionHttp(
        private val connectTimeoutMs: Int = 3_000,
        private val readTimeoutMs: Int = 10_000,
    ) : Http {
        override fun request(method: String, url: String, bearer: String, body: String?): Pair<Int, String> {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                // Android 的 HttpURLConnection（内置 OkHttp 实现）接受 PATCH；JVM 自带的 HttpURLConnection 不接受，
                // 那是测试时注入假 [Http] 的另一个理由。
                conn.requestMethod = method
                conn.connectTimeout = connectTimeoutMs
                conn.readTimeout = readTimeoutMs
                conn.useCaches = false
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("Authorization", "Bearer $bearer")
                conn.setRequestProperty("Accept", "application/json")
                if (body != null) {
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.setFixedLengthStreamingMode(bytes.size)
                    conn.outputStream.use { it.write(bytes) }
                }
                val code = conn.responseCode
                val stream = if (code >= 400) conn.errorStream else conn.inputStream
                val text = stream?.use { s -> s.readBytes().toString(Charsets.UTF_8) } ?: ""
                return code to text
            } finally {
                conn.disconnect()
            }
        }
    }

    companion object {
        private const val TAG = "VtlPhoneBridge"

        /** 插件 id（同时是 runtime 里的安装目录名，模型看到的工具前缀是 `vantaloom-phone__`）。 */
        const val PLUGIN_ID = "vantaloom-phone"
        private const val ASSET_MANIFEST = "vantaloom-phone-plugin/plugin.json"
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val MAX_ATTEMPTS = 5
        private const val INITIAL_BACKOFF_MS = 500L
        private const val MAX_BACKOFF_MS = 8_000L
    }
}
