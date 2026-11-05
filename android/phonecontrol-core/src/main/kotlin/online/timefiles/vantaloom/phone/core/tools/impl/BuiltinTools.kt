package online.timefiles.vantaloom.phone.core.tools.impl

/**
 * 协议 §4 的 16 个工具，固定顺序（tools/list 的顺序就是模型看到的顺序：观察 → 动作 → 控制）。
 * 顺序或集合变化都要动 ServerInfo.VERSION。
 */
object BuiltinTools {
    /** 新建一套工具实例。工具是无状态的，引擎持有一套即可。 */
    fun create(): List<BaseTool> = listOf(
        StatusTool(),
        AppsTool(),
        ScreenTool(),
        CollectTool(),
        WaitTool(),
        OpenTool(),
        IntentTool(),
        TapTool(),
        TypeTool(),
        ScrollTool(),
        SwipeTool(),
        TapXyTool(),
        KeyTool(),
        ClipboardTool(),
        NotifyTool(),
        HandoffTool(),
    )

    /** 工具名清单（文档/自检用）。 */
    val NAMES: List<String> = listOf(
        "status", "apps", "screen", "collect", "wait", "open", "intent", "tap",
        "type", "scroll", "swipe", "tap_xy", "key", "clipboard", "notify", "handoff",
    )
}
