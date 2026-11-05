package online.timefiles.vantaloom.phone.core.mcp

import online.timefiles.vantaloom.phone.core.json.Json
import online.timefiles.vantaloom.phone.core.json.JsonException

/**
 * JSON-RPC 2.0 的最小实现（MCP 用它做消息层）。
 *
 * - 请求：有 `id` 的是 request（必须回响应），没有 `id` 的是 notification（不回响应）。
 * - id 只允许 string / number / null（规范如此），原样回传。
 * - 批量：顶层是数组时逐条处理；全是通知 → 无响应体；空数组 → 单个 Invalid Request。
 *   MCP 2025-06-18 已去掉 batch，但旧版客户端可能发，按 JSON-RPC 处理最稳妥。
 */
object JsonRpc {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603

    /** 已解析的请求。[hasId]=false 是通知。 */
    data class Request(val id: Any?, val hasId: Boolean, val method: String, val params: Map<String, Any?>) {
        val isNotification: Boolean get() = !hasId
    }

    /** 带 JSON-RPC 错误码的异常；处理器抛它，由分发层编码成 error 响应。 */
    class RpcException(val code: Int, override val message: String, val data: Any? = null) : RuntimeException(message)

    /** 一条入站消息的解析结果：要么是请求，要么是已经确定要回的错误响应（Invalid Request）。 */
    sealed class Parsed {
        data class Ok(val request: Request) : Parsed()
        data class Bad(val response: Map<String, Any?>) : Parsed()
    }

    /** 把一个 JSON 值解释成请求。 */
    fun interpret(v: Any?): Parsed {
        val m = Json.asObject(v) ?: return Parsed.Bad(error(null, INVALID_REQUEST, "请求必须是 JSON 对象"))
        val hasId = m.containsKey("id")
        val id = m["id"]
        if (hasId && id != null && id !is String && id !is Number) {
            return Parsed.Bad(error(null, INVALID_REQUEST, "id 只能是字符串或数字"))
        }
        if (m["jsonrpc"] != "2.0") return Parsed.Bad(error(id, INVALID_REQUEST, "jsonrpc 必须是 \"2.0\""))
        val method = m["method"] as? String ?: run {
            // 客户端发来的 response（比如回应服务端请求）：我们从不发请求，当作无意义的通知忽略。
            if (m.containsKey("result") || m.containsKey("error")) {
                return Parsed.Ok(Request(id, hasId = false, method = "", params = emptyMap()))
            }
            return Parsed.Bad(error(id, INVALID_REQUEST, "缺少 method"))
        }
        val params = when (val p = m["params"]) {
            null -> emptyMap()
            is Map<*, *> -> Json.asObject(p)!!
            // 按位置传参是合法 JSON-RPC，但 MCP 的方法都用具名参数；给一个明确的参数错误。
            is List<*> -> return Parsed.Bad(error(id, INVALID_PARAMS, "params 必须是对象"))
            else -> return Parsed.Bad(error(id, INVALID_REQUEST, "params 必须是对象"))
        }
        return Parsed.Ok(Request(id, hasId, method, params))
    }

    /** 成功响应。 */
    fun result(id: Any?, result: Any?): Map<String, Any?> = linkedMapOf("jsonrpc" to "2.0", "id" to id, "result" to result)

    /** 错误响应。 */
    fun error(id: Any?, code: Int, message: String, data: Any? = null): Map<String, Any?> {
        val err = linkedMapOf<String, Any?>("code" to code.toLong(), "message" to message)
        if (data != null) err["data"] = data
        return linkedMapOf("jsonrpc" to "2.0", "id" to id, "error" to err)
    }

    /**
     * 分发一个 HTTP 请求体。返回响应体文本；null = 没有响应（全是通知），HTTP 层回 202。
     * [handle] 处理单个请求：返回 result，或抛 [RpcException]；其他异常 → -32603。
     */
    fun dispatch(body: String, handle: (Request) -> Any?): String? {
        val parsed: Any? = try {
            Json.parse(body)
        } catch (e: JsonException) {
            return Json.stringify(error(null, PARSE_ERROR, "JSON 解析失败：${e.message}"))
        }
        if (parsed is List<*>) {
            if (parsed.isEmpty()) return Json.stringify(error(null, INVALID_REQUEST, "批量请求不能为空"))
            val responses = parsed.mapNotNull { one(it, handle) }
            return if (responses.isEmpty()) null else Json.stringify(responses)
        }
        return one(parsed, handle)?.let { Json.stringify(it) }
    }

    private fun one(v: Any?, handle: (Request) -> Any?): Map<String, Any?>? {
        val req = when (val p = interpret(v)) {
            is Parsed.Bad -> return p.response
            is Parsed.Ok -> p.request
        }
        return try {
            val r = handle(req)
            if (req.isNotification) null else result(req.id, JsonValues.normalize(r))
        } catch (e: RpcException) {
            if (req.isNotification) null else error(req.id, e.code, e.message, JsonValues.normalize(e.data))
        } catch (e: Throwable) {
            if (req.isNotification) null else error(req.id, INTERNAL_ERROR, "内部错误（${e.javaClass.simpleName}）")
        }
    }
}
