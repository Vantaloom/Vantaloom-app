package online.timefiles.vantaloom.phone.a11y

import online.timefiles.vantaloom.phone.core.ports.Clock
import java.util.concurrent.atomic.AtomicLong

/**
 * 无障碍事件的轻量记录：只记「最近一次事件的时间」和「最近的前台包」。
 *
 * 为什么单独抽出来：
 *  - onAccessibilityEvent 在主线程高频触发，这里只做两次原子写，不持有 AccessibilityEvent（它会被系统回收）。
 *  - awaitSettle 在工具线程轮询它；测试里可以直接用假 [Clock] 构造并手动 [onEvent]。
 * 时间统一用 core 的 [Clock]，与驱动的 sleep / 注入时间戳同一个时基。
 */
class EventTracker(private val clock: Clock) {
    private val lastEventAt = AtomicLong(0L)
    private val eventCount = AtomicLong(0L)

    /** 最近一次改变窗口状态的前台包（TYPE_WINDOW_STATE_CHANGED 的 packageName）。 */
    @Volatile
    var foregroundPackage: String? = null
        private set

    /** 前台包上次变化的时间（wait(package=…) 诊断用）。 */
    @Volatile
    var foregroundChangedAtMs: Long = 0L
        private set

    /** 记录一次事件。[windowStatePackage] 非空表示这是一次窗口状态变化。 */
    fun onEvent(windowStatePackage: String?) {
        val now = clock.nowMs()
        lastEventAt.set(now)
        eventCount.incrementAndGet()
        if (windowStatePackage != null && windowStatePackage != foregroundPackage) {
            foregroundPackage = windowStatePackage
            foregroundChangedAtMs = now
        }
    }

    /** 最近一次事件的时间（Clock 时基）；从未收到为 0。 */
    fun lastEventAtMs(): Long = lastEventAt.get()

    /** 服务启动以来的事件数（demo 显示、判断服务是否「活着」）。 */
    fun eventCount(): Long = eventCount.get()
}
