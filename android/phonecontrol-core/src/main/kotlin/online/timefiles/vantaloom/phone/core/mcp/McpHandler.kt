package online.timefiles.vantaloom.phone.core.mcp

import online.timefiles.vantaloom.phone.core.PhoneEngine
import online.timefiles.vantaloom.phone.core.ServerInfo
import online.timefiles.vantaloom.phone.core.tools.PhoneTool
import online.timefiles.vantaloom.phone.core.tools.SemanticRisk
import online.timefiles.vantaloom.phone.core.tools.ToolKind
import online.timefiles.vantaloom.phone.core.tools.impl.BaseTool

/**
 * MCP 方法层（协议 §1）：initialize、notifications/initialized、ping、tools/list、tools/call。
 *
 * 无状态：不发 Mcp-Session-Id，也不要求先握手——runtime 重启后会懒重连，直接发 tools/call。
 * 协议版本只影响 initialize 的协商结果；其余方法在三个版本间形状一致，
 * 只有 `structuredContent` 是 2025-06-18 才有的字段，老客户端会忽略未知字段，所以始终下发。
 */
class McpHandler(private val engine: PhoneEngine) {

    /** 处理一个 HTTP 请求体；[remoteCaller] 来自请求头，只用于展示。返回 null = 202 无体。 */
    fun handle(body: String, remoteCaller: String? = null): String? =
        JsonRpc.dispatch(body) { req -> handleRequest(req, remoteCaller) }

    /** 单个请求。通知的返回值会被丢弃。 */
    fun handleRequest(req: JsonRpc.Request, remoteCaller: String?): Any? = when (req.method) {
        "initialize" -> initialize(req.params)
        "notifications/initialized", "notifications/cancelled", "notifications/roots/list_changed", "" -> null
        "ping" -> emptyMap<String, Any?>()
        "tools/list" -> toolsList()
        "tools/call" -> toolsCall(req.params, remoteCaller)
        else -> {
            // 未知的通知静默忽略（规范：通知永不回错误）；未知的请求回 -32601。
            if (req.isNotification) null
            else throw JsonRpc.RpcException(JsonRpc.METHOD_NOT_FOUND, "不支持的方法 ${req.method}")
        }
    }

    private fun initialize(params: Map<String, Any?>): Map<String, Any?> {
        val asked = params["protocolVersion"] as? String
        val version = negotiate(asked)
        return linkedMapOf(
            "protocolVersion" to version,
            "capabilities" to linkedMapOf("tools" to linkedMapOf("listChanged" to false)),
            "serverInfo" to linkedMapOf("name" to ServerInfo.NAME, "version" to ServerInfo.VERSION),
            "instructions" to ServerInfo.INSTRUCTIONS,
        )
    }

    private fun toolsList(): Map<String, Any?> =
        linkedMapOf("tools" to engine.tools().map { describe(it) })

    private fun describe(t: PhoneTool): Map<String, Any?> {
        val readOnly = (t as? BaseTool)?.readOnly ?: (t.kind == ToolKind.OBSERVE)
        return linkedMapOf(
            "name" to t.name,
            "description" to t.description,
            "inputSchema" to t.inputSchema,
            "annotations" to linkedMapOf(
                "title" to t.name,
                "readOnlyHint" to readOnly,
                "destructiveHint" to (!readOnly && t.semanticRisk == SemanticRisk.RISKY),
                "idempotentHint" to readOnly,
                // 操作的是本机屏幕，不是开放世界的网络资源；但 open(url) 会把用户带去任意网页。
                "openWorldHint" to (t.name == "open" || t.name == "intent"),
            ),
        )
    }

    private fun toolsCall(params: Map<String, Any?>, remoteCaller: String?): Map<String, Any?> {
        val name = params["name"] as? String
            ?: throw JsonRpc.RpcException(JsonRpc.INVALID_PARAMS, "tools/call 缺少 name")
        if (engine.tools().none { it.name == name }) {
            throw JsonRpc.RpcException(JsonRpc.INVALID_PARAMS, "未知工具 $name")
        }
        val rawArgs = params["arguments"]
        val args: Map<String, Any?> = when (rawArgs) {
            null -> emptyMap()
            is Map<*, *> -> rawArgs.entries.associate { (k, v) -> k.toString() to v }
            else -> throw JsonRpc.RpcException(JsonRpc.INVALID_PARAMS, "arguments 必须是对象")
        }
        val r = engine.call(name, args, remoteCaller)
        return linkedMapOf(
            "content" to r.content,
            "structuredContent" to r.structured,
            "isError" to r.isError,
        )
    }

    companion object {
        /** 版本协商：认识就回同一个，不认识回默认版本（协议 §1）。 */
        fun negotiate(asked: String?): String =
            if (asked != null && asked in ServerInfo.PROTOCOL_VERSIONS) asked else ServerInfo.DEFAULT_PROTOCOL_VERSION
    }
}
