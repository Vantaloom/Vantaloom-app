package online.timefiles.vantaloom.phone.core.mcp

import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 只绑 127.0.0.1 的最小 HTTP/1.1 服务（协议 §1）。
 *
 * 为什么自己写：Android 没有 com.sun.net.httpserver，core 又要求零依赖。只服务一个端点、一个客户端（本机 runtime），
 * 所以做得很窄：
 *
 * - **一律 `Connection: close`**：每个连接只处理一个请求。回环建连的成本可以忽略，而 Keep-Alive 要处理管线化、
 *   连接复用时的半包、空闲超时……在这里只会增加攻击面。Go 的 http.Client 看到 close 会自动重新拨号。
 * - **只接受 Content-Length 体**：`Transfer-Encoding: chunked` 回 411（Length Required）。
 *   MCP 客户端发的都是小 JSON，Go/Node 的客户端在知道体长时都会发 Content-Length；不实现 chunked 解码
 *   可以少一类请求走私（request smuggling）和无界读取的风险。
 * - 限制：请求行+头部总计 16 KB，头部最多 100 个，体 [maxBodyBytes]（超出 413），读超时 30 s。
 * - 固定线程池 + 有界队列：队列满时直接回 503，而不是无限堆积连接。
 *
 * 安全检查顺序（先便宜、先不泄露信息的）：Host → Origin → 路由/方法 → Bearer → Content-Type → 体。
 * 鉴权放在路由之后是为了让 404/405 不用带 token 也能诊断；它们不暴露任何状态。
 */
class LoopbackHttpServer(
    private val token: String,
    private val handler: (body: String, remoteCaller: String?) -> String?,
    private val port: Int = 0,
    private val maxBodyBytes: Int = 1 shl 20,
    private val workerThreads: Int = 4,
    private val readTimeoutMs: Int = 30_000,
) {
    @Volatile
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var pool: ExecutorService? = null
    private val tokenBytes = ("Bearer $token").toByteArray(Charsets.UTF_8)

    /** 实际监听端口；未启动为 -1。 */
    val localPort: Int get() = serverSocket?.localPort ?: -1

    /** 启动。重复调用无副作用，返回端口。 */
    @Synchronized
    fun start(): Int {
        serverSocket?.let { return it.localPort }
        // 绑定地址写死 127.0.0.1：绝不听 0.0.0.0，也不用 InetAddress.getLoopbackAddress()（可能是 ::1，
        // 而 endpoint.json 里写的是 127.0.0.1）。
        val ss = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
        val threadNo = AtomicInteger()
        val exec = ThreadPoolExecutor(
            workerThreads, workerThreads, 30, TimeUnit.SECONDS,
            java.util.concurrent.ArrayBlockingQueue(workerThreads * 8),
        ) { r -> Thread(r, "vantaloom-phone-http-${threadNo.incrementAndGet()}").apply { isDaemon = true } }
        pool = exec
        serverSocket = ss
        acceptThread = Thread({ acceptLoop(ss, exec) }, "vantaloom-phone-accept").apply { isDaemon = true; start() }
        return ss.localPort
    }

    /** 停止：关监听 socket（accept 线程随之退出），尽力结束工作线程。正在执行的工具调用不会被中断。 */
    @Synchronized
    fun stop() {
        try { serverSocket?.close() } catch (_: IOException) {}
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
        pool?.shutdownNow()
        pool = null
    }

    private fun acceptLoop(ss: ServerSocket, exec: ExecutorService) {
        while (!ss.isClosed) {
            val s: Socket = try {
                ss.accept()
            } catch (_: SocketException) {
                break // 关闭
            } catch (_: IOException) {
                continue
            }
            try {
                exec.execute { serve(s) }
            } catch (_: RejectedExecutionException) {
                // 队列满：立刻回 503，客户端可以重试。用很短的写超时，避免卡住 accept 线程。
                try {
                    s.soTimeout = 1_000
                    writeResponse(s.getOutputStream(), 503, "Service Unavailable", jsonError("服务忙，稍后重试"))
                } catch (_: IOException) {
                } finally {
                    closeQuietly(s)
                }
            }
        }
    }

    // ---------------------------------------------------------------- 单连接

    private class HttpError(val status: Int, val reason: String, val msg: String) : Exception(msg)

    private class Request(val method: String, val path: String, val headers: Map<String, String>)

    private fun serve(s: Socket) {
        try {
            s.soTimeout = readTimeoutMs
            s.tcpNoDelay = true
            val input = s.getInputStream()
            val output = s.getOutputStream()
            try {
                val req = readHead(input)
                route(req, input, output)
            } catch (e: HttpError) {
                writeResponse(output, e.status, e.reason, jsonError(e.msg), extraHeaders(e.status))
            } catch (_: SocketTimeoutException) {
                writeResponse(output, 408, "Request Timeout", jsonError("读取请求超时"))
            }
        } catch (_: IOException) {
            // 客户端断开等，无从回报
        } catch (_: Throwable) {
            try { writeResponse(s.getOutputStream(), 500, "Internal Server Error", jsonError("内部错误")) } catch (_: Throwable) {}
        } finally {
            closeQuietly(s)
        }
    }

    private fun extraHeaders(status: Int): Map<String, String> = when (status) {
        401 -> mapOf("WWW-Authenticate" to "Bearer")
        405 -> mapOf("Allow" to "POST")
        else -> emptyMap()
    }

    private fun route(req: Request, input: InputStream, output: OutputStream) {
        checkHost(req.headers["host"])
        if (req.headers.containsKey("origin")) throw HttpError(403, "Forbidden", "不接受浏览器请求")
        val path = req.path.substringBefore('?')
        if (path != MCP_PATH) throw HttpError(404, "Not Found", "只有 POST $MCP_PATH")
        if (req.method != "POST") {
            // GET 是 Streamable HTTP 的 SSE 流、DELETE 是结束会话；无状态服务端都不提供，规范要求回 405。
            throw HttpError(405, "Method Not Allowed", "只支持 POST（不提供 SSE 流和会话）")
        }
        checkAuth(req.headers["authorization"])
        val ct = req.headers["content-type"]?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        if (ct != "application/json") throw HttpError(415, "Unsupported Media Type", "Content-Type 必须是 application/json")
        if (req.headers["transfer-encoding"] != null) {
            throw HttpError(411, "Length Required", "不支持 Transfer-Encoding，请用 Content-Length")
        }
        val lenStr = req.headers["content-length"] ?: throw HttpError(411, "Length Required", "缺少 Content-Length")
        val len = lenStr.trim().toLongOrNull()?.takeIf { it >= 0 } ?: throw HttpError(400, "Bad Request", "Content-Length 无效")
        if (len > maxBodyBytes) throw HttpError(413, "Payload Too Large", "请求体超过 $maxBodyBytes 字节")
        val body = readExactly(input, len.toInt())
        val text = try {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(body)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw HttpError(400, "Bad Request", "请求体不是合法 UTF-8")
        }
        val remote = req.headers[REMOTE_CALLER_HEADER]?.take(200)?.filter { !it.isISOControl() }?.ifBlank { null }
        val response = handler(text, remote)
        if (response == null) {
            writeResponse(output, 202, "Accepted", null)
        } else {
            writeResponse(output, 200, "OK", response.toByteArray(Charsets.UTF_8))
        }
    }

    /** Host 必须是 127.0.0.1:<port> 或 localhost:<port>：防 DNS rebinding（恶意域名解析到 127.0.0.1）。 */
    private fun checkHost(host: String?) {
        val p = localPort
        val ok = host != null && host.lowercase(Locale.ROOT).let { it == "127.0.0.1:$p" || it == "localhost:$p" }
        if (!ok) throw HttpError(421, "Misdirected Request", "Host 必须是 127.0.0.1:$p")
    }

    /** 常量时间比较整个头值；缺失和错误统一 401，不透露是哪种。 */
    private fun checkAuth(value: String?) {
        val got = (value ?: "").trim().toByteArray(Charsets.UTF_8)
        if (!MessageDigest.isEqual(got, tokenBytes)) throw HttpError(401, "Unauthorized", "未授权")
    }

    // ---------------------------------------------------------------- 解析

    /** 读请求行和头部，总量不超过 [MAX_HEAD_BYTES]。头名转小写；重复的安全相关头直接 400。 */
    private fun readHead(input: InputStream): Request {
        val budget = intArrayOf(MAX_HEAD_BYTES)
        var line = readLine(input, budget) ?: throw IOException("连接已关闭")
        // 容忍请求前的空行（RFC 9112 建议）
        var skipped = 0
        while (line.isEmpty() && skipped++ < 4) line = readLine(input, budget) ?: throw IOException("连接已关闭")
        val parts = line.split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) throw HttpError(400, "Bad Request", "请求行无效")
        val method = parts[0]
        val path = parts[1]
        val headers = HashMap<String, String>()
        var count = 0
        while (true) {
            val h = readLine(input, budget) ?: throw IOException("连接已关闭")
            if (h.isEmpty()) break
            if (++count > MAX_HEADERS) throw HttpError(431, "Request Header Fields Too Large", "头部过多")
            if (h[0] == ' ' || h[0] == '\t') throw HttpError(400, "Bad Request", "不支持折叠头部")
            val colon = h.indexOf(':')
            if (colon <= 0) throw HttpError(400, "Bad Request", "头部格式无效")
            val name = h.substring(0, colon).lowercase(Locale.ROOT)
            if (name.any { it == ' ' || it == '\t' }) throw HttpError(400, "Bad Request", "头部名无效")
            val value = h.substring(colon + 1).trim()
            if (name in SINGLETON_HEADERS && headers.containsKey(name)) throw HttpError(400, "Bad Request", "重复的 $name 头")
            headers[name] = if (headers.containsKey(name)) headers[name] + ", " + value else value
        }
        return Request(method, path, headers)
    }

    /** 读一行（CRLF 或 LF 结尾），按 ISO-8859-1 解码（头部是字节语义）。超预算 → 431。EOF 且无内容 → null。 */
    private fun readLine(input: InputStream, budget: IntArray): String? {
        val sb = StringBuilder(64)
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else throw HttpError(400, "Bad Request", "头部不完整")
            if (--budget[0] < 0) throw HttpError(431, "Request Header Fields Too Large", "头部超过 ${MAX_HEAD_BYTES / 1024}KB")
            if (b == '\n'.code) {
                if (sb.isNotEmpty() && sb[sb.length - 1] == '\r') sb.setLength(sb.length - 1)
                return sb.toString()
            }
            if (b == 0) throw HttpError(400, "Bad Request", "头部含 NUL")
            sb.append(b.toChar())
        }
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) throw HttpError(400, "Bad Request", "请求体不完整")
            off += r
        }
        return buf
    }

    private fun writeResponse(out: OutputStream, status: Int, reason: String, body: ByteArray?, extra: Map<String, String> = emptyMap()) {
        val o = BufferedOutputStream(out, 8192)
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
        if (body != null) sb.append("Content-Type: application/json; charset=utf-8\r\n")
        sb.append("Content-Length: ").append(body?.size ?: 0).append("\r\n")
        sb.append("Cache-Control: no-store\r\n")
        sb.append("X-Content-Type-Options: nosniff\r\n")
        sb.append("Connection: close\r\n")
        for ((k, v) in extra) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        o.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        if (body != null) o.write(body)
        o.flush()
    }

    private fun jsonError(msg: String): ByteArray =
        online.timefiles.vantaloom.phone.core.json.Json.stringify(mapOf("error" to msg)).toByteArray(Charsets.UTF_8)

    private fun closeQuietly(s: Socket) {
        try { s.shutdownOutput() } catch (_: IOException) {} catch (_: UnsupportedOperationException) {}
        try { s.close() } catch (_: IOException) {}
    }

    companion object {
        const val MCP_PATH = "/mcp"
        const val MAX_HEAD_BYTES = 16 * 1024
        const val MAX_HEADERS = 100
        /** 跨机调用（sharing）时 runtime 带上的远端描述，只用于确认卡展示和审计。头名已小写。 */
        const val REMOTE_CALLER_HEADER = "x-vantaloom-remote-caller"
        private val SINGLETON_HEADERS = setOf("host", "authorization", "content-length", "content-type", "transfer-encoding", "origin")
    }
}
