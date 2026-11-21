package online.timefiles.vantaloom.phone.core.tools.impl

import online.timefiles.vantaloom.phone.core.driver.DriverAvailability
import online.timefiles.vantaloom.phone.core.model.DisplayKind
import online.timefiles.vantaloom.phone.core.model.ErrorCode
import online.timefiles.vantaloom.phone.core.model.PhoneException
import online.timefiles.vantaloom.phone.core.model.Rect
import online.timefiles.vantaloom.phone.core.model.phoneError
import online.timefiles.vantaloom.phone.core.observe.Observation
import online.timefiles.vantaloom.phone.core.observe.ObservedElement
import online.timefiles.vantaloom.phone.core.observe.ScreenRegistry
import online.timefiles.vantaloom.phone.core.safety.ActionIntent
import online.timefiles.vantaloom.phone.core.safety.SafetyGate
import online.timefiles.vantaloom.phone.core.safety.TaskApprovals
import online.timefiles.vantaloom.phone.core.session.TaskInfo
import online.timefiles.vantaloom.phone.core.tools.Budgets
import online.timefiles.vantaloom.phone.core.tools.PhoneTool
import online.timefiles.vantaloom.phone.core.tools.SemanticRisk
import online.timefiles.vantaloom.phone.core.tools.ToolArgs
import online.timefiles.vantaloom.phone.core.tools.ToolContext
import online.timefiles.vantaloom.phone.core.tools.ToolKind
import online.timefiles.vantaloom.phone.core.tools.ToolResult

/**
 * 引擎提供给内置工具的扩展上下文。
 *
 * 为什么不直接扩充 [ToolContext]：那是冻结的契约文件。内置工具需要屏幕登记表、安全门和任务记忆，
 * 但对它们的访问要经过引擎的方法（[guard] 负责弹卡、记忆「本任务都允许」、计入审计），
 * 所以在 impl 包里定义这个子接口，由 [online.timefiles.vantaloom.phone.core.PhoneEngine] 的每次调用上下文实现。
 * 测试里可以自己实现它，把工具单独跑在 FakeScreenDriver 上。
 */
interface EngineContext : ToolContext {
    val registry: ScreenRegistry
    val gate: SafetyGate
    val approvals: TaskApprovals
    val ownPackage: String

    /** 指定 display 的能力；没有该驱动时为 null。 */
    fun availability(display: DisplayKind = DisplayKind.MAIN): DriverAvailability?

    /** 已接入驱动的 display。 */
    fun displays(): Set<DisplayKind>

    /** 任务快照（status 用）。 */
    fun taskInfo(): TaskInfo

    /** 结束当前任务（接手被放弃等）。 */
    fun endTask(reason: String)

    /**
     * 安全门 + 确认卡。Allow 直接返回；Deny 抛 DENIED_POLICY；Confirm 弹卡并阻塞等待（不计入动作预算），
     * 拒绝/超时/取消分别抛 USER_REJECTED / CONFIRM_TIMEOUT / STOPPED。
     * [forceConfirm]=true（坐标动作）时无论策略怎么判都弹卡，且不允许「本任务都允许」——纵深防御，
     * 不依赖策略表写对。
     * @return 是否弹过确认卡（弹过则界面可能已变，调用方应重新定位目标）
     */
    fun guard(intent: ActionIntent, summary: String, target: Rect?, forceConfirm: Boolean = false): Boolean

    /** 执行途中的检查点：任务被停止/暂停就抛错。 */
    fun checkpoint()

    /** 还剩多少预算（毫秒）。循环类工具（collect/wait）用它提前收尾。 */
    fun remainingMs(): Long

    /** 阻塞等用户（确认卡、接手）的时间不计入预算。 */
    fun <T> userWait(block: () -> T): T

    /** 抓树并登记为该 display 的最新屏。 */
    fun observe(display: DisplayKind = DisplayKind.MAIN, detail: Boolean = false): Observation =
        registry.observe(driver(display), budgets, gate, detail)

    /** 等界面稳定再观察（协议 §2：动作后自动返回新观察）。 */
    fun settleAndObserve(display: DisplayKind = DisplayKind.MAIN): Observation {
        driver(display).awaitSettle(budgets.settleQuietMs, budgets.settleMaxMs)
        return observe(display)
    }
}

/** 取引擎上下文；工具被嵌进别的宿主时给出明确的 INTERNAL，而不是 ClassCastException。 */
fun ToolContext.engine(): EngineContext =
    this as? EngineContext ?: throw PhoneException(ErrorCode.INTERNAL, "内置工具需要 PhoneEngine 上下文")

/**
 * 内置工具的公共基类：声明引擎要的执行特征（是否受任务门控、是否串行、预算），
 * 并按 schema 的 properties 拒绝未知参数（additionalProperties=false 的服务端执行）。
 */
abstract class BaseTool(
    final override val name: String,
    final override val kind: ToolKind,
    final override val semanticRisk: SemanticRisk,
) : PhoneTool {
    /** false = 不过任务门（status/apps），在调用线程直接执行。 */
    open val gated: Boolean get() = true

    /** 是否走动作串行锁。改变界面的工具都要串行（collect 会滚动，也算）。 */
    open val serialized: Boolean get() = kind != ToolKind.OBSERVE

    /**
     * 是否算一次「动作」：没有任务时自动开任务（浮层出现，用户才有停止入口），并计入动作数。
     * collect 虽然归类为观察，但会自动滚动屏幕，用户必须能看到并打断它，所以也算。
     */
    open val startsTask: Boolean get() = kind != ToolKind.OBSERVE

    /** 锁屏/熄屏时是否拒绝（notify 不需要屏幕）。 */
    open val needsScreen: Boolean get() = true

    /** 是否要求浮层在场（不变量：没有浮层就不许动作，用户无法停止）。 */
    open val needsOverlay: Boolean get() = kind != ToolKind.OBSERVE

    /** 预算（毫秒），不含等用户的时间。 */
    open fun budgetMs(b: Budgets, args: ToolArgs): Long = if (kind == ToolKind.OBSERVE) b.observeMs else b.actionMs

    /** 浮层上显示的一句话。不要包含 type 的文本等隐私。 */
    open fun activityLine(args: ToolArgs): String = name

    /** 只读提示（tools/list annotations）。 */
    open val readOnly: Boolean get() = kind == ToolKind.OBSERVE

    /** 拒绝 schema 里没有的参数：模型拼错参数名时立刻得到提示，而不是被悄悄忽略。 */
    fun validate(args: ToolArgs) {
        @Suppress("UNCHECKED_CAST")
        val props = (inputSchema["properties"] as? Map<String, Any?>)?.keys ?: emptySet()
        val unknown = args.raw.keys.filter { it !in props }
        if (unknown.isNotEmpty()) {
            phoneError(
                ErrorCode.INVALID_ARGS,
                "未知参数 ${unknown.joinToString(", ")}；可用参数：${if (props.isEmpty()) "（无）" else props.joinToString(", ")}",
            )
        }
    }
}

/** JSON Schema 片段构造器。schema 是协议的一部分（§12），改动要动 ServerInfo.VERSION。 */
object Schema {
    /** 对象 schema，additionalProperties=false。 */
    fun obj(vararg props: Pair<String, Map<String, Any?>>, required: List<String> = emptyList()): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>("type" to "object", "properties" to linkedMapOf(*props))
        if (required.isNotEmpty()) m["required"] = required
        m["additionalProperties"] = false
        return m
    }

    fun str(desc: String, maxLength: Int? = null, minLength: Int? = null): Map<String, Any?> =
        linkedMapOf<String, Any?>("type" to "string", "description" to desc).apply {
            if (minLength != null) put("minLength", minLength.toLong())
            if (maxLength != null) put("maxLength", maxLength.toLong())
        }

    fun bool(desc: String, default: Boolean? = null): Map<String, Any?> =
        linkedMapOf<String, Any?>("type" to "boolean", "description" to desc).apply {
            if (default != null) put("default", default)
        }

    fun int(desc: String, min: Long? = null, max: Long? = null, default: Long? = null): Map<String, Any?> =
        linkedMapOf<String, Any?>("type" to "integer", "description" to desc).apply {
            if (min != null) put("minimum", min)
            if (max != null) put("maximum", max)
            if (default != null) put("default", default)
        }

    fun enum(desc: String, values: List<String>, default: String? = null): Map<String, Any?> =
        linkedMapOf<String, Any?>("type" to "string", "enum" to values, "description" to desc).apply {
            if (default != null) put("default", default)
        }

    fun point(desc: String): Map<String, Any?> = linkedMapOf(
        "type" to "array",
        "description" to desc,
        "items" to linkedMapOf("type" to "integer", "minimum" to 0L),
        "minItems" to 2L,
        "maxItems" to 2L,
    )
}

// ---------------------------------------------------------------- 小工具函数

/** 给模型看的元素描述：`[6] 按钮「发送」`，与观察文本一致。 */
internal fun describe(e: ObservedElement): String = "[${e.index}] ${e.role.zh}「${e.label}」"

/** 屏幕号 → display（协议 §2：s 主屏，b 后台屏）。 */
internal fun displayOfScreen(screenId: String): DisplayKind =
    if (screenId.startsWith(DisplayKind.BACKGROUND.screenPrefix)) DisplayKind.BACKGROUND else DisplayKind.MAIN

/** ref → display。 */
internal fun displayOfRef(ref: String): DisplayKind = displayOfScreen(ref.substringBefore(':'))

/** 执行手势/节点动作期间让浮层让开（协议 §8）。finally 保证异常、中断时也恢复。 */
internal inline fun <T> EngineContext.standAside(block: () -> T): T {
    status.setStandAside(true)
    try {
        return block()
    } finally {
        status.setStandAside(false)
    }
}

/**
 * 动作后取新观察；失败（如锁屏后无法抓树）不让已经成功的动作变成失败，而是返回说明。
 * 但 STOPPED/PAUSED 要继续抛：那是用户意图，必须送到模型面前。
 */
internal fun EngineContext.settleAndObserveSoft(display: DisplayKind): Pair<Observation?, String?> = try {
    settleAndObserve(display) to null
} catch (e: PhoneException) {
    if (e.code == ErrorCode.STOPPED || e.code == ErrorCode.PAUSED) throw e
    null to "（未能获取新屏幕：${e.code.wire} ${e.message}）"
}

/**
 * 组装动作类成功结果：`headline` + 空行 + 新观察文本；structured 带 ok/action/delivery/observation。
 */
internal fun actionResult(
    action: String,
    headline: String,
    obs: Observation?,
    delivery: String? = null,
    note: String? = null,
    extra: Map<String, Any?> = emptyMap(),
): ToolResult {
    val sb = StringBuilder(headline)
    if (!note.isNullOrBlank()) sb.append('\n').append(note)
    if (obs != null) sb.append("\n\n").append(obs.text)
    val structured = linkedMapOf<String, Any?>("ok" to true, "action" to action)
    if (delivery != null) structured["delivery"] = delivery
    structured.putAll(extra)
    structured["observation"] = obs?.structured
    return ToolResult(sb.toString(), structured)
}

/** 矩形与屏幕的可见交集；完全在屏外时返回 null（手势点不到）。 */
internal fun visiblePart(r: Rect, screenW: Int, screenH: Int): Rect? {
    if (screenW <= 0 || screenH <= 0) return if (r.isEmpty) null else r
    val v = Rect(maxOf(r.left, 0), maxOf(r.top, 0), minOf(r.right, screenW), minOf(r.bottom, screenH))
    return if (v.isEmpty) null else v
}

/** 预览文本用的截断。 */
internal fun clip(s: String, max: Int): String = if (s.length <= max) s else s.take(max - 1) + "…"
