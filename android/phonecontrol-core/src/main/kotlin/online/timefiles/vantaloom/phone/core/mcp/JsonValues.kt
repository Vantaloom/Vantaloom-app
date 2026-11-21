package online.timefiles.vantaloom.phone.core.mcp

/**
 * 把任意 Kotlin 值归一成 Json.stringify 一定认得的类型（Map<String,…>/List/String/Long/Double/Boolean/null）。
 *
 * 为什么需要：工具和观察管线很自然地会塞 Int、Float、枚举、Set、IntArray 进 structured 里；
 * 契约只保证 Json 认那 7 种类型，所以在出门前统一归一，而不是让每个工具自己小心。
 * 归一是纯函数、可重复调用；识别不了的对象退化成 toString()，宁可丑也不抛异常。
 */
object JsonValues {
    /** 递归归一；嵌套深度上限 64，防止自引用结构打爆栈。 */
    fun normalize(value: Any?, depth: Int = 0): Any? {
        if (depth > 64) return value?.toString()
        return when (value) {
            null -> null
            is String, is Boolean, is Long, is Double -> value
            is Int -> value.toLong()
            is Short -> value.toLong()
            is Byte -> value.toLong()
            is Float -> value.toDouble()
            is Number -> value.toDouble()
            is Char -> value.toString()
            is Enum<*> -> value.name.lowercase()
            is Map<*, *> -> {
                val out = LinkedHashMap<String, Any?>(value.size)
                for ((k, v) in value) out[k.toString()] = normalize(v, depth + 1)
                out
            }
            is Iterable<*> -> value.map { normalize(it, depth + 1) }
            is Array<*> -> value.map { normalize(it, depth + 1) }
            is IntArray -> value.map { it.toLong() }
            is LongArray -> value.toList()
            is DoubleArray -> value.toList()
            is BooleanArray -> value.toList()
            is ByteArray -> Base64Codec.encode(value)
            is Pair<*, *> -> listOf(normalize(value.first, depth + 1), normalize(value.second, depth + 1))
            else -> value.toString()
        }
    }

    /** 归一一个 Map（常用形式，省一次强转）。 */
    @Suppress("UNCHECKED_CAST")
    fun normalizeMap(value: Map<String, Any?>): Map<String, Any?> = normalize(value) as Map<String, Any?>
}
