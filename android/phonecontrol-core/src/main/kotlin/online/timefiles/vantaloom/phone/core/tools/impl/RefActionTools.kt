package online.timefiles.vantaloom.phone.core.tools.impl

import online.timefiles.vantaloom.phone.core.model.DisplayKind
import online.timefiles.vantaloom.phone.core.model.ErrorCode
import online.timefiles.vantaloom.phone.core.model.NodeAction
import online.timefiles.vantaloom.phone.core.model.PhoneException
import online.timefiles.vantaloom.phone.core.model.RawNode
import online.timefiles.vantaloom.phone.core.model.RawSnapshot
import online.timefiles.vantaloom.phone.core.model.phoneError
import online.timefiles.vantaloom.phone.core.observe.ElementState
import online.timefiles.vantaloom.phone.core.observe.Observation
import online.timefiles.vantaloom.phone.core.observe.ObservedElement
import online.timefiles.vantaloom.phone.core.safety.ActionIntent
import online.timefiles.vantaloom.phone.core.tools.SemanticRisk
import online.timefiles.vantaloom.phone.core.tools.ToolArgs
import online.timefiles.vantaloom.phone.core.tools.ToolContext
import online.timefiles.vantaloom.phone.core.tools.ToolKind
import online.timefiles.vantaloom.phone.core.tools.ToolResult

/** 定位好、过了安全门的动作目标。 */
internal class RefTarget(val element: ObservedElement, val obs: Observation, val display: DisplayKind)

/**
 * ref 类动作的公共前序（协议 §2 / §6）：
 * resolve（屏幕号必须是最新）→ relocate（重新抓树按指纹找回）→ [precheck]（工具自己的可操作性检查）
 * → SafetyGate / 确认卡 → 若弹过卡则再 relocate 一次。
 *
 * 为什么确认后还要再定位：用户看卡片的几秒里界面可能滚动/跳转（来了新消息），
 * 旧的 captureId/nodeId 或坐标可能已经指向别的东西。第二次定位失败就是 STALE_SCREEN，模型会拿到新屏重来，
 * 这比点错对象安全得多。
 */
internal fun EngineContext.prepareRef(
    tool: String,
    ref: String,
    precheck: (ObservedElement) -> Unit = {},
    intent: (ObservedElement, Observation) -> ActionIntent,
    summary: (ObservedElement, Observation) -> String,
): RefTarget {
    val display = displayOfRef(ref)
    requireDisplay(display)
    val driver = driver(display)
    val resolved = registry.resolve(ref)
    var (el, obs) = registry.relocate(driver, resolved, budgets, gate)
    precheck(el)
    val confirmed = guard(intent(el, obs), summary(el, obs), el.bounds)
    if (confirmed) {
        val again = registry.relocate(driver, el, budgets, gate)
        el = again.first
        obs = again.second
        precheck(el)
    }
    checkpoint()
    return RefTarget(el, obs, display)
}

/** 默认的 ref 动作意图：目标包 = 元素所在窗口包，退回前台包。 */
internal fun refIntent(tool: String, el: ObservedElement, obs: Observation, submit: Boolean = false, text: String? = null) = ActionIntent(
    tool = tool,
    packageName = el.packageName ?: obs.packageName,
    element = el,
    screen = obs,
    coordinate = false,
    submit = submit,
    text = text,
)

/** 卡片上的应用名：「微信 里的」。 */
internal fun inApp(obs: Observation?): String =
    obs?.snapshot?.foregroundLabel?.takeIf { it.isNotBlank() }?.let { "$it 里的" } ?: ""

// =============================================================================================== tap

/**
 * 协议 §4 `tap`：语义点击 → 可点击祖先（最多 4 层）→ 手势点中心。
 *
 * 为什么语义优先：ACTION_CLICK 不受遮挡、键盘、浮层影响，也不依赖坐标准确；但很多自绘控件（游戏、Flutter、
 * 部分 WebView）把点击挂在父容器上，文本节点本身不可点，所以沿祖先找。最后的手势点击是兜底，
 * 落点取元素**可见部分**的中心（半截在屏外的元素中心可能在屏幕外）。
 */
class TapTool : BaseTool("tap", ToolKind.ACT, SemanticRisk.RISKY) {
    override val description =
        "点击屏幕元素（按 ref，如 s12:5）。long=true 为长按。执行后自动返回新屏幕。" +
            "ref 必须来自最新一次观察；带 ⚠ 的元素会在手机上弹确认卡等用户同意。"
    override val inputSchema = Schema.obj(
        "ref" to Schema.str("要点的元素 ref，格式 <屏幕号>:<序号>，如 s12:5。旧屏幕号会返回 STALE_SCREEN 并附新屏幕"),
        "long" to Schema.bool("长按（如弹出菜单、进入多选）。默认 false", false),
        required = listOf("ref"),
    )

    override fun activityLine(args: ToolArgs): String = if (args.raw["long"] == true) "长按" else "点击"

    override fun call(args: ToolArgs, ctx: ToolContext): ToolResult {
        val e = ctx.engine()
        val ref = args.string("ref")
        val long = args.bool("long")
        val verb = if (long) "长按" else "点击"
        val t = e.prepareRef(
            name, ref,
            precheck = { el ->
                if (ElementState.DISABLED in el.states) {
                    phoneError(ErrorCode.NOT_ACTIONABLE, "${describe(el)} 当前不可用（灰显），先完成它依赖的步骤（如填写必填项）")
                }
            },
            intent = { el, obs -> refIntent(name, el, obs) },
            summary = { el, obs -> "$verb ${inApp(obs)}${el.role.zh}「${clip(el.label, 30)}」" },
        )
        val driver = e.driver(t.display)
        val el = t.element
        val action = if (long) NodeAction.LONG_CLICK else NodeAction.CLICK
        val delivery = e.standAside {
            when {
                action in el.actions && driver.performNode(el.captureId, el.nodeId, action) -> "semantic"
                tapAncestor(e, t, action) -> "ancestor"
                tapGesture(e, t, long) -> "gesture"
                else -> null
            }
        } ?: throw PhoneException(
            ErrorCode.ACTION_FAILED,
            "${describe(el)} ${verb}失败：语义动作、可点击祖先和手势都未成功",
            mapOf("ref" to el.ref),
            observationText = t.obs.text,
        )
        val (obs, note) = e.settleAndObserveSoft(t.display)
        return actionResult(name, "已${verb} ${describe(el)}" + deliveryNote(delivery), obs, delivery, note)
    }

    /** 沿祖先最多 4 层找可点击（可长按）且可用的节点。祖先必须包含目标中心，防止点到整个列表或窗口。 */
    private fun tapAncestor(e: EngineContext, t: RefTarget, action: NodeAction): Boolean {
        val index = NodeIndex(t.obs.snapshot)
        val el = t.element
        val anc = index.findAncestor(el.nodeId, MAX_ANCESTOR_LEVELS) { n ->
            n.enabled && action in n.actions &&
                (if (action == NodeAction.LONG_CLICK) n.longClickable else n.clickable) &&
                n.bounds.contains(el.bounds.centerX, el.bounds.centerY)
        } ?: return false
        return e.driver(t.display).performNode(t.obs.snapshot.captureId, anc.id, action)
    }

    private fun tapGesture(e: EngineContext, t: RefTarget, long: Boolean): Boolean {
        val snap = t.obs.snapshot
        val area = visiblePart(t.element.bounds, snap.screenWidth, snap.screenHeight) ?: return false
        val ms = if (long) e.budgets.longPressMs else e.budgets.tapMs
        return e.driver(t.display).tap(area.centerX, area.centerY, ms)
    }

    private fun deliveryNote(d: String) = when (d) {
        "ancestor" -> "（通过可点击的上层容器）"
        "gesture" -> "（手势点击）"
        else -> ""
    }

    private companion object {
        const val MAX_ANCESTOR_LEVELS = 4
    }
}

// =============================================================================================== type

/**
 * 协议 §4 `type`：ACTION_SET_TEXT 替换内容，append 时先读后拼；submit 走 ACTION_IME_ENTER。
 *
 * 为什么不用「粘贴」或逐字输入：SET_TEXT 是唯一不依赖输入法、不污染剪贴板的方式。
 * 代价是个别 App 的输入框不接受（返回 false 或文本被重置），所以执行后要回读校验。
 *
 * 密码框：无障碍读不到原文（只有圆点），所以 append 无法实现、也无法校验；是否允许往密码框输入由 SafetyGate 决定（默认硬拒绝）。
 */
class TypeTool : BaseTool("type", ToolKind.ACT, SemanticRisk.RISKY) {
    override val description =
        "在输入框里填写文本（默认替换原内容）。append=true 追加到末尾；submit=true 填完按输入法的回车/搜索/发送键（会弹确认）。" +
            "发送消息通常要再 tap 发送按钮；密码框和验证码框会被拒绝，请用 handoff 让用户自己填。"
    override val inputSchema = Schema.obj(
        "ref" to Schema.str("输入框的 ref（角色为「输入框」的元素）"),
        "text" to Schema.str("要填入的文本。换行用 \\n；不要包含密码", maxLength = MAX_TEXT),
        "submit" to Schema.bool("填完后按输入法的回车键（搜索框常用）。需要 Android 11+，否则返回 UNSUPPORTED，请改为 tap 界面上的按钮", false),
        "append" to Schema.bool("追加到已有内容末尾而不是替换。默认 false（替换）", false),
        required = listOf("ref", "text"),
    )

    override fun activityLine(args: ToolArgs): String = "输入文字" + if (args.raw["submit"] == true) "并提交" else ""

    override fun call(args: ToolArgs, ctx: ToolContext): ToolResult {
        val e = ctx.engine()
        val ref = args.string("ref")
        val text = args.string("text")
        if (text.length > MAX_TEXT) phoneError(ErrorCode.INVALID_ARGS, "text 最长 $MAX_TEXT 字")
        val submit = args.bool("submit")
        val append = args.bool("append")
        val display = displayOfRef(ref)
        if (submit && e.availability(display)?.canImeEnter != true) {
            phoneError(ErrorCode.UNSUPPORTED, "本机不支持输入法回车动作（需要 Android 11+）；请不带 submit 填写，再 tap 界面上的搜索/发送按钮")
        }
        val t = e.prepareRef(
            name, ref,
            precheck = { el ->
                val editable = el.editable || NodeAction.SET_TEXT in el.actions
                if (!editable) phoneError(ErrorCode.NOT_ACTIONABLE, "${describe(el)} 不是可编辑的输入框；先 tap 它看是否会出现真正的输入框")
                if (NodeAction.SET_TEXT !in el.actions) phoneError(ErrorCode.NOT_ACTIONABLE, "${describe(el)} 不接受直接写入文本（没有 SET_TEXT 动作）")
                if (ElementState.DISABLED in el.states) phoneError(ErrorCode.NOT_ACTIONABLE, "${describe(el)} 当前不可编辑")
                if (append && ElementState.PASSWORD in el.states) phoneError(ErrorCode.INVALID_ARGS, "密码框读不到原文，不支持 append")
            },
            intent = { el, obs -> refIntent(name, el, obs, submit = submit, text = text) },
            summary = { el, obs ->
                "在 ${inApp(obs)}${el.role.zh}「${clip(el.label, 20)}」填写 ${text.length} 个字" +
                    (if (submit) "并提交" else "") + "：" + clip(text.replace('\n', ' '), 40)
            },
        )
        val driver = e.driver(t.display)
        val el = t.element
        val password = ElementState.PASSWORD in el.states
        val node = t.obs.node(el.nodeId)
        val original = node?.let { NodeIndex(t.obs.snapshot).realText(it) }.orEmpty()
        val newText = if (append) original + text else text

        e.standAside {
            if (NodeAction.FOCUS in el.actions && ElementState.FOCUSED !in el.states) {
                // 聚焦失败不致命：多数输入框不聚焦也接受 SET_TEXT。先聚焦是为了让 IME_ENTER 有目标。
                runCatching { driver.performNode(el.captureId, el.nodeId, NodeAction.FOCUS) }
            }
            if (!driver.performNode(el.captureId, el.nodeId, NodeAction.SET_TEXT, newText)) {
                throw PhoneException(ErrorCode.ACTION_FAILED, "${describe(el)} 拒绝写入文本", observationText = t.obs.text)
            }
        }

        var verifyNote: String? = null
        if (submit) {
            // 提交后输入框常被清空（发送消息、搜索跳转），所以要在提交前校验。
            driver.awaitSettle(e.budgets.settleQuietMs, e.budgets.settleMaxMs)
            if (!password) verifyNote = verify(driver.capture(e.budgets.captureBudgetMs), el, newText, t.obs)
            e.checkpoint()
            val ok = e.standAside { driver.performNode(el.captureId, el.nodeId, NodeAction.IME_ENTER) }
            if (!ok) {
                val (obs, _) = e.settleAndObserveSoft(t.display)
                throw PhoneException(
                    ErrorCode.ACTION_FAILED,
                    "文本已填入，但回车提交失败；请 tap 界面上的搜索/发送按钮",
                    mapOf("typed" to true, "submitted" to false),
                    observationText = obs?.text,
                )
            }
        }
        val (obs, note) = e.settleAndObserveSoft(t.display)
        if (!submit && !password && obs != null) verifyNote = verify(obs.snapshot, el, newText, obs)
        val head = "已在 ${describe(el)} " + (if (append) "追加" else "填入") + " ${text.length} 个字" + if (submit) "并提交" else ""
        return actionResult(
            name, head, obs, "semantic",
            note = listOfNotNull(verifyNote, note).joinToString("\n").ifEmpty { null },
            extra = mapOf("submitted" to submit, "verified" to (verifyNote == null && !password)),
        )
    }

    /**
     * 回读校验：在新快照里找「同一个」输入框（同窗口、可编辑、viewId 相同或 bounds 重叠），
     * 比较去掉空白后的文本（号码类输入框会自动插空格/连字符）。
     * 找不到输入框 → 无法校验，返回说明；找到但内容不符 → ACTION_FAILED。
     * 有 maxTextLength 时只校验前缀（系统会截断）。
     */
    private fun verify(snapshot: RawSnapshot, el: ObservedElement, expected: String, obsForError: Observation): String? {
        val candidates = ArrayList<RawNode>()
        val index = NodeIndex(snapshot)
        for (w in snapshot.windows) {
            if (w.windowId != el.windowId) continue
            val stack = ArrayDeque<RawNode>()
            w.root?.let { stack.addLast(it) }
            while (stack.isNotEmpty()) {
                val n = stack.removeLast()
                if (n.editable && ((el.viewId != null && n.viewId == el.viewId) || n.bounds.intersects(el.bounds))) candidates += n
                n.children.forEach { stack.addLast(it) }
            }
        }
        if (candidates.isEmpty()) return "（输入框已不在屏幕上，未能回读校验）"
        val want = normalize(expected).let { w ->
            val max = candidates.maxOf { it.maxTextLength }
            if (max > 0 && w.length > max) w.take(max) else w
        }
        val ok = candidates.any { n ->
            val got = normalize(index.realText(n))
            got.contains(want) || (want.isEmpty() && got.isEmpty())
        }
        if (ok) return null
        val got = candidates.firstOrNull()?.let { clip(index.realText(it), 40) } ?: ""
        throw PhoneException(
            ErrorCode.ACTION_FAILED,
            "写入后回读不符：输入框现在是「$got」（App 可能拒绝了直接写入或做了格式化）",
            mapOf("expectedLength" to expected.length.toLong()),
            observationText = obsForError.text,
        )
    }

    private fun normalize(s: String) = s.filterNot { it.isWhitespace() || it == '-' }

    private companion object {
        const val MAX_TEXT = 5_000
    }
}

// =============================================================================================== scroll

/**
 * 协议 §4 `scroll`：节点滚动优先，失败退到手势。不给 ref 就滚屏幕中部。
 * 仍过安全门：滚动本身无害，但「首次触达某包」和硬拒绝包（银行 App 里也不该乱滑）仍然要生效。
 */
class ScrollTool : BaseTool("scroll", ToolKind.ACT, SemanticRisk.RISKY) {
    override val description =
        "滚动列表或屏幕。direction 是想看的方向：down = 看下面更多内容（手指往上滑）。" +
            "给 ref 滚那个容器，不给就滚屏幕中部。要读完一个长列表用 collect 更省事。"
    override val inputSchema = Schema.obj(
        "ref" to Schema.str("可选：要滚动的容器 ref（「可上下滑」「可左右滑」的元素）。不给就滚动屏幕中部"),
        "direction" to Schema.enum("想看哪个方向的内容：down 看下面、up 看上面、right 看右边、left 看左边", listOf("up", "down", "left", "right")),
        "amount" to Schema.enum("滚动幅度：page 约一页（默认），half 约半页（不想错过内容时用）", listOf("page", "half"), "page"),
        required = listOf("direction"),
    )

    override fun activityLine(args: ToolArgs): String = "滚动"

    override fun call(args: ToolArgs, ctx: ToolContext): ToolResult {
        val e = ctx.engine()
        val direction = args.enumOf("direction", listOf("up", "down", "left", "right"))
        val half = args.enumOf("amount", listOf("page", "half"), "page") == "half"
        val ref = args.optString("ref")
        val dirZh = mapOf("up" to "向上", "down" to "向下", "left" to "向左", "right" to "向右")[direction]!!

        if (ref != null) {
            val t = e.prepareRef(
                name, ref,
                intent = { el, obs -> refIntent(name, el, obs) },
                summary = { el, obs -> "${dirZh}滚动 ${inApp(obs)}${el.role.zh}「${clip(el.label, 30)}」" },
            )
            val snap = t.obs.snapshot
            val driver = e.driver(t.display)
            val delivery = e.standAside { ScrollMechanics.scroll(driver, t.element, direction, half, snap.screenWidth, snap.screenHeight, e.budgets) }
                ?: throw PhoneException(ErrorCode.ACTION_FAILED, "${describe(t.element)} 滚动失败（可能已到尽头或在屏幕外）", observationText = t.obs.text)
            val (obs, note) = e.settleAndObserveSoft(t.display)
            return actionResult(name, "已${dirZh}滚动 ${describe(t.element)}", obs, delivery, note)
        }

        // 无 ref：先观察拿到前台包和屏幕尺寸，给安全门判定。
        val before = e.observe(DisplayKind.MAIN)
        e.guard(
            ActionIntent(tool = name, packageName = before.packageName, element = null, screen = before, coordinate = false),
            "${dirZh}滚动 ${before.snapshot.foregroundLabel ?: "当前界面"}",
            null,
        )
        e.checkpoint()
        val snap = before.snapshot
        val driver = e.driver(DisplayKind.MAIN)
        val delivery = e.standAside { ScrollMechanics.scroll(driver, null, direction, half, snap.screenWidth, snap.screenHeight, e.budgets) }
            ?: throw PhoneException(ErrorCode.ACTION_FAILED, "滚动手势被系统拒绝", observationText = before.text)
        val (obs, note) = e.settleAndObserveSoft(DisplayKind.MAIN)
        return actionResult(name, "已${dirZh}滚动屏幕", obs, delivery, note)
    }
}
