package online.timefiles.vantaloom.phone.core.safety

import online.timefiles.vantaloom.phone.core.observe.ElementState
import online.timefiles.vantaloom.phone.core.observe.Observation
import online.timefiles.vantaloom.phone.core.observe.ObservedElement
import online.timefiles.vantaloom.phone.core.observe.Role

/** 一次待判定的动作（字段语义见 CONTRACTS.md）。 */
data class ActionIntent(
    /** "tap"… */
    val tool: String,
    /** 目标包（元素所在窗口包或前台包）。 */
    val packageName: String?,
    /** ref 类动作的目标。 */
    val element: ObservedElement?,
    /** 当前屏（受保护页面判定需要标题/文本）。 */
    val screen: Observation?,
    /** tap_xy / swipe */
    val coordinate: Boolean,
    /** type(submit=true) */
    val submit: Boolean = false,
    /** type 的文本（检测是否像在输入支付密码）。 */
    val text: String? = null,
    /** open(url) */
    val uri: String? = null,
    /** intent kind */
    val intentKind: String? = null,
)

/** 判定结果。 */
sealed class GateDecision {
    /** 放行（仍记审计）。 */
    object Allow : GateDecision()

    /** 需要用户确认。[allowTaskWide]=false 时确认卡不提供「本任务内都允许」。 */
    data class Confirm(val reason: String, val rule: String, val allowTaskWide: Boolean) : GateDecision()

    /** 硬拒绝（DENIED_POLICY）。 */
    data class Deny(val reason: String, val rule: String) : GateDecision()
}

/** 本任务内「都允许」的记忆（按 包名 / 规则 维度），TaskSession 持有并在任务结束时清空。 */
interface TaskApprovals {
    /** 用户对该包选过「本任务内都允许」。 */
    fun packageApproved(pkg: String): Boolean

    /** 用户对该规则（可按包细分）选过「本任务内都允许」。 */
    fun ruleApproved(rule: String, pkg: String?): Boolean

    /** 本任务已经对该包执行过（或确认过）动作。 */
    fun firstTouchDone(pkg: String): Boolean

    companion object {
        /** 什么都没批过（测试 / annotate 用）。 */
        val NONE: TaskApprovals = object : TaskApprovals {
            override fun packageApproved(pkg: String) = false
            override fun ruleApproved(rule: String, pkg: String?) = false
            override fun firstTouchDone(pkg: String) = false
        }
    }
}

/**
 * 设备侧权威安全门（协议 §6 的第 2–4 步；第 1 步「会话门」在 PhoneEngine/TaskSession）。
 *
 * 纯函数：不弹卡、不记忆、不读时间。弹卡和「本任务内都允许」的记忆在 PhoneEngine/TaskSession，
 * 这样同一个 Gate 可以给 annotate（标 ⚠）和 evaluate（真判定）共用，且可完全用夹具单测。
 *
 * 判定顺序：硬拒绝 → 确认 → 放行，第一条命中即生效。
 *
 * 确认模式（按包）：
 *  - always：跳过所有确认规则（硬拒绝照旧）。
 *  - task：如果该包或该规则在本任务内已被「都允许」，跳过确认；
 *          但 allowTaskWide=false 的规则（坐标动作）仍然每次问。
 *  - ask：每次问（即使选过「本任务内都允许」也不记忆）。
 *
 * @param policy 当前策略。用户改设置后由 PhoneEngine 换一个新 Gate（Gate 本身不可变，线程安全）。
 * @param ownPackage 本 App 包名（`own_app` 规则）。
 */
class SafetyGate(val policy: SafetyPolicy, val ownPackage: String) {

    /** 给观察里的元素打「⚠」（needsConfirm）。纯函数，不弹卡。 */
    fun annotate(obs: Observation) {
        for (e in obs.elements) {
            val intent = ActionIntent(
                tool = if (e.role == Role.INPUT || e.editable) "type" else "tap",
                packageName = e.packageName ?: obs.packageName,
                element = e,
                screen = obs,
                coordinate = false,
            )
            // 提前提示只看「元素本身」的规则：首次接触某包、每步确认包会让整屏都是 ⚠，没有信息量。
            e.needsConfirm = elementWarning(intent) != null
        }
    }

    /** 判定一次动作。不弹卡；弹卡与「本任务内都允许」的记忆在 PhoneEngine/TaskSession。 */
    fun evaluate(action: ActionIntent, taskMemory: TaskApprovals): GateDecision {
        hardDeny(action)?.let { return it }
        return confirmOrAllow(action, taskMemory)
    }

    /**
     * 只跑硬拒绝部分（PhoneEngine 可以在动作前用最新观察再判一次，防「确认后界面变了」）。
     * 返回 null 表示没有硬拒绝。
     */
    fun hardDeny(action: ActionIntent): GateDecision.Deny? {
        val pkg = action.packageName
        // own_app 不可解除，也不看用户设置。
        if (pkg != null && pkg == ownPackage) return GateDecision.Deny("不能操作 Vantaloom 自身的界面", Rules.OWN_APP)
        if (action.element?.packageName == ownPackage && ownPackage.isNotEmpty()) {
            return GateDecision.Deny("不能操作 Vantaloom 自身的界面", Rules.OWN_APP)
        }

        // 打开应用：目标包本身就要过名单（open(app=支付宝) 不应被放行）。
        if (pkg != null) {
            if (on(Rules.DENY_PACKAGE) && policy.isDeniedPackage(pkg)) {
                val cat = PackageLists.categoryOf(pkg) ?: "受限"
                return GateDecision.Deny("$pkg 属于${cat}类应用，默认禁止操作（可在设置里解除）", Rules.DENY_PACKAGE)
            }
            if (on(Rules.SECURITY_PACKAGE) && pkg in policy.securityPackages) {
                return GateDecision.Deny("$pkg 是安全中心/权限/安装确认类界面，禁止操作", Rules.SECURITY_PACKAGE)
            }
        }

        // 受保护设置页：只能看不能动。open/intent(settings) 打开设置本身不算「动」——但打开后的点击会被拦。
        val screen = action.screen
        if (screen != null && on(Rules.PROTECTED_SETTINGS_PAGE) && action.tool !in OPENERS) {
            val screenPkg = screen.packageName
            if (pkg == null || pkg == screenPkg || pkg in policy.protectedSettingsPackages) {
                ProtectedPages.match(screen, policy.protectedSettingsPackages, policy.protectedPageKeywords)?.let {
                    return GateDecision.Deny("当前是系统设置的「$it」页面，只能查看不能操作", Rules.PROTECTED_SETTINGS_PAGE)
                }
            }
        }

        // 秘密字段：只对写文本类动作生效（点一下密码框聚焦无害，填内容才危险）。
        if (on(Rules.SECRET_FIELD) && action.tool in TEXT_WRITERS) {
            secretFieldHit(action)?.let { return GateDecision.Deny(it, Rules.SECRET_FIELD) }
        }
        return null
    }

    private fun confirmOrAllow(action: ActionIntent, mem: TaskApprovals): GateDecision {
        val pkg = action.packageName
        val mode = policy.modeFor(pkg)
        if (mode == ConfirmMode.ALWAYS) return GateDecision.Allow

        val candidates = ArrayList<GateDecision.Confirm>()

        // 坐标动作：没有语义可审，每次都确认，不能「本任务内都允许」。
        if (action.coordinate && on(Rules.COORDINATE_ACTION)) {
            candidates.add(GateDecision.Confirm("坐标${if (action.tool == "swipe") "滑动" else "点击"}无法确认目标是什么", Rules.COORDINATE_ACTION, allowTaskWide = false))
        }
        elementWarning(action)?.let { candidates.add(it) }
        if (action.submit && on(Rules.SUBMIT_TEXT)) {
            candidates.add(GateDecision.Confirm("输入后会直接提交", Rules.SUBMIT_TEXT, allowTaskWide = true))
        }
        if (!action.uri.isNullOrBlank() && on(Rules.DEEP_LINK)) {
            candidates.add(GateDecision.Confirm("打开链接 ${shortUri(action.uri)}", Rules.DEEP_LINK, allowTaskWide = true))
        }
        if (action.intentKind != null && action.intentKind in OUTBOUND_KINDS && on(Rules.OUTBOUND_INTENT)) {
            candidates.add(GateDecision.Confirm("会打开${kindZh(action.intentKind)}草稿", Rules.OUTBOUND_INTENT, allowTaskWide = true))
        }
        if (pkg != null && pkg in policy.confirmEveryWritePackages && on(Rules.CONFIRM_EVERY_WRITE)) {
            candidates.add(GateDecision.Confirm("$pkg 被设为每步确认", Rules.CONFIRM_EVERY_WRITE, allowTaskWide = false))
        }
        if (pkg != null && on(Rules.FIRST_TOUCH_PACKAGE) && !mem.firstTouchDone(pkg)) {
            candidates.add(GateDecision.Confirm("本任务第一次操作 $pkg", Rules.FIRST_TOUCH_PACKAGE, allowTaskWide = true))
        }

        for (c in candidates) {
            if (!c.allowTaskWide) return c
            if (mode == ConfirmMode.TASK && (mem.ruleApproved(c.rule, pkg) || (pkg != null && mem.packageApproved(pkg)))) continue
            return c
        }
        return GateDecision.Allow
    }

    /** 元素级的确认原因（敏感词）。annotate 和 evaluate 共用。 */
    private fun elementWarning(action: ActionIntent): GateDecision.Confirm? {
        val e = action.element ?: return null
        if (!on(Rules.SENSITIVE_WORD)) return null
        val word = SensitiveWords.match(policy.sensitiveWords, e.rawLabel, e.viewIdTail) ?: return null
        return GateDecision.Confirm("目标含敏感词「$word」", Rules.SENSITIVE_WORD, allowTaskWide = true)
    }

    /** 秘密字段命中原因；没命中返回 null。 */
    private fun secretFieldHit(action: ActionIntent): String? {
        val e = action.element ?: return null
        if (ElementState.PASSWORD in e.states) return "目标是密码框，禁止由 agent 填写"
        val hint = action.screen?.node(e.nodeId)?.let { listOfNotNull(it.hintText, it.contentDescription, it.tooltip).joinToString(" ") }
        val word = SensitiveWords.match(policy.secretFieldKeywords, e.rawLabel, e.viewIdTail)
            ?: hint?.let { SensitiveWords.match(policy.secretFieldKeywords, it, null) }
        if (word != null) return "输入框看起来是「$word」，禁止由 agent 填写"
        // 文本本身像 6 位纯数字且输入框标签含「码」：大概率是验证码/支付密码。
        val t = action.text
        if (t != null && t.length in 4..8 && t.all { it.isDigit() } && (e.rawLabel.contains("码") || hint?.contains("码") == true)) {
            return "看起来在输入验证码或支付密码，禁止由 agent 填写"
        }
        return null
    }

    private fun on(rule: String): Boolean = !policy.ruleDisabled(rule)

    private fun shortUri(u: String): String = if (u.length <= 60) u else u.take(57) + "…"

    private fun kindZh(kind: String): String = when (kind) {
        "dial" -> "拨号"
        "sms_draft" -> "短信"
        "email_draft" -> "邮件"
        "share_text" -> "分享"
        else -> kind
    }

    companion object {
        /**
         * 会往**元素**里写文本的工具（秘密字段规则）。取值就是工具名本身（ActionIntent.tool = 工具名）。
         * clipboard(set) 只写系统剪贴板、没有目标元素，不在此列；将来若加「粘贴到元素」，
         * 用 tool="type" 之外的工具名时要加进来。
         */
        val TEXT_WRITERS: Set<String> = setOf("type")

        /** 打开类工具：受保护页面规则不拦它们本身。 */
        val OPENERS: Set<String> = setOf("open", "intent")

        /** 需要确认的 intent kind（协议 §6）。 */
        val OUTBOUND_KINDS: Set<String> = setOf("dial", "sms_draft", "email_draft", "share_text")
    }
}
