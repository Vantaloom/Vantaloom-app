package online.timefiles.vantaloom.phone.core.tools.impl

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * 最小 ISO8601 时间解析（calendar 意图用）。
 *
 * 为什么不用 java.time：core 会被 minSdk 24 的 APK 依赖，java.time 在 API 26 才有，
 * 需要 core library desugaring；为一个字段引入 desugaring 不划算。java.util.Calendar 所有 API 级别都有。
 *
 * 接受：`YYYY-MM-DD`、`YYYY-MM-DDTHH:MM`、`YYYY-MM-DDTHH:MM:SS(.fff)`，可选后缀 `Z` / `+08:00` / `+0800`；
 * 分隔符也接受空格。不带时区时按 [defaultZone]。
 */
object Iso8601 {
    private val PATTERN = Regex(
        "^(\\d{4})-(\\d{2})-(\\d{2})(?:[T ](\\d{2}):(\\d{2})(?::(\\d{2})(?:\\.(\\d{1,9}))?)?)?\\s*(Z|[+-]\\d{2}(?::?\\d{2})?)?$",
    )

    /** 解析成 epoch 毫秒；格式不对或字段越界返回 null。 */
    fun parseMillis(s: String, defaultZone: TimeZone = TimeZone.getDefault()): Long? {
        val m = PATTERN.matchEntire(s.trim()) ?: return null
        val g = m.groupValues
        val year = g[1].toInt(); val month = g[2].toInt(); val day = g[3].toInt()
        val hour = g[4].ifEmpty { "0" }.toInt(); val minute = g[5].ifEmpty { "0" }.toInt()
        val second = g[6].ifEmpty { "0" }.toInt()
        val millis = g[7].ifEmpty { "0" }.padEnd(3, '0').take(3).toInt()
        if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null
        val zone = when (val z = g[8]) {
            "" -> defaultZone
            "Z" -> TimeZone.getTimeZone("UTC")
            else -> {
                val digits = z.substring(1).replace(":", "")
                val hh = digits.take(2).toInt()
                val mm = if (digits.length >= 4) digits.substring(2, 4).toInt() else 0
                if (hh > 18 || mm > 59) return null
                TimeZone.getTimeZone("GMT" + z[0] + String.format(java.util.Locale.ROOT, "%02d:%02d", hh, mm))
            }
        }
        val cal = GregorianCalendar(zone)
        cal.isLenient = false
        cal.clear()
        cal.set(year, month - 1, day, hour, minute, second)
        cal.set(Calendar.MILLISECOND, millis)
        return try {
            cal.timeInMillis // 非宽松模式下 2 月 30 日这种会抛 IllegalArgumentException
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
