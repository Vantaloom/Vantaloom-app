package online.timefiles.vantaloom.phone.core.json

/**
 * JSON 解析或序列化失败。[position] 是出错时在原文里的字符下标（解析时有意义，序列化时为 -1），
 * 便于定位 debug dump 或 MCP 请求体里到底哪儿写坏了。
 */
class JsonException(val reason: String, val position: Int = -1) :
    RuntimeException(if (position >= 0) "$reason（位置 $position）" else reason)

/**
 * 零依赖的最小 JSON 实现。
 *
 * 为什么不用 org.json：Android 自带的 org.json 和 JVM 上的同名库行为不一致（数字类型、null 处理），
 * 而 core 要在两边跑同一套逻辑并用 JSON 夹具回放，所以自己写一个行为完全确定的版本。
 *
 * 类型映射（解析结果）：
 *  - 对象 → [LinkedHashMap]<String, Any?>（保留键序，重复键后者覆盖前者）
 *  - 数组 → [List]
 *  - 整数 → [Long]（超出 Long 范围时退化为 [Double]）
 *  - 小数/指数 → [Double]
 *  - true/false → [Boolean]，null → null
 *
 * 解析是严格模式：不接受注释、尾逗号、单引号、前导零、裸控制字符、NaN/Infinity，
 * 出错抛 [JsonException] 并带位置。嵌套深度默认上限 [MAX_DEPTH]，防恶意请求把栈打爆。
 */
object Json {
    /** 解析时允许的最大嵌套层数（对象和数组各算一层）。 */
    const val MAX_DEPTH = 64

    /** 序列化时的嵌套上限：比解析宽松，只用于发现意外的环引用，避免 StackOverflowError。 */
    private const val MAX_WRITE_DEPTH = 512

    /** 解析一段 JSON 文本。见类注释的类型映射。 */
    fun parse(text: String, maxDepth: Int = MAX_DEPTH): Any? = Parser(text, maxDepth).parseDocument()

    /** 解析并要求顶层是对象；否则抛 [JsonException]。 */
    fun parseObject(text: String, maxDepth: Int = MAX_DEPTH): LinkedHashMap<String, Any?> {
        val v = parse(text, maxDepth)
        if (v !is LinkedHashMap<*, *>) throw JsonException("顶层不是 JSON 对象", 0)
        @Suppress("UNCHECKED_CAST")
        return v as LinkedHashMap<String, Any?>
    }

    /**
     * 序列化。支持 Map（键用 toString）、Iterable/Array/原生数组/Sequence、String/Char、
     * Number、Boolean、null、Enum（写 name）。非有限 Double/Float 写 null（JSON 无法表达 NaN）。
     * 其他类型退化为 toString() 的字符串，保证永不因为一个怪对象而整体失败。
     */
    fun stringify(value: Any?, pretty: Boolean = false): String {
        val sb = StringBuilder()
        Writer(sb, pretty).write(value, 0)
        return sb.toString()
    }

    /** 便捷构造保序对象：`Json.obj("a" to 1, "b" to listOf(2))`。 */
    fun obj(vararg pairs: Pair<String, Any?>): LinkedHashMap<String, Any?> {
        val m = LinkedHashMap<String, Any?>(maxOf(4, pairs.size * 2))
        for ((k, v) in pairs) m[k] = v
        return m
    }

    /** 便捷构造数组。 */
    fun arr(vararg items: Any?): List<Any?> = items.toList()

    /** 把一个字符串编码成带引号的 JSON 字符串字面量。 */
    fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        writeString(sb, s)
        return sb.toString()
    }

    /** 把任意解析结果当对象用；不是对象时返回 null。 */
    fun asObject(v: Any?): Map<String, Any?>? {
        if (v !is Map<*, *>) return null
        @Suppress("UNCHECKED_CAST")
        return v as Map<String, Any?>
    }

    /** 把任意解析结果当数组用；不是数组时返回 null。 */
    fun asList(v: Any?): List<Any?>? = v as? List<Any?>

    // ---------------------------------------------------------------- 序列化

    private fun hex4(sb: StringBuilder, c: Int) {
        sb.append("\\u")
        val h = "0123456789abcdef"
        sb.append(h[(c shr 12) and 0xF]).append(h[(c shr 8) and 0xF]).append(h[(c shr 4) and 0xF]).append(h[c and 0xF])
    }

    /**
     * 转义规则：标准短转义 + 其他 C0 控制字符 \u00XX + \u2028/\u2029（JS 里是行终止符，
     * 嵌进 <script> 或被当 JS 求值时会断行）+ 落单代理项（否则 UTF-8 编码会产出非法字节）。
     */
    internal fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c == '\b' -> sb.append("\\b")
                c == '\u000C' -> sb.append("\\f")
                c < ' ' -> hex4(sb, c.code)
                c == '\u2028' || c == '\u2029' -> hex4(sb, c.code)
                Character.isHighSurrogate(c) -> {
                    if (i + 1 < n && Character.isLowSurrogate(s[i + 1])) {
                        sb.append(c).append(s[i + 1]); i++
                    } else {
                        hex4(sb, c.code)
                    }
                }
                Character.isLowSurrogate(c) -> hex4(sb, c.code)
                else -> sb.append(c)
            }
            i++
        }
        sb.append('"')
    }

    private class Writer(private val sb: StringBuilder, private val pretty: Boolean) {
        private fun newline(depth: Int) {
            if (!pretty) return
            sb.append('\n')
            repeat(depth) { sb.append("  ") }
        }

        private fun writeDouble(d: Double) {
            if (d.isNaN() || d.isInfinite()) sb.append("null") else sb.append(d.toString())
        }

        fun write(v: Any?, depth: Int) {
            if (depth > MAX_WRITE_DEPTH) throw JsonException("序列化嵌套超过 $MAX_WRITE_DEPTH 层（可能存在环引用）")
            when (v) {
                null -> sb.append("null")
                is String -> writeString(sb, v)
                is Boolean -> sb.append(if (v) "true" else "false")
                is Double -> writeDouble(v)
                is Float -> writeDouble(v.toDouble())
                is Number -> sb.append(v.toString())
                is Char -> writeString(sb, v.toString())
                is Enum<*> -> writeString(sb, v.name)
                is Map<*, *> -> writeObject(v, depth)
                is Iterable<*> -> writeArray(v.iterator(), depth)
                is Array<*> -> writeArray(v.iterator(), depth)
                is IntArray -> writeArray(v.iterator(), depth)
                is LongArray -> writeArray(v.iterator(), depth)
                is DoubleArray -> writeArray(v.iterator(), depth)
                is FloatArray -> writeArray(v.iterator(), depth)
                is ShortArray -> writeArray(v.iterator(), depth)
                is ByteArray -> writeArray(v.iterator(), depth)
                is BooleanArray -> writeArray(v.iterator(), depth)
                is CharArray -> writeString(sb, String(v))
                is Sequence<*> -> writeArray(v.iterator(), depth)
                else -> writeString(sb, v.toString())
            }
        }

        private fun writeObject(m: Map<*, *>, depth: Int) {
            if (m.isEmpty()) { sb.append("{}"); return }
            sb.append('{')
            var first = true
            for ((k, value) in m) {
                if (!first) sb.append(',')
                first = false
                newline(depth + 1)
                writeString(sb, k.toString())
                sb.append(if (pretty) ": " else ":")
                write(value, depth + 1)
            }
            newline(depth)
            sb.append('}')
        }

        private fun writeArray(it: Iterator<*>, depth: Int) {
            if (!it.hasNext()) { sb.append("[]"); return }
            sb.append('[')
            var first = true
            while (it.hasNext()) {
                val value = it.next()
                if (!first) sb.append(',')
                first = false
                newline(depth + 1)
                write(value, depth + 1)
            }
            newline(depth)
            sb.append(']')
        }
    }

    // ---------------------------------------------------------------- 解析

    private class Parser(private val s: String, private val maxDepth: Int) {
        private var i = 0

        fun parseDocument(): Any? {
            skipWs()
            val v = readValue(0)
            skipWs()
            if (i != s.length) fail("JSON 值之后还有多余内容")
            return v
        }

        private fun fail(msg: String): Nothing = throw JsonException(msg, i)

        private fun skipWs() {
            while (i < s.length) {
                val c = s[i]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++ else break
            }
        }

        private fun describe(c: Char): String = if (c < ' ') "\\u%04x".format(c.code) else c.toString()

        private fun readValue(depth: Int): Any? {
            if (i >= s.length) fail("意外的结尾，期望一个值")
            val c = s[i]
            return when {
                c == '{' -> readObject(depth + 1)
                c == '[' -> readArray(depth + 1)
                c == '"' -> readString()
                c == 't' -> literal("true", true)
                c == 'f' -> literal("false", false)
                c == 'n' -> literal("null", null)
                c == '-' || c in '0'..'9' -> readNumber()
                else -> fail("非法字符 '${describe(c)}'")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, i)) fail("非法字面量，期望 $word")
            i += word.length
            return value
        }

        private fun readObject(depth: Int): Any? {
            if (depth > maxDepth) fail("嵌套超过 $maxDepth 层")
            i++ // {
            val m = LinkedHashMap<String, Any?>()
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                skipWs()
                if (i >= s.length || s[i] != '"') fail("对象的键必须是字符串")
                val key = readString()
                skipWs()
                if (i >= s.length || s[i] != ':') fail("对象的键后面缺少 ':'")
                i++
                skipWs()
                m[key] = readValue(depth)
                skipWs()
                if (i >= s.length) fail("对象没有闭合")
                when (s[i]) {
                    ',' -> { i++; skipWs(); if (i < s.length && s[i] == '}') fail("对象里不允许尾逗号") }
                    '}' -> { i++; return m }
                    else -> fail("对象成员之间缺少 ',' 或 '}'")
                }
            }
        }

        private fun readArray(depth: Int): Any? {
            if (depth > maxDepth) fail("嵌套超过 $maxDepth 层")
            i++ // [
            val list = ArrayList<Any?>()
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return list }
            while (true) {
                skipWs()
                list.add(readValue(depth))
                skipWs()
                if (i >= s.length) fail("数组没有闭合")
                when (s[i]) {
                    ',' -> { i++; skipWs(); if (i < s.length && s[i] == ']') fail("数组里不允许尾逗号") }
                    ']' -> { i++; return list }
                    else -> fail("数组元素之间缺少 ',' 或 ']'")
                }
            }
        }

        private fun readNumber(): Any {
            val start = i
            if (s[i] == '-') i++
            if (i >= s.length) fail("数字不完整")
            if (s[i] == '0') {
                i++
                if (i < s.length && s[i] in '0'..'9') fail("数字不允许前导零")
            } else if (s[i] in '1'..'9') {
                while (i < s.length && s[i] in '0'..'9') i++
            } else fail("数字格式错误")
            var integral = true
            if (i < s.length && s[i] == '.') {
                integral = false
                i++
                if (i >= s.length || s[i] !in '0'..'9') fail("小数点后缺少数字")
                while (i < s.length && s[i] in '0'..'9') i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                integral = false
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                if (i >= s.length || s[i] !in '0'..'9') fail("指数缺少数字")
                while (i < s.length && s[i] in '0'..'9') i++
            }
            val text = s.substring(start, i)
            if (integral) text.toLongOrNull()?.let { return it }
            return text.toDouble()
        }

        private fun readString(): String {
            i++ // 开引号
            val start = i
            // 快路径：没有转义时直接 substring。
            while (i < s.length) {
                val c = s[i]
                if (c == '"') { val r = s.substring(start, i); i++; return r }
                if (c == '\\' || c < ' ') break
                i++
            }
            val sb = StringBuilder(i - start + 16).append(s, start, i)
            while (true) {
                if (i >= s.length) fail("字符串没有闭合")
                val c = s[i]
                when {
                    c == '"' -> { i++; return sb.toString() }
                    c < ' ' -> fail("字符串里不允许裸控制字符 ${describe(c)}")
                    c == '\\' -> {
                        i++
                        if (i >= s.length) fail("转义不完整")
                        when (s[i]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 >= s.length) fail("\\u 转义不完整")
                                var code = 0
                                for (k in 1..4) {
                                    val d = Character.digit(s[i + k], 16)
                                    if (d < 0) fail("\\u 转义里有非十六进制字符")
                                    code = code * 16 + d
                                }
                                // 代理对由两个 \uXXXX 依次追加自然拼成；落单代理项原样保留（宽松，不丢数据）。
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> fail("未知转义 \\${describe(s[i])}")
                        }
                        i++
                    }
                    else -> { sb.append(c); i++ }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 读取辅助
// 夹具与策略文件是人写的，字段类型可能不严格（例如 1 写成 1.0），这些辅助函数宽松读取，类型不对时给默认值。

/** 读字符串字段；不是字符串时返回 null。 */
fun Map<String, Any?>.jStr(key: String): String? = this[key] as? String

/** 读整数字段（接受 Long/Int/整数值 Double）。 */
fun Map<String, Any?>.jLong(key: String, default: Long): Long = when (val v = this[key]) {
    is Long -> v
    is Int -> v.toLong()
    is Double -> if (v.isFinite()) v.toLong() else default
    is Number -> v.toLong()
    else -> default
}

/** 读 Int 字段。 */
fun Map<String, Any?>.jInt(key: String, default: Int): Int = jLong(key, default.toLong()).toInt()

/** 读 Double 字段。 */
fun Map<String, Any?>.jDouble(key: String, default: Double): Double = (this[key] as? Number)?.toDouble() ?: default

/** 读布尔字段。 */
fun Map<String, Any?>.jBool(key: String, default: Boolean): Boolean = this[key] as? Boolean ?: default

/** 读对象字段。 */
fun Map<String, Any?>.jObj(key: String): Map<String, Any?>? = Json.asObject(this[key])

/** 读数组字段。 */
fun Map<String, Any?>.jList(key: String): List<Any?>? = Json.asList(this[key])

/** 读字符串数组字段（跳过非字符串元素）；字段缺失返回 null，以便调用方区分「没写」和「写了空表」。 */
fun Map<String, Any?>.jStrList(key: String): List<String>? = jList(key)?.filterIsInstance<String>()

/** 读整数数组字段。 */
fun Map<String, Any?>.jIntList(key: String): List<Int>? = jList(key)?.mapNotNull { (it as? Number)?.toInt() }
