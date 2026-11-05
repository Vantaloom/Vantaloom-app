package online.timefiles.vantaloom.phone.core.observe

import online.timefiles.vantaloom.phone.core.driver.ScreenDriver
import online.timefiles.vantaloom.phone.core.model.DisplayKind
import online.timefiles.vantaloom.phone.core.model.ErrorCode
import online.timefiles.vantaloom.phone.core.model.PhoneException
import online.timefiles.vantaloom.phone.core.model.RawSnapshot
import online.timefiles.vantaloom.phone.core.ports.Clock
import online.timefiles.vantaloom.phone.core.safety.SafetyGate
import online.timefiles.vantaloom.phone.core.tools.Budgets
import java.util.concurrent.atomic.AtomicLong

/**
 * 屏幕号分配、最新屏登记、ref 解析与按指纹重定位（协议 §2）。
 *
 * 为什么由一个中心对象管：ref 的有效性（「只有最近一次观察的 ref 能用」）是跨工具的全局不变量，
 * 观察类工具、动作类工具、自动新观察都必须经过同一个计数器和同一张「最新屏」表，
 * 否则并发的 screen 和 tap 会让模型拿着一个看似有效、实际已被替换的 ref 去点。
 *
 * 线程安全：抓树（慢、可能 2.5 s）在锁外做；分配屏幕号与登记在同一把锁里原子完成，
 * 所以屏幕号的顺序与「谁成为最新屏」的顺序一致。计数器进程内单调，跨 display 共用。
 *
 * @param clock 时间源（记录观察时间，便于测试和审计）。
 * @param options 观察构建策略；ownPackage 为空时退回 [SafetyGate.ownPackage]。
 */
class ScreenRegistry(
    private val clock: Clock,
    private val options: BuildOptions = BuildOptions(),
) {
    /** 每个 display 保留的历史观察数（最新 + 上一屏，「+」标记要用上一屏）。 */
    private val keepPerDisplay = 2

    private val counter = AtomicLong(0)
    private val lock = Any()

    /** display → 最近的观察，最新的在末尾。 */
    private val history = HashMap<DisplayKind, ArrayDeque<Entry>>()

    /** screenId → 截图几何。只保留仍在 history 里的屏幕。 */
    private val geometries = HashMap<String, ImageGeometry>()

    private class Entry(val observation: Observation, val detail: Boolean, val observedAtMs: Long)

    /** 抓树 → 建观察 → 登记为该 display 的最新屏。previous 用于计算「+」。 */
    fun observe(driver: ScreenDriver, budgets: Budgets, gate: SafetyGate?, detail: Boolean): Observation {
        val snapshot = driver.capture(budgets.captureBudgetMs)
        return register(snapshot, budgets, gate, detail)
    }

    /**
     * 用一份已抓好的快照建观察并登记（测试、collect 的逐页抓取、调试控制台导入 dump 用）。
     * 与 [observe] 走完全相同的路径。
     */
    fun register(snapshot: RawSnapshot, budgets: Budgets, gate: SafetyGate?, detail: Boolean): Observation {
        val display = snapshot.display
        // previous 的读取和新屏的登记必须在同一把锁里，否则两个并发观察会拿同一个 previous 算「+」，
        // 而屏幕号顺序又和登记顺序不一致。构建是纯 CPU，耗时可控（几 ms 到几十 ms），可以放在锁里。
        synchronized(lock) {
            val prev = history[display]?.lastOrNull()?.observation
            val screenId = "${display.screenPrefix}${counter.incrementAndGet()}"
            val obs = buildObservation(snapshot, screenId, prev, budgets, gate, detail)
            val q = history.getOrPut(display) { ArrayDeque() }
            q.addLast(Entry(obs, detail, clock.nowMs()))
            while (q.size > keepPerDisplay) {
                val dropped = q.removeFirst()
                geometries.remove(dropped.observation.screenId)
            }
            return obs
        }
    }

    /** 该 display 的最新观察；从未观察过返回 null。 */
    fun latest(display: DisplayKind): Observation? = synchronized(lock) { history[display]?.lastOrNull()?.observation }

    /** 该 display 的上一屏（最新之前的那次）。 */
    fun previous(display: DisplayKind): Observation? = synchronized(lock) {
        val q = history[display] ?: return null
        if (q.size >= 2) q[q.size - 2].observation else null
    }

    /** 某个屏幕的观察时间（ms）；不在历史里返回 null。 */
    fun observedAt(screenId: String): Long? = synchronized(lock) {
        history.values.asSequence().flatMap { it.asSequence() }.firstOrNull { it.observation.screenId == screenId }?.observedAtMs
    }

    /** 清空全部状态（服务重启、用户停止任务后可选调用）。屏幕号计数器**不**回退。 */
    fun clear() = synchronized(lock) {
        history.clear()
        geometries.clear()
    }

    /** 解析 ref：屏幕号不是最新 → STALE_SCREEN（附 latest 文本）；序号越界 → INVALID_ARGS。 */
    fun resolve(ref: String): ObservedElement {
        val parsed = parseRef(ref)
        val latest = latest(parsed.display) ?: throw PhoneException(
            ErrorCode.STALE_SCREEN, "还没有观察过屏幕，先调用 screen",
            details = mapOf("ref" to ref),
        )
        if (latest.screenId != parsed.screenId) throw stale(
            "ref $ref 所属屏幕 ${parsed.screenId} 已过期，最新屏幕是 ${latest.screenId}", latest, ref,
        )
        return latest.element(parsed.index) ?: throw PhoneException(
            ErrorCode.INVALID_ARGS, "屏幕 ${latest.screenId} 没有序号 ${parsed.index}（共 ${latest.elements.size} 项）",
            details = mapOf("ref" to ref, "count" to latest.elements.size),
        )
    }

    /**
     * 动作前重定位：重新抓树，按指纹找回目标，返回 (新的 ObservedElement, 新的 Observation)。
     * 0 个 → STALE_SCREEN（附新观察）；同距离多个 → AMBIGUOUS。新观察会成为最新屏。
     *
     * 匹配顺序：
     *  1. 精确指纹；
     *  2. 放宽：同 role + 同文本 + 同 viewId（列表滚动了一点、窗口 id 变了时仍能找回）；
     *     文本和 viewId 都为空的元素不做放宽匹配——没有任何身份信息，放宽等于瞎猜。
     * 多个候选取中心点离原位置最近的；最近两个距离差 ≤ 2 px 视为分不清 → AMBIGUOUS。
     */
    fun relocate(driver: ScreenDriver, element: ObservedElement, budgets: Budgets, gate: SafetyGate?): Pair<ObservedElement, Observation> {
        val display = displayOfRef(element.ref) ?: driver.display
        val detail = synchronized(lock) { history[display]?.lastOrNull()?.detail ?: false }
        val obs = observe(driver, budgets, gate, detail)
        val found = match(element, obs)
        return found to obs
    }

    /** 在给定观察里找回 [element]（[relocate] 的纯函数部分，便于单测）。 */
    fun match(element: ObservedElement, obs: Observation): ObservedElement {
        val exact = obs.elements.filter { it.fingerprint == element.fingerprint }
        val candidates = exact.ifEmpty {
            val hasIdentity = element.rawLabel.isNotBlank() || !element.viewId.isNullOrBlank()
            if (!hasIdentity) emptyList()
            else obs.elements.filter {
                it.role == element.role && it.rawLabel == element.rawLabel && it.viewId == element.viewId
            }
        }
        if (candidates.isEmpty()) throw stale("目标 ${element.ref}（${element.role.zh}「${element.label}」）在新屏幕上找不到了", obs, element.ref)
        if (candidates.size == 1) return candidates[0]
        val ranked = candidates.map { it to distance(it, element) }.sortedBy { it.second }
        if (ranked[1].second - ranked[0].second <= AMBIGUOUS_PX) {
            throw PhoneException(
                ErrorCode.AMBIGUOUS,
                "目标 ${element.ref} 在新屏幕上有多个同样的元素（${ranked.take(4).joinToString { it.first.ref }}），请按新屏幕的 ref 重新指定",
                details = mapOf("ref" to element.ref, "candidates" to ranked.map { it.first.ref }),
                observationText = obs.text,
            )
        }
        return ranked[0].first
    }

    /** observation 参数（tap_xy/swipe）：必须等于该 display 最新带截图的屏幕号。 */
    fun requireLatestScreen(screenId: String): Observation {
        val display = displayOfScreen(screenId) ?: throw PhoneException(
            ErrorCode.INVALID_ARGS, "observation 应为屏幕号，如 s12", details = mapOf("observation" to screenId),
        )
        val (latest, hasGeometry) = synchronized(lock) {
            val l = history[display]?.lastOrNull()?.observation
            l to (l != null && geometries.containsKey(l.screenId))
        }
        if (latest == null) throw PhoneException(ErrorCode.STALE_SCREEN, "还没有观察过屏幕，先调用 screen(image=true)")
        if (latest.screenId != screenId) {
            throw stale("屏幕 $screenId 已过期，最新屏幕是 ${latest.screenId}；坐标动作需要基于最新截图，请先 screen(image=true)", latest, screenId)
        }
        if (!hasGeometry) throw PhoneException(
            ErrorCode.INVALID_ARGS, "屏幕 $screenId 没有附带截图，坐标动作需要先 screen(image=true)",
            details = mapOf("observation" to screenId),
        )
        return latest
    }

    /** 记录某个屏幕附带的截图几何（scale），tap_xy 映射用。屏幕不在历史里时忽略（已过期，无意义）。 */
    fun attachImageGeometry(screenId: String, width: Int, height: Int, scale: Double) = synchronized(lock) {
        val known = history.values.any { q -> q.any { it.observation.screenId == screenId } }
        if (known) geometries[screenId] = ImageGeometry(width, height, scale)
    }

    /** 取截图几何；没有截图返回 null。 */
    fun imageGeometry(screenId: String): ImageGeometry? = synchronized(lock) { geometries[screenId] }

    // ------------------------------------------------------------------ 内部

    private fun buildObservation(
        snapshot: RawSnapshot, screenId: String, prev: Observation?, budgets: Budgets, gate: SafetyGate?, detail: Boolean,
    ): Observation {
        val opts = options.copy(
            ownPackage = options.ownPackage ?: gate?.ownPackage,
            maxLabelChars = budgets.maxLabelChars,
        )
        // 「+」只和同一个前台应用的上一屏比较：换了 App 整屏都是新的，全标「+」只是噪音。
        val prevFps = prev?.takeIf { it.snapshot.foregroundPackage == snapshot.foregroundPackage }
            ?.elements?.mapTo(HashSet()) { it.fingerprint }
        val built = ObservationBuilder.build(snapshot, screenId, prevFps, opts)
        val sparse = built.textElementCount < SPARSE_TEXT_THRESHOLD
        // 先用空文本建一个观察给 SafetyGate 标「⚠」（annotate 直接改 needsConfirm），再格式化。
        val draft = Observation(screenId, snapshot.display, snapshot, built.elements, "", emptyMap(), 0, sparse)
        gate?.annotate(draft)
        val hints = HashMap<Int, String>()
        for (e in built.elements) {
            if (e.role == Role.INPUT) draft.node(e.nodeId)?.hintText?.takeIf { it.isNotBlank() }?.let { hints[e.nodeId] = it }
        }
        val input = ObservationFormatter.Input(
            screenId = screenId, snapshot = snapshot, elements = built.elements,
            visibleChildren = built.visibleChildren, hints = hints, sparse = sparse, detail = detail,
            maxChars = budgets.maxObservationChars, maxLabelChars = budgets.maxLabelChars,
        )
        val out = ObservationFormatter.format(input)
        val structured = ObservationFormatter.structured(input, out.truncatedCount)
        return draft.copy(text = out.text, structured = structured, truncatedCount = out.truncatedCount)
    }

    private fun distance(a: ObservedElement, b: ObservedElement): Double {
        val dx = (a.bounds.centerX - b.bounds.centerX).toDouble()
        val dy = (a.bounds.centerY - b.bounds.centerY).toDouble()
        return Math.sqrt(dx * dx + dy * dy)
    }

    private fun stale(message: String, latest: Observation, ref: String): PhoneException = PhoneException(
        ErrorCode.STALE_SCREEN, message,
        details = mapOf("ref" to ref, "screen" to latest.screenId),
        observationText = latest.text,
    )

    /** 解析后的 ref。 */
    data class ParsedRef(val display: DisplayKind, val screenId: String, val index: Int)

    companion object {
        /** 文本元素少于这个数视为 sparse（协议 §3）。 */
        const val SPARSE_TEXT_THRESHOLD = 5

        /** 最近两个候选距离差在这个像素内视为分不清。 */
        const val AMBIGUOUS_PX = 2.0

        private val refPattern = Regex("""^([sb])(\d{1,18}):(\d{1,6})$""")
        private val screenPattern = Regex("""^([sb])(\d{1,18})$""")

        /** 解析 `s12:5`；格式错误抛 INVALID_ARGS。 */
        fun parseRef(ref: String): ParsedRef {
            val m = refPattern.matchEntire(ref.trim()) ?: throw PhoneException(
                ErrorCode.INVALID_ARGS, "ref 格式应为 s<屏幕号>:<序号>，如 s12:5", details = mapOf("ref" to ref),
            )
            val display = displayOfPrefix(m.groupValues[1][0])!!
            val index = m.groupValues[3].toInt()
            if (index < 1) throw PhoneException(ErrorCode.INVALID_ARGS, "ref 序号从 1 开始", details = mapOf("ref" to ref))
            return ParsedRef(display, m.groupValues[1] + m.groupValues[2], index)
        }

        /** ref 所属 display；格式不对返回 null。 */
        fun displayOfRef(ref: String): DisplayKind? = refPattern.matchEntire(ref.trim())?.let { displayOfPrefix(it.groupValues[1][0]) }

        /** 屏幕号所属 display；格式不对返回 null。 */
        fun displayOfScreen(screenId: String): DisplayKind? =
            screenPattern.matchEntire(screenId.trim())?.let { displayOfPrefix(it.groupValues[1][0]) }

        private fun displayOfPrefix(c: Char): DisplayKind? = DisplayKind.entries.firstOrNull { it.screenPrefix == c }
    }
}
