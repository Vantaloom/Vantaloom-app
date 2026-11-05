package online.timefiles.vantaloom.phone.core.observe

import online.timefiles.vantaloom.phone.core.model.NodeAction
import online.timefiles.vantaloom.phone.core.model.RawNode
import online.timefiles.vantaloom.phone.core.model.RawSnapshot
import online.timefiles.vantaloom.phone.core.model.RawWindow
import online.timefiles.vantaloom.phone.core.model.Rect
import online.timefiles.vantaloom.phone.core.model.WindowType

/**
 * 观察构建的可调策略。默认值即协议 §3 的行为；测试和调试控制台可以放宽。
 */
data class BuildOptions(
    /**
     * 本 App 的包名。属于它的窗口整体排除——那是我们自己的浮层/确认卡，
     * 让模型看到（更别说点到）自己的审批界面是协议 §6 明确要防的。驱动应已排除，这里兜底。
     */
    val ownPackage: String? = null,
    /** 是否收录输入法窗口。默认否：模型用 SET_TEXT 输入，不该去点键盘按键；键盘状态在头行里体现。 */
    val includeIme: Boolean = false,
    /** 是否收录系统窗口（状态栏、导航栏、通知栏）。默认是：通知栏展开时模型需要看到通知。 */
    val includeSystemWindows: Boolean = true,
    /** 是否按上层窗口遮挡过滤下层元素（对话框盖住的背景不该出现在观察里）。 */
    val occlusionFilter: Boolean = true,
    /** 可点击容器吸收子文本时最多收集几段，防一个大卡片吸进整屏文字。 */
    val maxAbsorbedPieces: Int = 8,
    /** 单次观察最多收录的元素数（超出的直接丢弃，保护格式化和指纹计算的耗时）。 */
    val maxElements: Int = 400,
    /** 单个标签最多字数（协议 §3：60）。 */
    val maxLabelChars: Int = 60,
)

/**
 * 构建结果。[elements] 已排好阅读顺序、分配了序号和 ref，needsConfirm 均为 false（由 SafetyGate 后填）。
 * [visibleChildren] 是可滚动容器的「可见项数」（nodeId → 数量），Formatter 输出「(6 项可见)」用；
 * 它不属于 ObservedElement 的契约字段，所以单独带出。
 */
data class BuildResult(
    val elements: List<ObservedElement>,
    val visibleChildren: Map<Int, Int>,
    /** 有文本的元素数（sparse 判定）。 */
    val textElementCount: Int,
    /** 是否有元素因 maxElements 被丢弃。 */
    val overflowed: Boolean,
)

/**
 * RawSnapshot → 元素列表（协议 §3 的「收录」「折叠」「吸收」规则）。纯函数、无状态，便于用夹具单测。
 *
 * 规则摘要：
 *  1. 窗口：按 z 序（列表顺序，上层在前）处理；排除本 App、无障碍浮层、放大镜、分屏分隔条，默认排除输入法。
 *  2. 可见性：visibleToUser=false、bounds 为空、与屏幕不相交的节点跳过；被更上层窗口盖住中心点的节点跳过。
 *  3. 收录：可点击/可长按/可编辑/可滚动/可勾选，或有文本/描述的节点。
 *  4. 折叠：其余（纯布局）节点不出现，只继续遍历子节点。
 *  5. 吸收：可点击的容器（非输入框、非滚动容器）把「自身不可交互」的后代文本吸进标签，用 ` · ` 连接，
 *     被吸收的后代不再单独出现——否则一个列表项会变成「按钮 + 3 行文本」四个元素，模型更难对齐。
 *  6. 排序：窗口 z 序 → 分区 → top → left。分区放在排序里是为了让序号在格式化文本里严格递增。
 */
object ObservationBuilder {

    /** 构建元素列表。[previousFingerprints] 为 null 表示没有可比较的上一屏，此时不标「+」。 */
    fun build(
        snapshot: RawSnapshot,
        screenId: String,
        previousFingerprints: Set<String>?,
        options: BuildOptions = BuildOptions(),
    ): BuildResult {
        val screen = Rect(0, 0, maxOf(0, snapshot.screenWidth), maxOf(0, snapshot.screenHeight))
        val windows = snapshot.windows.filter { includeWindow(it, options) }
        val drafts = ArrayList<Draft>()
        val visibleChildren = HashMap<Int, Int>()
        val occluders = ArrayList<Rect>()
        var overflowed = false

        for ((rank, w) in windows.withIndex()) {
            val root = w.root
            if (root != null && !overflowed) {
                val ctx = WalkContext(w, rank, screen, occluders.toList(), options, snapshot)
                collect(root, ctx, drafts, visibleChildren)
                if (drafts.size > options.maxElements) overflowed = true
            }
            // 当前窗口成为下面窗口的遮挡物。系统栏这类小窗口也算，正好盖住的就是它们自己的区域。
            if (!w.bounds.isEmpty && w.type != WindowType.ACCESSIBILITY_OVERLAY) occluders.add(w.bounds)
        }

        val kept = if (drafts.size > options.maxElements) drafts.subList(0, options.maxElements) else drafts
        val sorted = kept.sortedWith(
            compareBy<Draft>({ it.windowRank }, { it.zone.ordinal }, { it.bounds.top }, { it.bounds.left }, { it.node.id }),
        )

        val out = ArrayList<ObservedElement>(sorted.size)
        var textCount = 0
        for ((i, d) in sorted.withIndex()) {
            val index = i + 1
            val fp = Fingerprint.of(
                d.window.windowId, d.bounds, d.role, d.rawLabel,
                if (d.node.checkable) d.node.checked else null,
                snapshot.screenWidth, snapshot.screenHeight,
            )
            if (d.rawLabel.isNotBlank()) textCount++
            out.add(
                ObservedElement(
                    index = index,
                    ref = "$screenId:$index",
                    nodeId = d.node.id,
                    captureId = snapshot.captureId,
                    windowId = d.window.windowId,
                    packageName = d.node.packageName ?: d.window.packageName,
                    role = d.role,
                    label = truncate(d.rawLabel, options.maxLabelChars),
                    rawLabel = d.rawLabel,
                    viewId = d.node.viewId,
                    bounds = d.bounds,
                    states = d.states,
                    actions = d.node.actions,
                    zone = d.zone,
                    fingerprint = fp,
                    isNew = previousFingerprints != null && fp !in previousFingerprints,
                    needsConfirm = false,
                    masked = d.masked,
                ),
            )
        }
        return BuildResult(out, visibleChildren, textCount, overflowed)
    }

    /** 窗口过滤策略（见 [BuildOptions]）。 */
    fun includeWindow(w: RawWindow, options: BuildOptions): Boolean {
        if (options.ownPackage != null && w.packageName == options.ownPackage) return false
        return when (w.type) {
            WindowType.ACCESSIBILITY_OVERLAY, WindowType.MAGNIFICATION_OVERLAY, WindowType.SPLIT_SCREEN_DIVIDER -> false
            WindowType.INPUT_METHOD -> options.includeIme
            WindowType.SYSTEM -> options.includeSystemWindows
            WindowType.APPLICATION, WindowType.UNKNOWN -> true
        }
    }

    /** 标签截断：换行压成空格，超过 max 用「…」。按 code point 截，不切开 emoji 代理对。 */
    fun truncate(text: String, max: Int): String {
        val flat = flatten(text)
        if (max <= 0) return ""
        if (flat.codePointCount(0, flat.length) <= max) return flat
        val end = flat.offsetByCodePoints(0, max - 1)
        return flat.substring(0, end) + "…"
    }

    /** 把多行文本压成一行、合并连续空白。 */
    fun flatten(text: String): String {
        val sb = StringBuilder(text.length)
        var space = false
        for (ch in text) {
            if (ch.isWhitespace() || ch == '\u2028' || ch == '\u2029') { space = true; continue }
            if (space && sb.isNotEmpty()) sb.append(' ')
            space = false
            sb.append(ch)
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ 内部

    private class WalkContext(
        val window: RawWindow,
        val rank: Int,
        val screen: Rect,
        val occluders: List<Rect>,
        val options: BuildOptions,
        val snapshot: RawSnapshot,
    )

    private class Draft(
        val node: RawNode,
        val window: RawWindow,
        val windowRank: Int,
        val role: Role,
        val rawLabel: String,
        val bounds: Rect,
        val states: Set<ElementState>,
        val zone: Zone,
        val masked: Boolean,
    )

    private fun isActionable(n: RawNode): Boolean =
        n.clickable || n.longClickable || NodeAction.CLICK in n.actions || NodeAction.LONG_CLICK in n.actions

    private fun isInteractive(n: RawNode): Boolean =
        isActionable(n) || n.editable || n.scrollable || n.checkable

    private fun hasOwnText(n: RawNode): Boolean = !n.text.isNullOrBlank() || !n.contentDescription.isNullOrBlank()

    /** 节点在屏幕内的可见部分；不可见返回 null。 */
    private fun visibleBounds(n: RawNode, ctx: WalkContext): Rect? {
        if (!n.visibleToUser || n.bounds.isEmpty) return null
        val s = ctx.screen
        if (s.isEmpty) return n.bounds
        if (!n.bounds.intersects(s)) return null
        return Rect(maxOf(n.bounds.left, s.left), maxOf(n.bounds.top, s.top), minOf(n.bounds.right, s.right), minOf(n.bounds.bottom, s.bottom))
    }

    private fun occluded(b: Rect, ctx: WalkContext): Boolean {
        if (!ctx.options.occlusionFilter) return false
        val cx = b.centerX
        val cy = b.centerY
        return ctx.occluders.any { it.contains(cx, cy) }
    }

    private fun collect(root: RawNode, ctx: WalkContext, out: MutableList<Draft>, visibleChildren: MutableMap<Int, Int>) {
        // 用显式栈做前序遍历；absorbed 标记被某个可点击容器吸收、不再单独收录的子树。
        val stack = ArrayDeque<RawNode>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            if (out.size > ctx.options.maxElements) return
            val n = stack.removeLast()
            val vb = visibleBounds(n, ctx)
            val include = vb != null && !occluded(vb, ctx) && (isInteractive(n) || hasOwnText(n) || (n.editable && !n.hintText.isNullOrBlank()))
            var absorbChildren = false
            if (include) {
                val draft = makeDraft(n, vb!!, ctx)
                out.add(draft.first)
                absorbChildren = draft.second
                if (n.scrollable) visibleChildren[n.id] = n.children.count { c -> visibleBounds(c, ctx) != null }
            }
            if (absorbChildren) {
                // 被吸收的子树里仍可能有独立可交互的后代（行里的开关、删除按钮），它们要单独收录。
                pushInteractiveDescendants(n, stack)
            } else {
                for (i in n.children.indices.reversed()) stack.addLast(n.children[i])
            }
        }
    }

    /** 吸收模式下：只把子树里「自身可交互」的后代压栈（它们的子树按常规规则继续）。 */
    private fun pushInteractiveDescendants(n: RawNode, stack: ArrayDeque<RawNode>) {
        val found = ArrayList<RawNode>()
        fun rec(x: RawNode) {
            for (c in x.children) {
                if (isInteractive(c)) found.add(c) else rec(c)
            }
        }
        rec(n)
        for (i in found.indices.reversed()) stack.addLast(found[i])
    }

    /** 生成草稿；second = 是否吸收了子节点文本。 */
    private fun makeDraft(n: RawNode, vb: Rect, ctx: WalkContext): Pair<Draft, Boolean> {
        var role = RoleMapper.map(n)
        val actionable = isActionable(n)
        // 可点的图片/文本对模型来说就是按钮；保留 link 判断（viewId 带 link 的已在 RoleMapper 里处理）。
        if (actionable && (role == Role.IMAGE || role == Role.TEXT || role == Role.OTHER)) role = Role.BUTTON

        val pieces = ArrayList<String>()
        if (n.password) {
            // 密码框：内容永不进入任何标签（协议 §3）。rawLabel 置空，Formatter 显示「•••」。
        } else {
            val own = ownLabel(n, role)
            if (own.isNotBlank()) pieces.add(own)
        }

        var absorbed = false
        val canAbsorb = actionable && !n.editable && !n.scrollable && n.children.isNotEmpty()
        if (canAbsorb) {
            val texts = ArrayList<String>()
            gatherTexts(n, texts, ctx.options.maxAbsorbedPieces)
            for (t in texts) if (pieces.none { it == t }) pieces.add(t)
            absorbed = true
        }

        val joined = pieces.joinToString(" · ")
        val m = Masking.mask(flatten(joined))
        val masked = m.masked

        val states = LinkedHashSet<ElementState>()
        if (n.focused) states.add(ElementState.FOCUSED)
        if (n.selected) states.add(ElementState.SELECTED)
        if (n.checkable) states.add(if (n.checked) ElementState.CHECKED else ElementState.UNCHECKED)
        if (!n.enabled) states.add(ElementState.DISABLED)
        if (n.scrollable) states.add(scrollAxis(n))
        if (n.password) states.add(ElementState.PASSWORD)
        if (n.editable) states.add(ElementState.EDITABLE)
        if (vb != n.bounds) states.add(ElementState.OFFSCREEN)

        val draft = Draft(
            node = n, window = ctx.window, windowRank = ctx.rank, role = role, rawLabel = m.text,
            bounds = vb, states = states, zone = Zone.of(vb.centerY, ctx.snapshot.screenHeight), masked = masked,
        )
        return draft to absorbed
    }

    /**
     * 节点自身的标签：输入框用当前内容（没有内容就空，提示文本由 Formatter 另行展示），
     * 其余优先 text，再 contentDescription；两者都有且描述不是文本的重复时，用 ` · ` 连上。
     */
    private fun ownLabel(n: RawNode, role: Role): String {
        val text = n.text?.trim().orEmpty()
        val desc = n.contentDescription?.trim().orEmpty()
        if (n.editable || role == Role.INPUT) {
            // 有些输入框在空内容时把 hint 当 text 报出来，这里去掉，免得模型以为框里已经有字。
            return if (text.isNotEmpty() && text != n.hintText?.trim()) text else ""
        }
        if (text.isEmpty()) return desc
        if (desc.isEmpty() || desc == text || text.contains(desc) || desc.contains(text)) return if (desc.length > text.length && desc.contains(text)) desc else text
        return "$text · $desc"
    }

    /** 收集子树里「不可交互」节点的文本（前序），最多 max 段。 */
    private fun gatherTexts(n: RawNode, out: MutableList<String>, max: Int) {
        for (c in n.children) {
            if (out.size >= max) return
            if (!c.visibleToUser) continue
            if (isInteractive(c)) continue
            if (!c.password) {
                val t = c.text?.trim().takeUnless { it.isNullOrEmpty() } ?: c.contentDescription?.trim().orEmpty()
                if (t.isNotEmpty() && out.none { it == t }) out.add(t)
            }
            gatherTexts(c, out, max)
        }
    }

    /** 判断滚动方向：先看动作，再看类名，再看集合形状，默认竖向。 */
    private fun scrollAxis(n: RawNode): ElementState {
        val a = n.actions
        val v = NodeAction.SCROLL_UP in a || NodeAction.SCROLL_DOWN in a
        val h = NodeAction.SCROLL_LEFT in a || NodeAction.SCROLL_RIGHT in a
        if (h && !v) return ElementState.SCROLLABLE_H
        if (v) return ElementState.SCROLLABLE_V
        val simple = RoleMapper.simpleName(n.className)
        if (simple.contains("Horizontal") || simple.startsWith("ViewPager") || simple == "LazyRow" || simple == "LazyHorizontalGrid") {
            return ElementState.SCROLLABLE_H
        }
        if (n.collectionRows == 1 && n.collectionColumns > 1) return ElementState.SCROLLABLE_H
        return ElementState.SCROLLABLE_V
    }
}
