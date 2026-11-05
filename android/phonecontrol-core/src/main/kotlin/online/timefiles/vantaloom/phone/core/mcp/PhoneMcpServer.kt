package online.timefiles.vantaloom.phone.core.mcp

import online.timefiles.vantaloom.phone.core.PhoneEngine
import online.timefiles.vantaloom.phone.core.json.Json
import java.security.SecureRandom

/** 服务配置（CONTRACTS.md）。port=0 表示随机端口。 */
data class ServerConfig(
    val port: Int = 0,
    val maxBodyBytes: Int = 1 shl 20,
    val workerThreads: Int = 4,
    val tokenBytes: Int = 32,
)

/**
 * 当前端点。token 是秘密：[toString] 打码，避免被日志顺手打出来。
 */
data class McpEndpoint(val url: String, val port: Int, val token: String, val startedAtMs: Long) {
    /** 协议 §1 的 endpoint.json 内容。文件权限（0600）由平台侧负责。 */
    fun toJson(pid: Int): String = Json.stringify(
        linkedMapOf("v" to 1L, "url" to url, "token" to token, "pid" to pid.toLong(), "startedAt" to startedAtMs),
    )

    override fun toString(): String = "McpEndpoint(url=$url, port=$port, token=***, startedAtMs=$startedAtMs)"
}

/**
 * 组合：[PhoneEngine] + [McpHandler] + [LoopbackHttpServer]。每次 [start] 换新端口和新 token（协议 §1），
 * runtime 收到 401 时重读 endpoint.json。[stop] 不关 engine（engine 的生命周期跟无障碍服务走）。
 */
class PhoneMcpServer(private val engine: PhoneEngine, private val config: ServerConfig = ServerConfig()) {
    private val handler = McpHandler(engine)
    private val random = SecureRandom()
    private var http: LoopbackHttpServer? = null

    @Volatile
    private var current: McpEndpoint? = null

    /** 当前端点；未启动为 null。 */
    val endpoint: McpEndpoint? get() = current

    /** 启动：随机端口、新 token。重复调用返回当前端点。 */
    @Synchronized
    fun start(): McpEndpoint {
        current?.let { return it }
        val token = newToken(config.tokenBytes)
        val server = LoopbackHttpServer(
            token = token,
            handler = { body, remote -> handler.handle(body, remote) },
            port = config.port,
            maxBodyBytes = config.maxBodyBytes,
            workerThreads = config.workerThreads.coerceAtLeast(2),
        )
        val port = server.start()
        http = server
        val ep = McpEndpoint("http://127.0.0.1:$port${LoopbackHttpServer.MCP_PATH}", port, token, System.currentTimeMillis())
        current = ep
        return ep
    }

    /** 停止监听。之后可以再 [start]（会是新端口、新 token）。 */
    @Synchronized
    fun stop() {
        http?.stop()
        http = null
        current = null
    }

    /** 32 字节 SecureRandom，base64url 无填充（放进头部和 JSON 都不用转义）。 */
    private fun newToken(bytes: Int): String {
        val b = ByteArray(bytes.coerceAtLeast(16))
        random.nextBytes(b)
        return Base64Codec.encodeUrlNoPad(b)
    }
}
