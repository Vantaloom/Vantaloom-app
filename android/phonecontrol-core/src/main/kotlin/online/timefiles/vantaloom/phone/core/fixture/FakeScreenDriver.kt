package online.timefiles.vantaloom.phone.core.fixture

import online.timefiles.vantaloom.phone.core.driver.AppEntry
import online.timefiles.vantaloom.phone.core.driver.DriverAvailability
import online.timefiles.vantaloom.phone.core.driver.GlobalAction
import online.timefiles.vantaloom.phone.core.driver.IntentRequest
import online.timefiles.vantaloom.phone.core.driver.ScreenDriver
import online.timefiles.vantaloom.phone.core.driver.Screenshot
import online.timefiles.vantaloom.phone.core.model.DisplayKind
import online.timefiles.vantaloom.phone.core.model.ErrorCode
import online.timefiles.vantaloom.phone.core.model.NodeAction
import online.timefiles.vantaloom.phone.core.model.PhoneException
import online.timefiles.vantaloom.phone.core.model.RawSnapshot
import online.timefiles.vantaloom.phone.core.ports.Clock

/**
 * 回放 JSON 夹具的假驱动（JVM 测试、调试控制台离线演示用）。
 *
 * 行为：
 *  - [capture] 按顺序返回 [snapshots]；用完后一直返回最后一份（模拟「界面不再变化」）。
 *    [advanceOn] 可以改成「只有动作发生后才前进一帧」，更接近真机（看一眼不会改变界面）。
 *  - 每次 capture 返回的快照都会被改写 captureId（单调递增）和 capturedAtMs（取自 [clock]），
 *    因为同一份夹具会被回放多次，而 core 依赖 captureId 区分不同抓取。
 *  - 所有动作记入 [calls]，测试可以断言「点了哪个节点 / 坐标」。
 *  - 可以按调用名脚本化失败：[failNext] 让下一次某类调用返回 false，[throwNext] 让它抛指定异常。
 *  - performNode 校验 captureId 是否在最近 2 次 capture 内，否则抛 STALE_SCREEN（与真驱动约定一致）。
 *
 * 线程安全：所有可变状态在 synchronized(this) 下访问。
 *
 * @param snapshots 回放序列，至少一份。
 * @param clock 时间源。
 * @param display 本驱动负责的 display。
 */
class FakeScreenDriver(
    snapshots: List<RawSnapshot>,
    private val clock: Clock = Clock.System,
    override val display: DisplayKind = DisplayKind.MAIN,
) : ScreenDriver {

    /** 回放前进策略。 */
    enum class Advance {
        /** 每次 capture 都前进一帧。 */
        EVERY_CAPTURE,
        /** 只有成功的动作（performNode/tap/swipe/globalAction/launch/open/intent）之后的下一次 capture 才前进。 */
        AFTER_ACTION,
        /** 永远停在当前帧（固定返回）。 */
        NEVER,
    }

    /** 一次被记录的调用。[name] 用驱动方法名；[args] 是参数的可读映射。 */
    data class Call(val name: String, val args: Map<String, Any?>, val atMs: Long, val result: Any?)

    private val frames: MutableList<RawSnapshot> = snapshots.toMutableList().also {
        require(it.isNotEmpty()) { "FakeScreenDriver 至少需要一份快照" }
    }
    private var cursor = 0
    private var pendingAdvance = false
    private var nextCaptureId = 1L
    private val recentCaptures = ArrayDeque<Long>()
    private val callLog = ArrayList<Call>()
    private val failures = HashMap<String, ArrayDeque<Any>>() // 值是 Boolean(false) 或 Throwable
    private var lastInjection = 0L

    /** 回放前进策略，默认每次 capture 前进。 */
    @Volatile var advanceOn: Advance = Advance.EVERY_CAPTURE

    /** availability() 的返回值，可整体替换。 */
    @Volatile var availabilityValue: DriverAvailability = DriverAvailability(
        serviceConnected = true, screenOn = true, locked = false, apiLevel = 34,
        canScreenshot = true, canImeEnter = true, overlayAttached = true,
        manufacturer = "fake", model = "fixture", romHint = null,
    )

    /** screenshot() 返回的结果；null 时抛 UNSUPPORTED。 */
    @Volatile var screenshotValue: Screenshot? = Screenshot(jpeg = ByteArray(0), width = 576, height = 1280, scale = 0.5333, secure = false, lumaVariance = 100.0)

    /** launchableApps() 的返回值。 */
    @Volatile var apps: List<AppEntry> = emptyList()

    /** 剪贴板内容。 */
    @Volatile var clipboard: String? = null

    /** awaitSettle 假装等待的毫秒数（不真睡，只推进记录）。 */
    @Volatile var settleMs: Long = 0

    /** 所有记录的调用（快照副本）。 */
    val calls: List<Call> get() = synchronized(this) { callLog.toList() }

    /** 按方法名过滤调用。 */
    fun callsNamed(name: String): List<Call> = synchronized(this) { callLog.filter { it.name == name } }

    /** 动作类调用（不含 capture/availability/settle 等观察类）。 */
    fun actionCalls(): List<Call> = synchronized(this) { callLog.filter { it.name in ACTION_NAMES } }

    /** 最后一次调用。 */
    fun lastCall(): Call? = synchronized(this) { callLog.lastOrNull() }

    /** 清空调用记录。 */
    fun clearCalls() = synchronized(this) { callLog.clear() }

    /** 让下一次 [name] 调用返回 false（可叠加多次）。 */
    fun failNext(name: String, times: Int = 1) = synchronized(this) {
        val q = failures.getOrPut(name) { ArrayDeque() }
        repeat(times) { q.addLast(false) }
    }

    /** 让下一次 [name] 调用抛 [error]。 */
    fun throwNext(name: String, error: Throwable) = synchronized(this) {
        failures.getOrPut(name) { ArrayDeque() }.addLast(error)
    }

    /** 便捷：抛指定错误码的 PhoneException。 */
    fun throwNext(name: String, code: ErrorCode, message: String = "fake ${code.wire}") =
        throwNext(name, PhoneException(code, message))

    /** 追加回放帧。 */
    fun enqueue(vararg snapshots: RawSnapshot) = synchronized(this) { frames.addAll(snapshots) }

    /** 跳到指定帧。 */
    fun seek(index: Int) = synchronized(this) {
        cursor = index.coerceIn(0, frames.size - 1)
        pendingAdvance = false
    }

    /** 当前帧序号。 */
    fun currentFrame(): Int = synchronized(this) { cursor }

    // ------------------------------------------------------------------ ScreenDriver

    override fun availability(): DriverAvailability = availabilityValue

    override fun capture(budgetMs: Long): RawSnapshot = synchronized(this) {
        injected("capture")?.let { if (it is Throwable) throw it }
        val shouldAdvance = when (advanceOn) {
            Advance.EVERY_CAPTURE -> nextCaptureId > 1
            Advance.AFTER_ACTION -> pendingAdvance
            Advance.NEVER -> false
        }
        if (shouldAdvance && cursor < frames.size - 1) cursor++
        pendingAdvance = false
        val id = nextCaptureId++
        recentCaptures.addLast(id)
        while (recentCaptures.size > 2) recentCaptures.removeFirst()
        val snap = frames[cursor].copy(captureId = id, capturedAtMs = clock.nowMs(), display = display)
        record("capture", mapOf("budgetMs" to budgetMs, "frame" to cursor), id)
        snap
    }

    override fun performNode(captureId: Long, nodeId: Int, action: NodeAction, text: String?): Boolean = synchronized(this) {
        val args = mapOf("captureId" to captureId, "nodeId" to nodeId, "action" to action.name, "text" to text)
        if (captureId !in recentCaptures) {
            record("performNode", args, "STALE")
            throw PhoneException(ErrorCode.STALE_SCREEN, "capture $captureId 已释放")
        }
        act("performNode", args)
    }

    override fun tap(x: Int, y: Int, durationMs: Long): Boolean = synchronized(this) {
        lastInjection = clock.nowMs()
        act("tap", mapOf("x" to x, "y" to y, "durationMs" to durationMs))
    }

    override fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): Boolean = synchronized(this) {
        lastInjection = clock.nowMs()
        act("swipe", mapOf("fromX" to fromX, "fromY" to fromY, "toX" to toX, "toY" to toY, "durationMs" to durationMs))
    }

    override fun globalAction(action: GlobalAction): Boolean = synchronized(this) {
        act("globalAction", mapOf("action" to action.wire))
    }

    override fun screenshot(timeoutMs: Long): Screenshot = synchronized(this) {
        injected("screenshot")?.let {
            if (it is Throwable) throw it
            record("screenshot", mapOf("timeoutMs" to timeoutMs), false)
            throw PhoneException(ErrorCode.ACTION_FAILED, "fake 截图失败")
        }
        val s = screenshotValue
        record("screenshot", mapOf("timeoutMs" to timeoutMs), s != null)
        s ?: throw PhoneException(ErrorCode.UNSUPPORTED, "fake 驱动未配置截图")
    }

    override fun launchPackage(packageName: String): Boolean = synchronized(this) {
        act("launchPackage", mapOf("packageName" to packageName))
    }

    override fun openUri(uri: String): Boolean = synchronized(this) { act("openUri", mapOf("uri" to uri)) }

    override fun sendIntent(request: IntentRequest): Boolean = synchronized(this) {
        act("sendIntent", mapOf("kind" to request.kind.wire, "fields" to request.fields))
    }

    override fun launchableApps(): List<AppEntry> = synchronized(this) {
        injected("launchableApps")?.let { if (it is Throwable) throw it else return@synchronized emptyList() }
        record("launchableApps", emptyMap(), apps.size)
        apps
    }

    override fun clipboardGet(): String? = synchronized(this) {
        injected("clipboardGet")?.let { if (it is Throwable) throw it else return@synchronized null }
        record("clipboardGet", emptyMap(), clipboard != null)
        clipboard
    }

    override fun clipboardSet(text: String): Boolean = synchronized(this) {
        val ok = act("clipboardSet", mapOf("length" to text.length), advance = false)
        if (ok) clipboard = text
        ok
    }

    override fun postNotification(title: String, text: String): Boolean = synchronized(this) {
        act("postNotification", mapOf("title" to title, "text" to text), advance = false)
    }

    override fun awaitSettle(quietMs: Long, maxMs: Long): Long = synchronized(this) {
        val waited = settleMs.coerceAtMost(maxMs)
        record("awaitSettle", mapOf("quietMs" to quietMs, "maxMs" to maxMs), waited)
        waited
    }

    override fun lastInjectionAtMs(): Long = synchronized(this) { lastInjection }

    // ------------------------------------------------------------------ 内部

    /** 取出该调用名排队的失败注入；没有返回 null。调用方已持锁。 */
    private fun injected(name: String): Any? = failures[name]?.removeFirstOrNull()

    /** 执行一次动作类调用：先看失败注入，再记录；成功时标记下一次 capture 前进。 */
    private fun act(name: String, args: Map<String, Any?>, advance: Boolean = true): Boolean {
        when (val f = injected(name)) {
            is Throwable -> { record(name, args, f::class.simpleName); throw f }
            false -> { record(name, args, false); return false }
        }
        record(name, args, true)
        if (advance) pendingAdvance = true
        return true
    }

    private fun record(name: String, args: Map<String, Any?>, result: Any?) {
        callLog.add(Call(name, args, clock.nowMs(), result))
    }

    companion object {
        /** 视为「动作」的调用名。 */
        val ACTION_NAMES: Set<String> = setOf(
            "performNode", "tap", "swipe", "globalAction", "launchPackage", "openUri", "sendIntent",
            "clipboardSet", "postNotification",
        )

        /** 从夹具 JSON 文本构造（单份、数组或 {snapshots:[…]} 均可）。 */
        fun fromJson(text: String, clock: Clock = Clock.System, display: DisplayKind = DisplayKind.MAIN): FakeScreenDriver =
            FakeScreenDriver(SnapshotJson.listFromJsonString(text), clock, display)
    }
}
