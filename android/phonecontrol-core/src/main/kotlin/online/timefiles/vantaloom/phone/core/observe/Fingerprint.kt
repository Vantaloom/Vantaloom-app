package online.timefiles.vantaloom.phone.core.observe

import online.timefiles.vantaloom.phone.core.model.Rect

/**
 * 元素指纹（协议 §2）：`windowId|归一化 bounds|role|文本|checked`。
 *
 * 为什么归一化 bounds：同一个按钮在两次抓树之间常有 1–几像素的抖动（动画收尾、状态栏高度变化、
 * 输入法调整），用原始像素做指纹会把「同一个元素」判成「不见了」。按屏幕尺寸映射到 1/100 网格后，
 * 小抖动落在同一格里；真的换了位置（列表滚动）则指纹会变，交给 ScreenRegistry 的放宽匹配处理。
 *
 * 文本用打码后、未截断的 rawLabel，并压缩空白、截到 [MAX_TEXT]，避免超长文本让指纹过大。
 */
object Fingerprint {
    /** 归一化网格数。 */
    const val GRID = 100

    /** 指纹里文本部分的最大长度。 */
    const val MAX_TEXT = 80

    /** 计算指纹。screen 宽高 ≤ 0 时退化为原始像素（夹具里可能没填尺寸）。 */
    fun of(windowId: Int, bounds: Rect, role: Role, text: String, checked: Boolean?, screenWidth: Int, screenHeight: Int): String {
        val b = normalize(bounds, screenWidth, screenHeight)
        val t = normalizeText(text)
        val c = when (checked) { null -> "-"; true -> "1"; false -> "0" }
        return "$windowId|${b[0]},${b[1]},${b[2]},${b[3]}|${role.wire}|$t|$c"
    }

    /** bounds 归一化到 [GRID] 网格：返回 [l, t, r, b]。 */
    fun normalize(bounds: Rect, screenWidth: Int, screenHeight: Int): IntArray {
        if (screenWidth <= 0 || screenHeight <= 0) return intArrayOf(bounds.left, bounds.top, bounds.right, bounds.bottom)
        fun nx(v: Int) = Math.round(v.toDouble() * GRID / screenWidth).toInt()
        fun ny(v: Int) = Math.round(v.toDouble() * GRID / screenHeight).toInt()
        return intArrayOf(nx(bounds.left), ny(bounds.top), nx(bounds.right), ny(bounds.bottom))
    }

    /** 文本归一化：压缩空白、去掉指纹分隔符 '|'、截断。 */
    fun normalizeText(text: String): String {
        val sb = StringBuilder()
        var space = false
        for (ch in text.trim()) {
            if (ch.isWhitespace()) { space = true; continue }
            if (space && sb.isNotEmpty()) sb.append(' ')
            space = false
            sb.append(if (ch == '|') '¦' else ch)
            if (sb.length >= MAX_TEXT) break
        }
        return sb.toString()
    }

    /** 拆开指纹的各段（调试/放宽匹配用）。格式不对返回 null。 */
    fun parse(fp: String): Parts? {
        val p = fp.split('|')
        if (p.size != 5) return null
        val b = p[1].split(',').mapNotNull { it.toIntOrNull() }
        if (b.size != 4) return null
        return Parts(p[0].toIntOrNull() ?: return null, b, p[2], p[3], p[4])
    }

    /** 指纹的结构化形式。 */
    data class Parts(val windowId: Int, val bounds: List<Int>, val role: String, val text: String, val checked: String)
}
