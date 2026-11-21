package online.timefiles.vantaloom.phone.core.driver

import online.timefiles.vantaloom.phone.core.model.DisplayKind
import online.timefiles.vantaloom.phone.core.model.NodeAction
import online.timefiles.vantaloom.phone.core.model.RawSnapshot

/**
 * core → 平台的唯一接缝。主屏实现在 :android（AccessibilityService），后台虚拟屏（Shizuku）
 * 以后也实现这个接口；JVM 测试用 FakeScreenDriver 回放 JSON 夹具。
 *
 * 线程：所有方法都可能在工具执行线程上被同步调用（非主线程）。实现要自己把需要
 * 主线程的部分（如 dispatchGesture 回调、截图回调）同步化，并尊重 timeoutMs。
 *
 * 错误：实现抛 [online.timefiles.vantaloom.phone.core.model.PhoneException] 表达已知失败
 * （SERVICE_DISABLED / UNSUPPORTED / SCREEN_LOCKED …），其余异常会被包成 INTERNAL。
 */
interface ScreenDriver {
    val display: DisplayKind

    /** 当前能力与状态（status 工具、会话门用）。必须快（< 50 ms），不抓树。 */
    fun availability(): DriverAvailability

    /** 抓一次树。超过 budgetMs 时返回 complete=false 的部分树，不抛异常。 */
    fun capture(budgetMs: Long): RawSnapshot

    /**
     * 对某次 capture 里的节点执行无障碍动作。返回系统报告的结果（performAction 的返回值）。
     * capture 已被释放或节点已失效 → 抛 STALE_SCREEN。
     * [text] 只对 SET_TEXT 有意义。
     */
    fun performNode(captureId: Long, nodeId: Int, action: NodeAction, text: String? = null): Boolean

    /** 单点手势（屏幕像素）。durationMs：短按约 60，长按由调用方给（≥ longPressTimeout）。 */
    fun tap(x: Int, y: Int, durationMs: Long): Boolean

    /** 直线滑动手势。 */
    fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): Boolean

    fun globalAction(action: GlobalAction): Boolean

    /** 截图。不支持时抛 UNSUPPORTED；安全窗口时返回 secure=true 的 [Screenshot]（可以没有像素）。 */
    fun screenshot(timeoutMs: Long): Screenshot

    /** 按包名启动主 Activity。找不到抛 INVALID_ARGS。 */
    fun launchPackage(packageName: String): Boolean

    /** 打开 URL / 深链（ACTION_VIEW）。 */
    fun openUri(uri: String): Boolean

    /** 发一个系统意图。具体映射在平台侧。 */
    fun sendIntent(request: IntentRequest): Boolean

    /** 可启动应用（LAUNCHER）。 */
    fun launchableApps(): List<AppEntry>

    fun clipboardGet(): String?
    fun clipboardSet(text: String): Boolean

    /** 发一条本 App 的通知。 */
    fun postNotification(title: String, text: String): Boolean

    /**
     * 等界面稳定：自上次无障碍事件起安静 quietMs，或达到 maxMs。返回实际等待毫秒。
     * 实现用服务的 onAccessibilityEvent 时间戳。
     */
    fun awaitSettle(quietMs: Long, maxMs: Long): Long

    /** 自己注入手势前后调用，供 GuardedButton 判定注入窗口期。默认由平台实现内部处理。 */
    fun lastInjectionAtMs(): Long
}

data class DriverAvailability(
    val serviceConnected: Boolean,
    val screenOn: Boolean,
    val locked: Boolean,
    val apiLevel: Int,
    val canScreenshot: Boolean,
    val canImeEnter: Boolean,
    /** 浮层是否在场（不变量 2：没有浮层就不许动作）。 */
    val overlayAttached: Boolean,
    val manufacturer: String,
    val model: String,
    val romHint: String?,
    /** 不可用时给用户/模型看的一句话。 */
    val reason: String? = null,
)

enum class GlobalAction(val wire: String) {
    BACK("back"), HOME("home"), RECENTS("recents"), NOTIFICATIONS("notifications"),
    QUICK_SETTINGS("quick_settings"), LOCK_SCREEN("lock_screen"), SCREENSHOT("screenshot");

    companion object {
        fun fromWire(v: String?): GlobalAction? = entries.firstOrNull { it.wire == v }
    }
}

/**
 * 截图结果。[jpeg] 已按协议编码（长边 ≤ 1280、质量 80），尚未标注。
 * 标注由平台侧在 [annotate] 里做（需要 Bitmap/Canvas），core 只给出要画的框。
 */
data class Screenshot(
    val jpeg: ByteArray?,
    val width: Int,
    val height: Int,
    /** 截图像素 = 屏幕像素 × scale。 */
    val scale: Double,
    val secure: Boolean,
    /** 亮度方差（全黑检测用）；未计算为 -1。 */
    val lumaVariance: Double = -1.0,
    /** 平台绑定的标注器；输入屏幕像素框，返回重新编码的 JPEG。 */
    val annotate: ((List<Mark>) -> ByteArray)? = null,
) {
    data class Mark(val label: String, val left: Int, val top: Int, val right: Int, val bottom: Int, val highlight: Boolean = false)

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

data class AppEntry(val label: String, val packageName: String, val system: Boolean)

/** intent 工具的请求；fields 按 kind 取用，校验在 core。 */
data class IntentRequest(val kind: IntentKind, val fields: Map<String, String>)

enum class IntentKind(val wire: String) {
    ALARM("alarm"), TIMER("timer"), CALENDAR("calendar"), DIAL("dial"), SMS_DRAFT("sms_draft"),
    EMAIL_DRAFT("email_draft"), SHARE_TEXT("share_text"), NAVIGATE("navigate"), WEB_SEARCH("web_search"),
    SETTINGS("settings");

    companion object {
        fun fromWire(v: String?): IntentKind? = entries.firstOrNull { it.wire == v }
    }
}
