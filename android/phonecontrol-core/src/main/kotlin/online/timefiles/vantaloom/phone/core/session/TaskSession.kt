package online.timefiles.vantaloom.phone.core.session

import online.timefiles.vantaloom.phone.core.model.ErrorCode
import online.timefiles.vantaloom.phone.core.model.PhoneException
import online.timefiles.vantaloom.phone.core.ports.ActivityPhase
import online.timefiles.vantaloom.phone.core.ports.Clock
import online.timefiles.vantaloom.phone.core.ports.StatusSink
import online.timefiles.vantaloom.phone.core.ports.TaskControl
import online.timefiles.vantaloom.phone.core.ports.TaskState
import online.timefiles.vantaloom.phone.core.safety.Rules
import online.timefiles.vantaloom.phone.core.safety.TaskApprovals
import online.timefiles.vantaloom.phone.core.tools.Budgets
import java.util.Random

/**
 * 任务快照（status 工具、调试控制台用）。只读副本，拿到手之后不会再变。
 */
data class TaskInfo(
    val state: TaskState,
    val taskId: String?,
    val startedAtMs: Long,
    val actionCount: Int,
    val maxActions: Int,
    /** 距离空闲超时还剩多少毫秒；没有活动任务时为 -1。 */
    val idleRemainingMs: Long,
    val pendingNotes: Int,
    /** 上一个任务结束的原因（idle / 用户停止 / 动作数超限 …），从没结束过为 null。 */
    val lastEndReason: String?,
)

/**
 * 协议 §9 的任务会话。实现 [TaskControl]（给浮层/通知按钮）和 [TaskApprovals]（给 SafetyGate 查「本任务内都允许」）。
 *
 * ## 生命周期
 * - `NONE` → 第一次**动作类**调用（[beginAction]）自动开新任务，id = 时间戳 + 随机数，回调 [StatusSink.onTaskStarted]，浮层随之出现。
 * - `RUNNING` ⇄ `PAUSED`：用户在浮层上暂停/恢复。暂停期间所有受门控的调用返回 PAUSED（可安全重试）。
 * - 结束（回到 `NONE`）：空闲超过 [Budgets.taskIdleMs]、动作数超过 [Budgets.taskMaxActions]、[endTask]（如接手被放弃、服务关闭）。
 * - `STOPPED`：用户主动停止。**语义（刻意设计成「一次性」）**：
 *   1. 停止后，下一次**受门控**的调用（`status`、`apps` 以外的任何工具）返回 STOPPED **一次**，同时清除停止态回到 `NONE`；
 *   2. 再下一次动作类调用才开新任务（重新出浮层、重新走首次触达确认）。
 *
 *   为什么不让 STOPPED 一直持续到「新任务」：MCP 是无状态的，设备侧根本分不清「agent 的下一轮新任务」和「同一个任务的残余调用」。
 *   一次性 STOPPED 保证模型**至少看到一次**停止信号（协议要求它立刻停手汇报），又不会让手机永久卡死在停止态、
 *   非得用户去设置里点一下才能再用。`status`/`apps` 不消耗这次信号：模型查状态时应该看到 `已停止`，而不是把信号吃掉。
 *   正在执行中的动作被停止时，[checkpoint] 抛出的那次 STOPPED 就算是这一次。
 *
 * 动作数超限时同样直接结束任务并抛一次 STOPPED（消耗掉，不再进入停止态）。
 *
 * ## 线程
 * 所有状态在 [lock] 下读写；回调 [StatusSink] 一律在锁外执行（浮层实现可能同步回调 [pause]/[stop]，
 * 在锁里回调会有重入/死锁风险）。
 */
class TaskSession(
    private val clock: Clock,
    private val status: StatusSink,
    budgets: Budgets,
    private val random: Random = Random(),
) : TaskControl, TaskApprovals {

    /** 预算可在运行时替换（测试调小空闲超时）。 */
    @Volatile
    var budgets: Budgets = budgets

    private val lock = Any()
    private var state = TaskState.NONE
    private var taskId: String? = null
    private var startedAtMs = 0L
    private var lastCallAtMs = 0L
    private var actionCount = 0
    private var stopReason: String? = null
    private var lastEndReason: String? = null
    private val notes = ArrayDeque<String>()
    private val approvedPackages = HashSet<String>()
    private val approvedRules = HashSet<String>()
    private val touchedPackages = HashSet<String>()
    /** 正在执行的调用数。确认卡（30 s）、接手（最长 600 s）期间不能被判空闲。 */
    private var inFlight = 0

    // ---------------------------------------------------------------- 引擎调用

    /** 引擎在每次受门控调用开始时调用；与 [callFinished] 成对。 */
    fun callStarted() {
        synchronized(lock) { inFlight++ }
    }

    /** 调用结束：刷新空闲计时（长时间阻塞的接手结束后，空闲从此刻算起）。 */
    fun callFinished() {
        synchronized(lock) {
            if (inFlight > 0) inFlight--
            if (state == TaskState.RUNNING || state == TaskState.PAUSED) lastCallAtMs = clock.nowMs()
        }
    }

    /** 当前任务 id（运行或暂停中）；没有任务时为 null。 */
    fun currentTaskId(): String? = synchronized(lock) { if (state == TaskState.RUNNING || state == TaskState.PAUSED) taskId else null }

    /**
     * 惰性空闲检查：活动任务超过 [Budgets.taskIdleMs] 没有任何调用就结束。
     * 引擎在每次调用开头调一次，另有后台守护线程定期调，保证 agent 不再调用时浮层也能按时消失。
     * @return 本次是否结束了一个任务
     */
    fun sweepIdle(): Boolean {
        val ended: Pair<String, String>?
        synchronized(lock) {
            ended = if ((state == TaskState.RUNNING || state == TaskState.PAUSED) && inFlight == 0 &&
                clock.nowMs() - lastCallAtMs >= budgets.taskIdleMs
            ) endLocked("空闲超时") else null
        }
        ended?.let { status.onTaskEnded(it.first, it.second) }
        return ended != null
    }

    /**
     * 会话门（协议 §6 第 1 条）：受门控的调用在真正执行前先过这里。
     * STOPPED → 抛一次 STOPPED 并清除停止态（见类注释）；PAUSED → 抛 PAUSED。
     */
    fun admit() {
        synchronized(lock) {
            when (state) {
                TaskState.STOPPED -> throw consumeStopLocked()
                TaskState.PAUSED -> {
                    lastCallAtMs = clock.nowMs()
                    throw PhoneException(ErrorCode.PAUSED, "用户暂停了任务，等用户在手机上点「继续」后再试")
                }
                else -> Unit
            }
        }
    }

    /**
     * 动作类调用开始：没有任务就开新任务；计数；超过上限就结束任务并抛 STOPPED。
     * 调用前应已经过 [admit]。
     * @return 任务 id（非空）
     */
    fun beginAction(tool: String): String {
        var started: String? = null
        var ended: Pair<String, String>? = null
        val failure: PhoneException?
        val id: String?
        synchronized(lock) {
            when (state) {
                TaskState.STOPPED -> throw consumeStopLocked()
                TaskState.PAUSED -> throw PhoneException(ErrorCode.PAUSED, "用户暂停了任务，等用户在手机上点「继续」后再试")
                TaskState.NONE -> started = startLocked()
                TaskState.RUNNING -> Unit
            }
            lastCallAtMs = clock.nowMs()
            actionCount++
            if (actionCount > budgets.taskMaxActions) {
                ended = endLocked("动作数超过 ${budgets.taskMaxActions}")
                failure = PhoneException(
                    ErrorCode.STOPPED,
                    "本任务的动作数已超过 ${budgets.taskMaxActions} 个上限，任务已结束；请停下来向用户汇报",
                    mapOf("limit" to budgets.taskMaxActions.toLong(), "tool" to tool),
                )
                id = null
            } else {
                failure = null
                id = taskId
            }
        }
        started?.let { status.onTaskStarted(it) }
        ended?.let { status.onTaskEnded(it.first, it.second) }
        if (failure != null) throw failure
        return id!!
    }

    /** 观察类调用：刷新空闲计时，返回当前任务 id（可能为空）。不开新任务。 */
    fun touch(): String? = synchronized(lock) {
        if (state == TaskState.RUNNING || state == TaskState.PAUSED) {
            lastCallAtMs = clock.nowMs()
            taskId
        } else null
    }

    /**
     * 执行中途的检查点（确认卡回来后、长循环每一步）：
     * - 任务被停止，或已经不是开始执行时的那个任务 → 抛 STOPPED（消耗停止态）；
     * - 被暂停 → 抛 PAUSED（动作尚未发出，可安全重试）。
     * [expectedTaskId] 为 null 表示调用开始时没有任务（观察类），只检查停止态。
     */
    fun checkpoint(expectedTaskId: String?) {
        synchronized(lock) {
            when {
                state == TaskState.STOPPED -> throw consumeStopLocked()
                expectedTaskId != null && (state == TaskState.NONE || taskId != expectedTaskId) ->
                    throw PhoneException(ErrorCode.STOPPED, "任务已结束（${lastEndReason ?: "未知原因"}），请停下来向用户汇报")
                state == TaskState.PAUSED ->
                    throw PhoneException(ErrorCode.PAUSED, "用户暂停了任务，等用户在手机上点「继续」后再试")
                else -> Unit
            }
        }
    }

    /** 取出并清空用户留言（协议 §9：拼进下一次工具结果开头）。 */
    fun drainNotes(): List<String> = synchronized(lock) {
        if (notes.isEmpty()) emptyList() else notes.toList().also { notes.clear() }
    }

    /** 结束当前任务回到 NONE（不进入停止态）：接手被放弃、服务关闭等。 */
    fun endTask(reason: String) {
        val ended = synchronized(lock) {
            if (state == TaskState.RUNNING || state == TaskState.PAUSED) endLocked(reason) else null
        }
        ended?.let { status.onTaskEnded(it.first, it.second) }
    }

    /** 当前状态快照。 */
    fun info(): TaskInfo = synchronized(lock) {
        val active = state == TaskState.RUNNING || state == TaskState.PAUSED
        TaskInfo(
            state = state,
            taskId = if (active) taskId else null,
            startedAtMs = if (active) startedAtMs else 0L,
            actionCount = if (active) actionCount else 0,
            maxActions = budgets.taskMaxActions,
            idleRemainingMs = if (active) (budgets.taskIdleMs - (clock.nowMs() - lastCallAtMs)).coerceAtLeast(0) else -1,
            pendingNotes = notes.size,
            lastEndReason = if (state == TaskState.STOPPED) stopReason else lastEndReason,
        )
    }

    // ---------------------------------------------------------------- TaskControl（浮层 → core）

    override fun pause() {
        val id = synchronized(lock) {
            if (state != TaskState.RUNNING) return
            state = TaskState.PAUSED
            taskId
        }
        id?.let { status.onActivity(it, ActivityPhase.PAUSED, "已暂停，点「继续」恢复") }
    }

    override fun resume() {
        val id = synchronized(lock) {
            if (state != TaskState.PAUSED) return
            state = TaskState.RUNNING
            // 恢复时重置空闲计时：用户暂停期间 agent 可能一直在等，不该一恢复就被判空闲。
            lastCallAtMs = clock.nowMs()
            taskId
        }
        id?.let { status.onActivity(it, ActivityPhase.IDLE, "已恢复，等 agent 下一步") }
    }

    override fun stop(reason: String) {
        val ended = synchronized(lock) {
            if (state != TaskState.RUNNING && state != TaskState.PAUSED) return
            val id = taskId ?: ""
            clearTaskLocked()
            state = TaskState.STOPPED
            stopReason = reason
            lastEndReason = reason
            id to reason
        }
        status.onTaskEnded(ended.first, ended.second)
    }

    override fun leaveNote(text: String) {
        val clean = text.trim().replace(Regex("\\s+"), " ").take(MAX_NOTE_CHARS)
        if (clean.isEmpty()) return
        synchronized(lock) {
            // 队列有上限：用户连点也不会把结果撑爆；丢最旧的，最新的意图最重要。
            while (notes.size >= MAX_NOTES) notes.removeFirst()
            notes.addLast(clean)
        }
    }

    override fun state(): TaskState = synchronized(lock) { state }

    // ---------------------------------------------------------------- TaskApprovals（给 SafetyGate）

    override fun packageApproved(pkg: String): Boolean = synchronized(lock) { pkg in approvedPackages }

    override fun ruleApproved(rule: String, pkg: String?): Boolean = synchronized(lock) {
        ruleKey(rule, pkg) in approvedRules || ruleKey(rule, null) in approvedRules
    }

    override fun firstTouchDone(pkg: String): Boolean = synchronized(lock) { pkg in touchedPackages }

    /**
     * 用户在确认卡上选了「本任务内都允许」（规则名一律用 [Rules] 常量，不靠字符串猜）：
     * - [Rules.FIRST_TOUCH_PACKAGE]：记该包本任务已首次确认（[firstTouchDone]）。刻意**不**记整包放行
     *   （[packageApproved]）：那会让同一个包里的敏感词（支付/删除/发送）确认也被跳过。
     * - [Rules.SENSITIVE_WORD] / [Rules.SUBMIT_TEXT] / [Rules.DEEP_LINK] / [Rules.OUTBOUND_INTENT]：记 (规则, 包)。
     * - [Rules.COORDINATE_ACTION] / [Rules.CONFIRM_EVERY_WRITE] 及其它：永不允许 task-wide，忽略。
     */
    fun recordTaskApproval(rule: String, pkg: String?) {
        synchronized(lock) {
            when (rule) {
                Rules.FIRST_TOUCH_PACKAGE -> if (pkg != null) touchedPackages += pkg
                in TASK_WIDE_RULES -> approvedRules += ruleKey(rule, pkg)
                else -> Unit
            }
        }
    }

    /** 显式记整包「本任务内都允许」（确认模式 task）。 */
    fun recordPackageApproved(pkg: String) {
        synchronized(lock) { approvedPackages += pkg }
    }

    /** 本任务已经对这个包动过手（首次触达确认已经过了）。 */
    fun recordTouched(pkg: String) {
        synchronized(lock) { touchedPackages += pkg }
    }

    // ---------------------------------------------------------------- 内部

    private fun startLocked(): String {
        clearTaskLocked()
        val now = clock.nowMs()
        val id = "$now-" + Integer.toHexString(random.nextInt(0x1000000) or 0x1000000).substring(1)
        taskId = id
        state = TaskState.RUNNING
        startedAtMs = now
        lastCallAtMs = now
        actionCount = 0
        stopReason = null
        return id
    }

    /** 结束任务回到 NONE，返回 (id, 原因) 供锁外回调。 */
    private fun endLocked(reason: String): Pair<String, String> {
        val id = taskId ?: ""
        clearTaskLocked()
        state = TaskState.NONE
        lastEndReason = reason
        return id to reason
    }

    private fun clearTaskLocked() {
        taskId = null
        actionCount = 0
        approvedPackages.clear()
        approvedRules.clear()
        touchedPackages.clear()
    }

    private fun consumeStopLocked(): PhoneException {
        val reason = stopReason ?: "用户停止"
        state = TaskState.NONE
        stopReason = null
        return PhoneException(
            ErrorCode.STOPPED,
            "用户已停止任务（$reason），请立刻停手并向用户汇报，不要继续操作",
            mapOf("reason" to reason),
        )
    }

    private fun ruleKey(rule: String, pkg: String?) = rule + "\u0000" + (pkg ?: "*")

    private companion object {
        const val MAX_NOTES = 20
        const val MAX_NOTE_CHARS = 500

        /** 选「本任务内都允许」后按 (规则, 包) 记住的规则。 */
        val TASK_WIDE_RULES: Set<String> = setOf(Rules.SENSITIVE_WORD, Rules.SUBMIT_TEXT, Rules.DEEP_LINK, Rules.OUTBOUND_INTENT)
    }
}
