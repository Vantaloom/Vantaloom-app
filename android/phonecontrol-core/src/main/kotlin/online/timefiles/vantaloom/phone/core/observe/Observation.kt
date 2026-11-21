package online.timefiles.vantaloom.phone.core.observe

import online.timefiles.vantaloom.phone.core.model.DisplayKind
import online.timefiles.vantaloom.phone.core.model.NodeAction
import online.timefiles.vantaloom.phone.core.model.RawNode
import online.timefiles.vantaloom.phone.core.model.RawSnapshot
import online.timefiles.vantaloom.phone.core.model.Rect

/**
 * 一次观察的结果（协议 §2/§3）。
 *
 * [elements] 包含**全部**收录的元素，即使文本因超长被截断（[truncatedCount] > 0）也保留，
 * 这样被截掉的 ref 仍然可解析（模型通过 collect/scroll 后的新屏看到它们才会用到）。
 */
data class Observation(
    /** "s12" / "b3" */
    val screenId: String,
    val display: DisplayKind,
    /** 原始树（解析动作目标用）。 */
    val snapshot: RawSnapshot,
    val elements: List<ObservedElement>,
    /** 协议 §3 格式化文本（已截断）。 */
    val text: String,
    /** 协议 §3 structuredContent。 */
    val structured: Map<String, Any?>,
    /** 被截掉（文本里没列出）的元素数。 */
    val truncatedCount: Int,
    /** 文本节点 < 5，建议截图。 */
    val sparse: Boolean,
) {
    /**
     * 本次 capture 内 nodeId → 节点的索引。懒构建：多数观察只被格式化一次，
     * 但 SafetyGate（查 hint）和工具（找可点击祖先）会反复按 id 取节点。
     */
    val nodesById: Map<Int, RawNode> by lazy { SnapshotIndex.nodesById(snapshot) }

    /** nodeId → 父节点 id 的索引（tap 的「可点击祖先最多 4 层」要用）。 */
    val parentById: Map<Int, Int> by lazy { SnapshotIndex.parentById(snapshot) }

    /** 按 nodeId 取原始节点。 */
    fun node(nodeId: Int): RawNode? = nodesById[nodeId]

    /** 按 1-based 序号取元素。 */
    fun element(index: Int): ObservedElement? = elements.getOrNull(index - 1)

    /** 前台包名的便捷访问。 */
    val packageName: String? get() = snapshot.foregroundPackage

    /** 屏幕上是否有被打码的对象（协议 §10：此时不附截图）。 */
    val hasMasked: Boolean get() = elements.any { it.masked }

    /** 是否有元素被 SafetyGate 标为需要确认。 */
    val hasConfirm: Boolean get() = elements.any { it.needsConfirm }
}

/** 观察里的一个元素。字段语义见 CONTRACTS.md。 */
data class ObservedElement(
    /** 1-based 序号。 */
    val index: Int,
    /** "s12:5" */
    val ref: String,
    /** RawNode.id（本次 capture 内）。 */
    val nodeId: Int,
    val captureId: Long,
    val windowId: Int,
    val packageName: String?,
    val role: Role,
    /** 已打码/截断后的展示文本。 */
    val label: String,
    /** 未截断（仍不含密码；已打码；用于敏感词匹配）。 */
    val rawLabel: String,
    val viewId: String?,
    val bounds: Rect,
    val states: Set<ElementState>,
    val actions: Set<NodeAction>,
    val zone: Zone,
    val fingerprint: String,
    /** 「+」 */
    val isNew: Boolean,
    /** 「⚠」，由 SafetyGate.annotate 填。 */
    var needsConfirm: Boolean,
    /** 含被打码的卡号/证件号。 */
    val masked: Boolean,
) {
    /** viewIdResourceName 的末段（`com.tencent.mm:id/send` → `send`），detail 模式和敏感词匹配用。 */
    val viewIdTail: String? get() = viewIdTail(viewId)

    /** 是否可编辑（type 工具的前置检查）。 */
    val editable: Boolean get() = ElementState.EDITABLE in states

    /** 是否可滚动（任一方向）。 */
    val scrollable: Boolean get() = ElementState.SCROLLABLE_V in states || ElementState.SCROLLABLE_H in states

    companion object {
        /** viewId 末段；空串或 null 返回 null。 */
        fun viewIdTail(viewId: String?): String? {
            if (viewId.isNullOrBlank()) return null
            val slash = viewId.lastIndexOf('/')
            val tail = if (slash >= 0) viewId.substring(slash + 1) else viewId
            return tail.ifBlank { null }
        }
    }
}

/** 角色固定词表（协议 §3）。zh 给模型看，wire 进 structuredContent。 */
enum class Role(val zh: String, val wire: String) {
    BUTTON("按钮", "button"),
    TEXT("文本", "text"),
    INPUT("输入框", "input"),
    SWITCH("开关", "switch"),
    CHECKBOX("复选框", "checkbox"),
    RADIO("单选", "radio"),
    LIST("列表", "list"),
    GRID("网格", "grid"),
    TAB("标签页", "tab"),
    IMAGE("图片", "image"),
    LINK("链接", "link"),
    SLIDER("滑块", "slider"),
    PROGRESS("进度", "progress"),
    MENUITEM("菜单项", "menuitem"),
    OTHER("其他", "other");

    companion object {
        /** wire 名反查；未知返回 OTHER（夹具里写错角色名不应让回放失败）。 */
        fun fromWire(v: String?): Role = entries.firstOrNull { it.wire == v } ?: OTHER
    }
}

/** 元素状态。wire 是 structuredContent 里的小写名。 */
enum class ElementState {
    FOCUSED, SELECTED, CHECKED, UNCHECKED, DISABLED, SCROLLABLE_V, SCROLLABLE_H, PASSWORD, EDITABLE, OFFSCREEN;

    /** structuredContent 用的小写名。 */
    val wire: String get() = name.lowercase()

    /** 默认中文展示词（开关/复选框的勾选态由 Formatter 按角色另行措辞）。 */
    val zh: String
        get() = when (this) {
            FOCUSED -> "已聚焦"
            SELECTED -> "已选中"
            CHECKED -> "已勾选"
            UNCHECKED -> "未勾选"
            DISABLED -> "不可用"
            SCROLLABLE_V -> "可上下滑"
            SCROLLABLE_H -> "可左右滑"
            PASSWORD -> "密码"
            EDITABLE -> "可编辑"
            OFFSCREEN -> "部分在屏外"
        }
}

/** 按元素中心点 y 坐标的分区（协议 §3：< 15% 顶部，> 85% 底部）。 */
enum class Zone {
    TOP, MIDDLE, BOTTOM;

    /** 分区标题。 */
    val zh: String
        get() = when (this) {
            TOP -> "顶部"
            MIDDLE -> "中部"
            BOTTOM -> "底部"
        }

    companion object {
        /** 由中心 y 和屏幕高度算分区。屏幕高度未知（≤0）时一律算中部。 */
        fun of(centerY: Int, screenHeight: Int): Zone {
            if (screenHeight <= 0) return MIDDLE
            val r = centerY.toDouble() / screenHeight
            return when {
                r < 0.15 -> TOP
                r > 0.85 -> BOTTOM
                else -> MIDDLE
            }
        }
    }
}

/**
 * 截图几何：截图像素 = 屏幕像素 × [scale]。tap_xy/swipe 的坐标是截图像素，要映射回屏幕。
 */
data class ImageGeometry(val width: Int, val height: Int, val scale: Double) {
    /** 截图像素 → 屏幕像素：除以 scale、四舍五入，并夹到屏幕范围内（防模型给出图外坐标点到屏幕边缘外）。 */
    fun toScreen(x: Int, y: Int): Pair<Int, Int> {
        val s = if (scale > 0 && scale.isFinite()) scale else 1.0
        val sw = maxOf(1, Math.round(width / s).toInt())
        val sh = maxOf(1, Math.round(height / s).toInt())
        val sx = Math.round(x / s).toInt().coerceIn(0, sw - 1)
        val sy = Math.round(y / s).toInt().coerceIn(0, sh - 1)
        return sx to sy
    }

    /** 截图坐标是否落在图内（工具可以据此给出更明确的 INVALID_ARGS，而不是悄悄夹边）。 */
    fun contains(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height
}

/** RawSnapshot 的遍历索引工具。 */
object SnapshotIndex {
    /** 全部窗口里 nodeId → 节点。 */
    fun nodesById(snapshot: RawSnapshot): Map<Int, RawNode> {
        val m = HashMap<Int, RawNode>()
        for (w in snapshot.windows) w.root?.let { walk(it) { n -> m[n.id] = n } }
        return m
    }

    /** nodeId → 父 nodeId。 */
    fun parentById(snapshot: RawSnapshot): Map<Int, Int> {
        val m = HashMap<Int, Int>()
        fun rec(n: RawNode) {
            for (c in n.children) { m[c.id] = n.id; rec(c) }
        }
        for (w in snapshot.windows) w.root?.let { rec(it) }
        return m
    }

    /** 前序遍历（迭代实现，防极深的树把栈打爆）。 */
    inline fun walk(root: RawNode, visit: (RawNode) -> Unit) {
        val stack = ArrayDeque<RawNode>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            visit(n)
            for (i in n.children.indices.reversed()) stack.addLast(n.children[i])
        }
    }
}
