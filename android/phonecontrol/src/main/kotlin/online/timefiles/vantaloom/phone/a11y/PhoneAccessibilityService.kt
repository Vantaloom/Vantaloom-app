package online.timefiles.vantaloom.phone.a11y

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import online.timefiles.vantaloom.phone.host.PhoneControlHost
import java.lang.ref.WeakReference

/**
 * 无障碍服务本体。它自己几乎不做事：连上时把自己交给 [PhoneControlHost] 组装驱动/浮层/引擎/MCP 服务，
 * 断开时让 Host 拆掉。这样服务类保持很薄，生命周期逻辑集中在 Host，将来并入 Vantaloom-app 时也只是换个宿主。
 *
 * 「服务是否可用」的真相是 [current] 非空（onServiceConnected 之后、onUnbind 之前），
 * 而不是 Settings 里的开关值——ROM 杀掉服务后开关常常还亮着（设计文档 §6）。
 */
class PhoneAccessibilityService : AccessibilityService() {

    /** 事件时间戳与前台包。在 onServiceConnected 里用 Host 的时钟创建。 */
    @Volatile
    var events: EventTracker = EventTracker(PhoneControlHost.clock)
        private set

    /** onServiceConnected 之后为 true；onUnbind/onDestroy 后为 false。 */
    @Volatile
    var connected: Boolean = false
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        events = EventTracker(PhoneControlHost.clock)
        connected = true
        instanceRef = WeakReference(this)
        PhoneControlHost.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // 只拿需要的两个字段，不保留 event 引用（系统会回收重用它）。
        val pkg = if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()
        } else {
            null
        }
        // 自己的浮层也会产生窗口事件；它不是「前台应用」，但仍算一次界面变化（浮层动画期间不应判稳定）。
        events.onEvent(if (pkg == packageName) null else pkg)
    }

    override fun onInterrupt() {
        // 我们不提供语音/振动反馈，没有可中断的东西。
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        if (!connected) return
        connected = false
        if (instanceRef?.get() === this) instanceRef = null
        PhoneControlHost.detach(this)
    }

    companion object {
        @Volatile
        private var instanceRef: WeakReference<PhoneAccessibilityService>? = null

        /** 当前已连接的服务实例；没有则 null。弱引用：绝不延长服务生命周期。 */
        fun current(): PhoneAccessibilityService? = instanceRef?.get()?.takeIf { it.connected }
    }
}
