package online.timefiles.vantaloom.phone.core.tools

import online.timefiles.vantaloom.phone.core.model.ErrorCode
import online.timefiles.vantaloom.phone.core.model.phoneError

/**
 * 一个 MCP 工具。schema 是固定的 JSON Schema 对象（用 Kotlin Map 表达，由 Json 序列化），
 * 任何改动都要动 ServerInfo.VERSION（协议 §12）。
 */
interface PhoneTool {
    val name: String
    val description: String
    val inputSchema: Map<String, Any?>
    val kind: ToolKind
    /** 协议 §4 的语义风险（文档/审计用）。 */
    val semanticRisk: SemanticRisk

    /** 执行。已知失败抛 PhoneException。 */
    fun call(args: ToolArgs, ctx: ToolContext): ToolResult
}

enum class ToolKind { OBSERVE, ACT, CONTROL }
enum class SemanticRisk(val wire: String) { SAFE("safe"), NORMAL("normal"), RISKY("risky") }

/** 给模型的结果。images 是已编码的 (mime, bytes)。 */
data class ToolResult(
    val text: String,
    val structured: Map<String, Any?> = emptyMap(),
    val images: List<ToolImage> = emptyList(),
)

data class ToolImage(val mimeType: String, val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * 参数访问器：类型不对、越界一律 INVALID_ARGS，工具代码里不再手写校验。
 * raw 来自 Json.parse（Map/List/String/Long/Double/Boolean/null）。
 */
class ToolArgs(val raw: Map<String, Any?>) {
    fun has(key: String): Boolean = raw[key] != null

    fun string(key: String): String = optString(key) ?: phoneError(ErrorCode.INVALID_ARGS, "缺少参数 $key")
    fun optString(key: String): String? = when (val v = raw[key]) {
        null -> null
        is String -> v
        else -> phoneError(ErrorCode.INVALID_ARGS, "参数 $key 应为字符串")
    }

    fun bool(key: String, default: Boolean = false): Boolean = when (val v = raw[key]) {
        null -> default
        is Boolean -> v
        else -> phoneError(ErrorCode.INVALID_ARGS, "参数 $key 应为布尔值")
    }

    fun optLong(key: String): Long? = when (val v = raw[key]) {
        null -> null
        is Long -> v
        is Int -> v.toLong()
        is Double -> if (v % 1.0 == 0.0) v.toLong() else phoneError(ErrorCode.INVALID_ARGS, "参数 $key 应为整数")
        else -> phoneError(ErrorCode.INVALID_ARGS, "参数 $key 应为整数")
    }

    fun long(key: String): Long = optLong(key) ?: phoneError(ErrorCode.INVALID_ARGS, "缺少参数 $key")

    fun intIn(key: String, range: IntRange, default: Int? = null): Int {
        val v = optLong(key) ?: default?.toLong() ?: phoneError(ErrorCode.INVALID_ARGS, "缺少参数 $key")
        if (v < range.first || v > range.last) phoneError(ErrorCode.INVALID_ARGS, "参数 $key 应在 ${range.first}..${range.last}")
        return v.toInt()
    }

    fun enumOf(key: String, allowed: Collection<String>, default: String? = null): String {
        val v = optString(key) ?: default ?: phoneError(ErrorCode.INVALID_ARGS, "缺少参数 $key")
        if (v !in allowed) phoneError(ErrorCode.INVALID_ARGS, "参数 $key 应为 ${allowed.joinToString("|")}")
        return v
    }

    /** [x, y] 形式的整数对。 */
    fun point(key: String): Pair<Int, Int> {
        val v = raw[key] as? List<*> ?: phoneError(ErrorCode.INVALID_ARGS, "参数 $key 应为 [x, y]")
        if (v.size != 2) phoneError(ErrorCode.INVALID_ARGS, "参数 $key 应为 [x, y]")
        fun num(a: Any?): Int = when (a) {
            is Long -> a.toInt(); is Int -> a; is Double -> a.toInt()
            else -> phoneError(ErrorCode.INVALID_ARGS, "参数 $key 应为 [x, y]")
        }
        return num(v[0]) to num(v[1])
    }

    /** 把参数压成一行摘要（审计用）；长文本只记长度。 */
    fun auditSummary(redactKeys: Set<String> = setOf("text", "set")): String =
        raw.entries.joinToString(" ") { (k, v) ->
            if (k in redactKeys && v is String) "$k=<${v.length}字>" else "$k=${v.toString().take(80)}"
        }
}
