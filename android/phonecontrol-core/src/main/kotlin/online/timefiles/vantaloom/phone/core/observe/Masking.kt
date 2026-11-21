package online.timefiles.vantaloom.phone.core.observe

/**
 * 观察文本里的敏感数字打码（协议 §3 / §10）。
 *
 * 为什么只打这两类：银行卡号与身份证号是「一旦进了模型上下文/日志就无法收回」的高价值标识，
 * 且都有校验位，可以低误报地识别。手机号、金额等普通数字不打，否则模型没法正常工作。
 *
 * 识别规则：
 *  - 连续的数字串，中间允许单个空格或横杠分组（`6222 0212 3456 7890 123`、`6222-0212-…`），
 *    身份证末位允许 X/x。
 *  - 数字串两侧不能紧邻其他数字/字母（避免从一长串订单号中间截出一段碰巧通过 Luhn 的子串）。
 *  - 卡号：去分隔后 13–19 位且通过 Luhn。
 *  - 身份证：18 位，前 17 位数字 + 校验位（GB 11643 加权模 11）正确。
 *  - 输出 `****` + 末 4 位。
 */
object Masking {

    /** 打码结果：[text] 是处理后的文本，[masked] 是否发生过替换。 */
    data class Result(val text: String, val masked: Boolean)

    /** 候选：数字开头，后面是数字或「单个分隔符 + 数字」，最后可带 X（身份证）。 */
    private val candidate = Regex("""(?<![0-9A-Za-z])\d(?:[ \-]?\d){12,18}(?:[ \-]?[Xx])?(?![0-9A-Za-z])""")

    /** 对文本打码。null/空串原样返回。 */
    fun mask(text: String?): Result {
        if (text.isNullOrEmpty()) return Result(text ?: "", false)
        // 快路径：少于 13 个数字不可能命中。
        var digits = 0
        for (c in text) if (c in '0'..'9') digits++
        if (digits < 13) return Result(text, false)
        var hit = false
        val out = candidate.replace(text) { m ->
            val compact = m.value.filter { it.isDigit() || it == 'X' || it == 'x' }
            if (isSensitiveNumber(compact)) {
                hit = true
                "****" + compact.takeLast(4).uppercase()
            } else m.value
        }
        return Result(out, hit)
    }

    /** 已去分隔的数字串是否是卡号或身份证号。 */
    fun isSensitiveNumber(compact: String): Boolean = isIdCard(compact) || isCardNumber(compact)

    /** 13–19 位且通过 Luhn。全相同数字（如 0000…）排除，它们常是占位符。 */
    fun isCardNumber(digits: String): Boolean {
        if (digits.length !in 13..19 || !digits.all { it in '0'..'9' }) return false
        if (digits.all { it == digits[0] }) return false
        return luhn(digits)
    }

    /** Luhn 校验。 */
    fun luhn(digits: String): Boolean {
        var sum = 0
        var dbl = false
        for (i in digits.indices.reversed()) {
            var d = digits[i] - '0'
            if (d !in 0..9) return false
            if (dbl) { d *= 2; if (d > 9) d -= 9 }
            sum += d
            dbl = !dbl
        }
        return sum % 10 == 0
    }

    private val idWeights = intArrayOf(7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2)
    private const val ID_CHECK = "10X98765432"

    /**
     * 18 位居民身份证号：前 17 位数字、校验位正确；再粗查出生月日是否合法，进一步降误报。
     */
    fun isIdCard(s: String): Boolean {
        if (s.length != 18) return false
        var sum = 0
        for (i in 0 until 17) {
            val c = s[i]
            if (c !in '0'..'9') return false
            sum += (c - '0') * idWeights[i]
        }
        if (ID_CHECK[sum % 11] != s[17].uppercaseChar()) return false
        val month = s.substring(10, 12).toInt()
        val day = s.substring(12, 14).toInt()
        return month in 1..12 && day in 1..31
    }
}
