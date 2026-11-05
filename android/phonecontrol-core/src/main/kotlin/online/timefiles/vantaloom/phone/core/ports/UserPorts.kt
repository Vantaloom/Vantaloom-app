package online.timefiles.vantaloom.phone.core.ports

/**
 * core → 用户界面（设备浮层）的接缝。:android 的 ControlOverlay 实现它们；JVM 测试用脚本化的假实现。
 * 所有阻塞方法都在工具执行线程上调用，实现负责切到主线程画 UI 并阻塞等待结果。
 */
interface ConfirmationPort {
    /**
     * 弹确认卡并阻塞到用户选择或超时。实现必须用 GuardedButton（协议 §8）只认真实触摸。
     * 任务被停止时应尽快返回 [ConfirmDecision.CANCELLED]。
     */
    fun confirm(request: ConfirmRequest, timeoutMs: Long): ConfirmDecision
}

data class ConfirmRequest(
    val taskId: String,
    /** 工具名（tap/type/…） */
    val tool: String,
    /** 给人看的一句话：「点击 微信 里的按钮「发送」」 */
    val summary: String,
    val appLabel: String?,
    val packageName: String?,
    /** 为什么要确认（规则名的人话）：「含敏感词「发送」」 */
    val reason: String,
    /** 目标在屏幕上的位置（可空），浮层可以画个高亮框。 */
    val targetBounds: online.timefiles.vantaloom.phone.core.model.Rect?,
    /** 是否允许「本任务内都允许」（坐标动作等每次必问的为 false）。 */
    val allowTaskWide: Boolean,
    /** 已经是不是远端会话（sharing）发起的。仅展示用。 */
    val remoteCaller: String? = null,
)

enum class ConfirmDecision { ALLOW_ONCE, ALLOW_TASK, REJECT, TIMEOUT, CANCELLED }

interface HandoffPort {
    /** 请用户接手，阻塞到「继续」「放弃」或超时。 */
    fun handoff(request: HandoffRequest, timeoutMs: Long): HandoffResult
}

data class HandoffRequest(val taskId: String, val reason: String, val kind: HandoffKind)

enum class HandoffKind(val wire: String) {
    LOGIN("login"), CAPTCHA("captcha"), PAYMENT("payment"), CONFIRM("confirm"), OTHER("other");

    companion object {
        fun fromWire(v: String?): HandoffKind = entries.firstOrNull { it.wire == v } ?: OTHER
    }
}

enum class HandoffResult { RESUMED, ABANDONED, TIMEOUT, CANCELLED }

/**
 * 任务状态 → 浮层。全部是 fire-and-forget，不阻塞。
 * 浮层在 [onTaskStarted] 时出现，在 [onTaskEnded] 时消失。
 */
interface StatusSink {
    fun onTaskStarted(taskId: String)
    fun onActivity(taskId: String, phase: ActivityPhase, line: String)
    fun onTaskEnded(taskId: String, reason: String)
    /** 动作执行期间浮层要让开（FLAG_NOT_TOUCHABLE + 隐藏大块）。 */
    fun setStandAside(standAside: Boolean)
}

enum class ActivityPhase { OBSERVING, ACTING, WAITING_CONFIRM, WAITING_USER, PAUSED, IDLE }

/**
 * 浮层 → core：用户的控制意图。core 的 TaskSession 实现它，浮层和通知按钮调用它。
 */
interface TaskControl {
    fun pause()
    fun resume()
    fun stop(reason: String)
    /** 用户给 agent 的留言，拼进下一次工具结果。 */
    fun leaveNote(text: String)
    fun state(): TaskState
}

enum class TaskState { NONE, RUNNING, PAUSED, STOPPED }

/** 可替换的时钟（测试里用假时钟推进空闲超时、武装期等）。 */
interface Clock {
    fun nowMs(): Long
    fun sleep(ms: Long)

    object System : Clock {
        override fun nowMs(): Long = java.lang.System.currentTimeMillis()
        override fun sleep(ms: Long) { if (ms > 0) Thread.sleep(ms) }
    }
}
