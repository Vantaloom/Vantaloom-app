package online.timefiles.vantaloom.phone.core.observe

import online.timefiles.vantaloom.phone.core.json.Json
import online.timefiles.vantaloom.phone.core.model.RawSnapshot

/**
 * 协议 §3 的观察文本与 structuredContent。
 *
 * 文本是给模型读的，所以每个决定都偏向「少而准」：
 *  - 角色只用固定中文词表，状态只列对决策有用的几个；
 *  - 整页有字数上限（默认 3500），超出时明确告诉模型还剩多少、该用什么工具继续，
 *    而不是悄悄截断让它以为看全了；
 *  - 密码框内容永远不渲染。
 */
object ObservationFormatter {

    /** 格式化输入：从 Observation 拆出来，便于不经过 ScreenRegistry 单独测试格式。 */
    data class Input(
        val screenId: String,
        val snapshot: RawSnapshot,
        val elements: List<ObservedElement>,
        /** 可滚动容器 nodeId → 可见子项数。 */
        val visibleChildren: Map<Int, Int> = emptyMap(),
        /** 输入框 nodeId → 提示文本（hintText），空输入框时展示。 */
        val hints: Map<Int, String> = emptyMap(),
        val sparse: Boolean = false,
        val detail: Boolean = false,
        val maxChars: Int = 3_500,
        val maxLabelChars: Int = 60,
    )

    /** 格式化输出：文本 + 被截掉的元素数。 */
    data class Output(val text: String, val truncatedCount: Int)

    /** 生成头行：`屏幕 s12 · 微信 (com.tencent.mm) · 竖屏 1080×2400 · 键盘收起 · 18 项`。 */
    fun header(screenId: String, snapshot: RawSnapshot, count: Int): String {
        val parts = ArrayList<String>()
        parts.add("屏幕 $screenId")
        val pkg = snapshot.foregroundPackage
        val label = snapshot.foregroundLabel?.takeIf { it.isNotBlank() }
        parts.add(
            when {
                label != null && pkg != null -> "$label ($pkg)"
                label != null -> label
                pkg != null -> pkg
                else -> "未知应用"
            },
        )
        parts.add("${orientation(snapshot)} ${snapshot.screenWidth}×${snapshot.screenHeight}")
        parts.add(if (snapshot.imeVisible) "键盘弹出" else "键盘收起")
        parts.add("$count 项")
        return parts.joinToString(" · ")
    }

    /** 竖屏/横屏：优先看宽高（折叠屏、平板的自然方向不一定是竖屏），宽高缺失时看 rotation。 */
    fun orientation(snapshot: RawSnapshot): String {
        if (snapshot.screenWidth > 0 && snapshot.screenHeight > 0) {
            return if (snapshot.screenWidth > snapshot.screenHeight) "横屏" else "竖屏"
        }
        return if (snapshot.rotation == 90 || snapshot.rotation == 270) "横屏" else "竖屏"
    }

    /** 生成完整文本。 */
    fun format(input: Input): Output {
        val sb = StringBuilder()
        sb.append(header(input.screenId, input.snapshot, input.elements.size)).append('\n')
        val footer = footerHints(input)
        // 预留尾部提示的空间，保证截断后提示仍在。
        val budget = input.maxChars - footer.sumOf { it.length + 1 } - 40

        if (input.elements.isEmpty()) {
            sb.append("（没有识别到可操作或带文字的元素）\n")
        }

        var currentZone: Zone? = null
        var written = 0
        var truncated = 0
        for (e in input.elements) {
            val line = "│ " + line(e, input)
            val zoneLine = if (e.zone != currentZone) (if (currentZone == null) "┌ " else "├ ") + e.zone.zh else null
            val need = line.length + 1 + (zoneLine?.length?.plus(1) ?: 0)
            if (sb.length + need > budget && written > 0) {
                truncated = input.elements.size - written
                break
            }
            if (zoneLine != null) { sb.append(zoneLine).append('\n'); currentZone = e.zone }
            sb.append(line).append('\n')
            written++
        }
        if (currentZone != null) sb.append("└\n")
        if (truncated > 0) sb.append("…还有 $truncated 项，用 collect 或 scroll\n")
        for (f in footer) sb.append(f).append('\n')
        return Output(sb.toString().trimEnd('\n'), truncated)
    }

    /** 单个元素一行（不含前缀 `│ `）：`[n] 角色「文本」 状态... [@x,y,w,h id=…] [+] [⚠]`。 */
    fun line(e: ObservedElement, input: Input): String {
        val sb = StringBuilder()
        sb.append('[').append(e.index).append("] ").append(e.role.zh)
        val password = ElementState.PASSWORD in e.states
        when {
            password -> sb.append("「•••」")
            e.label.isNotEmpty() -> sb.append('「').append(e.label).append('」')
            // 输入框、按钮这类即使没字也要显示空引号，让模型知道「这里应该有字但没有」。
            e.role !in noQuoteWhenEmpty -> sb.append("「」")
        }
        for (s in stateWords(e)) sb.append(' ').append(s)
        if (e.role == Role.INPUT && e.label.isEmpty() && !password) {
            input.hints[e.nodeId]?.takeIf { it.isNotBlank() }?.let {
                sb.append(" 提示「").append(ObservationBuilder.truncate(it, 24)).append('」')
            }
        }
        if (e.scrollable) input.visibleChildren[e.nodeId]?.takeIf { it > 0 }?.let { sb.append(" ($it 项可见)") }
        if (input.detail) {
            val b = e.bounds
            sb.append(" @").append(b.left).append(',').append(b.top).append(',').append(b.width).append(',').append(b.height)
            e.viewIdTail?.let { sb.append(" id=").append(it) }
        }
        if (e.isNew) sb.append(" +")
        if (e.needsConfirm) sb.append(" ⚠")
        return sb.toString()
    }

    private val noQuoteWhenEmpty = setOf(Role.LIST, Role.GRID, Role.PROGRESS, Role.SLIDER, Role.IMAGE, Role.OTHER)

    /** 状态词：勾选态按角色措辞；EDITABLE 不写（角色已说明）。 */
    fun stateWords(e: ObservedElement): List<String> {
        val out = ArrayList<String>()
        val s = e.states
        if (ElementState.CHECKED in s || ElementState.UNCHECKED in s) {
            val on = ElementState.CHECKED in s
            out.add(
                when (e.role) {
                    Role.SWITCH -> if (on) "已开启" else "已关闭"
                    Role.RADIO -> if (on) "已选中" else "未选中"
                    else -> if (on) "已勾选" else "未勾选"
                },
            )
        }
        if (ElementState.SELECTED in s && !(e.role == Role.RADIO)) out.add("已选中")
        if (ElementState.FOCUSED in s) out.add("已聚焦")
        if (ElementState.SCROLLABLE_V in s) out.add("可上下滑")
        if (ElementState.SCROLLABLE_H in s) out.add("可左右滑")
        if (ElementState.DISABLED in s) out.add("不可用")
        if (ElementState.PASSWORD in s) out.add("密码")
        if (ElementState.OFFSCREEN in s) out.add("部分在屏外")
        return out
    }

    /** 尾部提示行。 */
    fun footerHints(input: Input): List<String> {
        val out = ArrayList<String>()
        if (!input.snapshot.complete) out.add("提示：抓取界面超时，列出的元素可能不完整。")
        if (input.snapshot.secureWindow) out.add("提示：当前窗口禁止截图。")
        if (input.sparse) out.add("提示：界面主要是图形，建议 screen(image=true)")
        if (input.snapshot.imeVisible) out.add("提示：键盘弹出时底部按钮可能被遮挡。")
        if (input.elements.any { it.masked }) out.add("提示：卡号/证件号已打码。")
        return out
    }

    /** 协议 §3 的 structuredContent。包含全部元素（不受文本截断影响）。 */
    fun structured(input: Input, truncatedCount: Int): Map<String, Any?> {
        val s = input.snapshot
        val elements = input.elements.map { e ->
            val m = Json.obj(
                "ref" to e.ref,
                "role" to e.role.wire,
                "text" to if (ElementState.PASSWORD in e.states) "" else e.label,
                "states" to e.states.filter { it != ElementState.EDITABLE || e.role != Role.INPUT }.map { it.wire },
                "bounds" to listOf(e.bounds.left, e.bounds.top, e.bounds.width, e.bounds.height),
                "confirm" to e.needsConfirm,
            )
            if (e.isNew) m["new"] = true
            if (e.masked) m["masked"] = true
            e.viewIdTail?.let { m["id"] = it }
            if (e.packageName != null && e.packageName != s.foregroundPackage) m["package"] = e.packageName
            m
        }
        return Json.obj(
            "screen" to input.screenId,
            "display" to s.display.wire,
            "package" to s.foregroundPackage,
            "app" to s.foregroundLabel,
            "rotation" to s.rotation,
            "size" to listOf(s.screenWidth, s.screenHeight),
            "ime" to s.imeVisible,
            "secure" to s.secureWindow,
            "complete" to s.complete,
            "sparse" to input.sparse,
            "truncated" to truncatedCount,
            "elements" to elements,
        )
    }
}
