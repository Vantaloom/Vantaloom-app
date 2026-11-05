package online.timefiles.vantaloom.phone.core.safety

/**
 * 敏感词匹配（协议 §6 第 3 条，以及第 2 条的「秘密字段」关键词）。
 *
 * 匹配规则：大小写不敏感、忽略全部空白（「确 认」「Con firm」也算），并把全角 ASCII 折成半角；
 * 对元素的 label 和 viewId 末段都匹配。viewId 末段额外把 `_`/`-` 去掉，
 * 这样 `btn_send`、`pay-now` 能命中 `send`、`paynow`。
 *
 * 英文短词误报控制：英文词长度 ≤ 4 时要求词边界（`pay` 不命中 `display`，`send` 不命中 `sender` 也无妨，
 * 但仍按边界算以保持一致），中文词按子串匹配。
 */
object SensitiveWords {

    /** 默认敏感词（中英双语），命中 → 确认。 */
    val DEFAULT: List<String> = listOf(
        // 资金
        "支付", "付款", "转账", "收款", "充值", "提现", "购买", "下单", "提交订单", "立即购买", "确认支付", "确认付款",
        "打赏", "红包", "还款", "借款", "贷款", "订阅", "续费", "开通会员", "免密支付",
        // 破坏性
        "删除", "卸载", "清空", "清除数据", "格式化", "恢复出厂", "重置", "移除", "撤回", "解绑", "注销", "销户",
        // 对外发送/授权
        "发送", "发布", "提交", "确认", "同意", "授权", "允许", "绑定", "分享", "转发", "拨打", "呼叫", "举报", "关注",
        // 账号
        "退出登录", "登出", "切换账号", "修改密码", "设置密码",
        // English
        "pay", "payment", "pay now", "checkout", "purchase", "buy", "buy now", "order", "place order", "transfer",
        "send money", "top up", "withdraw", "subscribe", "donate",
        "delete", "uninstall", "remove", "erase", "wipe", "format", "factory reset", "reset", "clear data", "unbind",
        "send", "post", "publish", "submit", "confirm", "agree", "accept", "authorize", "allow", "grant", "share",
        "forward", "call", "dial", "report",
        "log out", "logout", "sign out", "signout", "deactivate", "close account", "change password",
    )

    /** 默认秘密字段关键词：输入目标的 viewId/提示/标签命中 → 硬拒绝（协议 §6 第 2 条）。 */
    val SECRET_FIELDS: List<String> = listOf(
        "验证码", "校验码", "动态码", "短信码", "支付密码", "交易密码", "取款密码", "密码", "口令", "安全码", "CVV", "CVV2", "CVC",
        "卡验证码", "有效期", "PIN", "PIN码", "OTP", "一次性密码", "身份证号", "银行卡号", "卡号",
        "password", "passcode", "passwd", "pwd", "pin", "otp", "verification code", "security code", "cvv", "cvc",
        "card number", "cardnumber", "expiry", "smscode", "sms_code", "authcode", "auth_code", "captcha", "2fa", "totp",
    )

    /** 归一化：小写、去空白、全角转半角。 */
    fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch0 in s) {
            var ch = ch0
            if (ch in '\uFF01'..'\uFF5E') ch = (ch.code - 0xFEE0).toChar()
            if (ch == '\u3000' || ch.isWhitespace()) continue
            sb.append(ch.lowercaseChar())
        }
        return sb.toString()
    }

    /** viewId 末段的归一化：额外去掉 `_` 和 `-`。 */
    fun normalizeViewId(viewIdTail: String): String = normalize(viewIdTail).replace("_", "").replace("-", "")

    /**
     * 在 [label] 和 [viewIdTail] 里找第一个命中的词；没有命中返回 null。
     * 返回的是词表里的原词（给用户看「含敏感词「发送」」）。
     */
    fun match(words: Collection<String>, label: String?, viewIdTail: String?): String? =
        matchAll(words, label, viewIdTail, limit = 1).firstOrNull()

    /** 所有命中的词（去重，最多 [limit] 个）。 */
    fun matchAll(words: Collection<String>, label: String?, viewIdTail: String?, limit: Int = Int.MAX_VALUE): List<String> {
        val l = label?.let { normalize(it) }.orEmpty()
        val rawLabel = label?.lowercase().orEmpty()
        val v = viewIdTail?.let { normalizeViewId(it) }.orEmpty()
        val rawView = viewIdTail?.lowercase().orEmpty()
        if (l.isEmpty() && v.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        for (w in words) {
            if (out.size >= limit) break
            val nw = normalize(w)
            if (nw.isEmpty()) continue
            val hit = if (isShortAscii(nw)) {
                // 短英文词在原文（保留空白和分隔符）上按词边界匹配，避免「pay」命中「display」。
                wordBoundaryHit(rawLabel, nw) || wordBoundaryHit(rawView, nw) || v == nw
            } else {
                l.contains(nw) || v.contains(nw.replace("_", "").replace("-", ""))
            }
            if (hit && w !in out) out.add(w)
        }
        return out
    }

    private fun isShortAscii(w: String): Boolean = w.length <= 4 && w.all { it.code < 128 }

    /** 词边界匹配：前后不能是 ASCII 字母或数字（中文、下划线、空格都算边界）。 */
    private fun wordBoundaryHit(text: String, w: String): Boolean {
        if (text.isEmpty()) return false
        var from = 0
        while (true) {
            val i = text.indexOf(w, from)
            if (i < 0) return false
            val before = if (i == 0) ' ' else text[i - 1]
            val afterIdx = i + w.length
            val after = if (afterIdx >= text.length) ' ' else text[afterIdx]
            if (!before.isAsciiAlnum() && !after.isAsciiAlnum()) return true
            from = i + 1
        }
    }

    private fun Char.isAsciiAlnum(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
}
