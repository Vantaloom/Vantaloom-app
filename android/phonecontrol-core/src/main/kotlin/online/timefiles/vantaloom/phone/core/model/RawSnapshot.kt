package online.timefiles.vantaloom.phone.core.model

/**
 * 平台无关的「一次抓树」结果。Android 驱动把 AccessibilityWindowInfo / AccessibilityNodeInfo
 * 映射成它；core 里的一切（观察格式化、指纹、安全门、工具）只看这个结构，
 * 所以可以在 JVM 上用 JSON 夹具（debug dump）回放测试。
 *
 * 约定：
 *  - [captureId] 由驱动单调分配。驱动至少保留最近 2 次 capture 的原生节点句柄，
 *    以便 [online.timefiles.vantaloom.phone.core.driver.ScreenDriver.performNode] 按
 *    (captureId, nodeId) 找回真实节点；更早的可以释放。
 *  - [RawNode.id] 在同一次 capture 内唯一（前序遍历序号），跨 capture 无意义。
 *  - 坐标一律是屏幕像素（getBoundsInScreen）。
 */
data class RawSnapshot(
    val captureId: Long,
    val display: DisplayKind,
    /** 前台（活动窗口）包名；抓不到时为 null。 */
    val foregroundPackage: String?,
    /** 前台应用的显示名（驱动用 PackageManager 解析）；可空。 */
    val foregroundLabel: String?,
    val screenWidth: Int,
    val screenHeight: Int,
    /** 0/90/180/270 */
    val rotation: Int,
    /** 输入法窗口是否可见。 */
    val imeVisible: Boolean,
    /** 活动窗口带 FLAG_SECURE（能判断时）。 */
    val secureWindow: Boolean,
    /** 按 z 序从上到下的窗口。驱动应排除自己的浮层窗口。 */
    val windows: List<RawWindow>,
    /** 抓取是否在预算内完整完成；false = 部分树（超时）。 */
    val complete: Boolean,
    val capturedAtMs: Long,
)

enum class DisplayKind(val wire: String, val screenPrefix: Char) {
    MAIN("main", 's'),
    BACKGROUND("background", 'b');

    companion object {
        fun fromWire(value: String?): DisplayKind? = when (value) {
            null, "", "main" -> MAIN
            "background" -> BACKGROUND
            else -> null
        }
    }
}

data class RawWindow(
    val windowId: Int,
    val type: WindowType,
    val packageName: String?,
    val title: String?,
    val bounds: Rect,
    val active: Boolean,
    val focused: Boolean,
    val layer: Int,
    /** 根节点；系统拒绝返回时为 null。 */
    val root: RawNode?,
)

enum class WindowType { APPLICATION, INPUT_METHOD, SYSTEM, ACCESSIBILITY_OVERLAY, SPLIT_SCREEN_DIVIDER, MAGNIFICATION_OVERLAY, UNKNOWN }

data class RawNode(
    val id: Int,
    val windowId: Int,
    val className: String?,
    val packageName: String?,
    val text: String?,
    val contentDescription: String?,
    val hintText: String?,
    /** viewIdResourceName（如 com.tencent.mm:id/send），需要 flagReportViewIds。 */
    val viewId: String?,
    val paneTitle: String?,
    val stateDescription: String?,
    val tooltip: String?,
    val bounds: Rect,
    val clickable: Boolean,
    val longClickable: Boolean,
    val focusable: Boolean,
    val focused: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val selected: Boolean,
    val enabled: Boolean,
    val password: Boolean,
    val visibleToUser: Boolean,
    val heading: Boolean,
    /** 可用的无障碍动作（驱动从 actionList 映射，识别不了的丢掉）。 */
    val actions: Set<NodeAction>,
    /** 列表/网格的行列数（CollectionInfo），没有时 -1。 */
    val collectionRows: Int = -1,
    val collectionColumns: Int = -1,
    /** 输入框最大长度，未知 -1。 */
    val maxTextLength: Int = -1,
    val children: List<RawNode>,
)

/** 驱动能执行的节点级动作（对应 AccessibilityAction）。 */
enum class NodeAction {
    CLICK, LONG_CLICK, FOCUS, SET_TEXT, IME_ENTER,
    SCROLL_FORWARD, SCROLL_BACKWARD, SCROLL_UP, SCROLL_DOWN, SCROLL_LEFT, SCROLL_RIGHT,
    SHOW_ON_SCREEN, SELECT, EXPAND, COLLAPSE, DISMISS, COPY, PASTE,
}

data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2
    val isEmpty: Boolean get() = width <= 0 || height <= 0
    fun contains(x: Int, y: Int): Boolean = x in left until right && y in top until bottom
    fun intersects(o: Rect): Boolean = left < o.right && o.left < right && top < o.bottom && o.top < bottom
    fun area(): Long = if (isEmpty) 0 else width.toLong() * height.toLong()

    companion object {
        val EMPTY = Rect(0, 0, 0, 0)
    }
}
