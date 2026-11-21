package online.timefiles.vantaloom.phone.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import online.timefiles.vantaloom.phone.BuildConfig
import online.timefiles.vantaloom.phone.core.ports.Clock

/**
 * 只接受真实手指触摸的按钮（协议 §8）。确认卡、接手卡上的全部按钮都用它。
 *
 * 为什么要这么严：agent 能注入手势（dispatchGesture）、能发无障碍 ACTION_CLICK，也可能借别的
 * 悬浮窗做点击劫持。确认卡是写操作的权威人工门，如果 agent 能替自己点「允许」，整套安全模型就没了。
 * 所以以下任何一条不满足都**静默丢弃**（不给反馈，免得被当作探测信号）：
 *
 *  1. 武装期：按钮可见超过 [ARM_MS]（以 [arm] 时刻起算，SystemClock.uptimeMillis 时基——与 MotionEvent 同一时基）。
 *  2. 不在本服务注入窗口期：注入前后 [INJECTION_WINDOW_MS] 内（[lastInjectionAtMs] 走 core 的 Clock 时基，单独比较）。
 *  3. 没有 FLAG_WINDOW_IS_OBSCURED / FLAG_WINDOW_IS_PARTIALLY_OBSCURED（后者 API 29+）。
 *  4. deviceId > 0 且来源是 SOURCE_TOUCHSCREEN（注入事件 deviceId 常为 -1/0，或来源是虚拟设备）。
 *  5. 完整的 DOWN→UP，按下时长 [MIN_PRESS_MS]..[MAX_PRESS_MS]，期间没有 CANCEL、没有第二根手指、没滑出按钮。
 *  6. performClick() / performAccessibilityAction(ACTION_CLICK) 从外部调用一律返回 false，不触发回调。
 *
 * 回调只经由 [onGuardedClick] 触发，绝不走 View.OnClickListener（那条路可以被 performClick 触发）。
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility", "AppCompatCustomView")
class GuardedButton(
    context: Context,
    private val clock: Clock,
    private val lastInjectionAtMs: () -> Long,
    /**
     * 是否容忍 FLAG_WINDOW_IS_PARTIALLY_OBSCURED。只给「留言」窗口的发送键用：输入法窗口（其他 UID）弹起时
     * 必然和留言窗口重叠，严格模式下永远点不了。确认卡/接手卡一律严格（false）。
     * 完全遮挡（FLAG_WINDOW_IS_OBSCURED，即触点正上方有别的窗口）无论如何都拒绝。
     */
    private val allowPartialObscure: Boolean = false,
) : Button(context) {

    /** 通过全部判定后的回调。 */
    var onGuardedClick: (() -> Unit)? = null

    /** 被拒绝的触摸次数（demo 与调试用；也可上报审计）。 */
    var rejectedCount: Int = 0
        private set

    /** 最近一次被拒的原因（调试用）。 */
    var lastRejectReason: String? = null
        private set

    private var armedAtUptime: Long = Long.MAX_VALUE
    private var downAtUptime: Long = -1L
    private var tracking = false

    init {
        // 系统级防遮挡：被别的窗口盖住时系统直接不分发触摸。我们自己还会再查一遍标志位。
        filterTouchesWhenObscured = true
        isHapticFeedbackEnabled = true
        // 保持「可点击」以便读屏用户知道这是按钮；但无障碍点击被 performAccessibilityAction 拦掉。
        isClickable = true
        isFocusable = true
    }

    /** 卡片显示（或重新显示）时调用：从此刻起计武装期。 */
    fun arm() {
        armedAtUptime = SystemClock.uptimeMillis()
        tracking = false
        downAtUptime = -1L
    }

    /** 卡片隐藏时调用：之后的触摸一律无效，直到再次 [arm]。 */
    fun disarm() {
        armedAtUptime = Long.MAX_VALUE
        tracking = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 附着即武装：即使调用方忘了 arm，也至少有 500ms 武装期。
        if (armedAtUptime == Long.MAX_VALUE) arm()
    }

    override fun onDetachedFromWindow() {
        disarm()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // 窗口从不可见变可见（浮层截图前隐藏后恢复）要重新武装：防止「隐藏→注入→显示」的时序攻击。
        if (visibility == View.VISIBLE) arm() else disarm()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val reason = rejectReason(event, isDown = true)
                if (reason != null) {
                    reject(reason)
                    tracking = false
                    return true // 吞掉，不让父布局拿去做别的
                }
                tracking = true
                downAtUptime = event.eventTime
                isPressed = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return true
                if (!inside(event)) {
                    tracking = false
                    isPressed = false
                    reject("滑出按钮")
                } else {
                    rejectReason(event, isDown = false)?.let {
                        tracking = false
                        isPressed = false
                        reject(it)
                    }
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (tracking) reject("多指")
                tracking = false
                isPressed = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                tracking = false
                isPressed = false
                return true
            }
            MotionEvent.ACTION_UP -> {
                isPressed = false
                if (!tracking) return true
                tracking = false
                val reason = rejectReason(event, isDown = false)
                    ?: pressDurationReason(event.eventTime - downAtUptime)
                    ?: if (!inside(event)) "抬起位置在按钮外" else null
                if (reason != null) {
                    reject(reason)
                    return true
                }
                performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                onGuardedClick?.invoke()
                return true
            }
        }
        return true
    }

    private fun rejectReason(event: MotionEvent, isDown: Boolean): String? {
        val nowUptime = SystemClock.uptimeMillis()
        if (isDown && nowUptime - armedAtUptime < ARM_MS) return "武装期内"
        if (armedAtUptime == Long.MAX_VALUE) return "未武装"
        val inj = lastInjectionAtMs()
        if (inj > 0 && kotlin.math.abs(clock.nowMs() - inj) < INJECTION_WINDOW_MS) return "注入窗口期"
        val flags = event.flags
        if ((flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED) != 0) return "窗口被遮挡"
        if (!allowPartialObscure && Build.VERSION.SDK_INT >= 29 &&
            (flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0
        ) {
            return "窗口被部分遮挡"
        }
        if (event.deviceId <= 0) return "deviceId=${event.deviceId}"
        if (!event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) return "来源不是触摸屏"
        if (event.pointerCount != 1) return "多指"
        return null
    }

    private fun pressDurationReason(ms: Long): String? = when {
        downAtUptime < 0 -> "没有按下"
        ms < MIN_PRESS_MS -> "按下太短 ${ms}ms"
        ms > MAX_PRESS_MS -> "按下太长 ${ms}ms"
        else -> null
    }

    private fun inside(event: MotionEvent): Boolean =
        event.x >= 0 && event.y >= 0 && event.x <= width && event.y <= height

    private fun reject(reason: String) {
        rejectedCount++
        lastRejectReason = reason
        if (BuildConfig.DEBUG) Log.d(TAG, "拒绝触摸「$text」：$reason（累计 $rejectedCount）")
    }

    /** 外部（含无障碍、agent、测试）的 performClick 一律无效。 */
    override fun performClick(): Boolean {
        reject("performClick")
        return false
    }

    /** 无障碍 ACTION_CLICK / LONG_CLICK 一律返回 false（协议 §8）。其余动作（聚焦、朗读）照常。 */
    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action == AccessibilityNodeInfo.ACTION_CLICK || action == AccessibilityNodeInfo.ACTION_LONG_CLICK) {
            reject("无障碍点击")
            return false
        }
        return super.performAccessibilityAction(action, arguments)
    }

    override fun performLongClick(): Boolean {
        reject("performLongClick")
        return false
    }

    companion object {
        private const val TAG = "VtlGuardedButton"

        /** 武装期。 */
        const val ARM_MS = 500L

        /** 本服务注入手势前后的屏蔽窗口。 */
        const val INJECTION_WINDOW_MS = 300L

        /** 合法按下时长下限：注入的点击通常 0–10ms。 */
        const val MIN_PRESS_MS = 50L

        /** 合法按下时长上限：放在按钮上不动 5 秒不算点击（防「压住等武装」）。 */
        const val MAX_PRESS_MS = 5_000L
    }
}
