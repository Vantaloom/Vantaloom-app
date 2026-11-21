package online.timefiles.vantaloom.phone.core

import online.timefiles.vantaloom.phone.core.driver.DriverAvailability
import online.timefiles.vantaloom.phone.core.driver.ScreenDriver
import online.timefiles.vantaloom.phone.core.mcp.Base64Codec
import online.timefiles.vantaloom.phone.core.mcp.JsonValues
import online.timefiles.vantaloom.phone.core.model.DisplayKind
import online.timefiles.vantaloom.phone.core.model.ErrorCode
import online.timefiles.vantaloom.phone.core.model.PhoneException
import online.timefiles.vantaloom.phone.core.model.Rect
import online.timefiles.vantaloom.phone.core.observe.ScreenRegistry
import online.timefiles.vantaloom.phone.core.ports.ActivityPhase
import online.timefiles.vantaloom.phone.core.ports.Clock
import online.timefiles.vantaloom.phone.core.ports.ConfirmDecision
import online.timefiles.vantaloom.phone.core.ports.ConfirmRequest
import online.timefiles.vantaloom.phone.core.ports.ConfirmationPort
import online.timefiles.vantaloom.phone.core.ports.HandoffPort
import online.timefiles.vantaloom.phone.core.ports.StatusSink
import online.timefiles.vantaloom.phone.core.ports.TaskControl
import online.timefiles.vantaloom.phone.core.safety.ActionIntent
import online.timefiles.vantaloom.phone.core.safety.ConfirmMode
import online.timefiles.vantaloom.phone.core.safety.Rules
import online.timefiles.vantaloom.phone.core.safety.GateDecision
import online.timefiles.vantaloom.phone.core.safety.SafetyGate
import online.timefiles.vantaloom.phone.core.safety.SafetyPolicy
import online.timefiles.vantaloom.phone.core.safety.TaskApprovals
import online.timefiles.vantaloom.phone.core.session.AuditEntry
import online.timefiles.vantaloom.phone.core.session.AuditLog
import online.timefiles.vantaloom.phone.core.session.TaskInfo
import online.timefiles.vantaloom.phone.core.session.TaskSession
import online.timefiles.vantaloom.phone.core.tools.Budgets
import online.timefiles.vantaloom.phone.core.tools.PhoneTool
import online.timefiles.vantaloom.phone.core.tools.ToolArgs
import online.timefiles.vantaloom.phone.core.tools.ToolKind
import online.timefiles.vantaloom.phone.core.tools.ToolResult
import online.timefiles.vantaloom.phone.core.tools.impl.BaseTool
import online.timefiles.vantaloom.phone.core.tools.impl.BuiltinTools
import online.timefiles.vantaloom.phone.core.tools.impl.EngineContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * 一次 tools/call 的编码结果（MCP CallToolResult 的三个字段）。
 * [content] 是 text/image 块；[structured] 是 structuredContent；全部已归一成 Json 可序列化的类型。
 */
data class McpToolResult(val content: List<Map<String, Any?>>, val structured: Map<String, Any?>, val isError: Boolean)

/**
 * 编排层（CONTRACTS.md 「A2 → B」）。一次调用的流程：
 *
 * 1. 查工具（未知 → INVALID_ARGS 结果；JSON-RPC 层会先拦一道）、校验参数名。
 * 2. 会话门：`status`/`apps` 不受限；其余先过 STOPPED/PAUSED（[TaskSession.admit]），再查锁屏、无障碍服务。
 * 3. 串行：动作类拿动作锁（`tryLock(actionMs)`，拿不到 → TIMEOUT）。观察类「排在当前动作之后」：
 *    先试着等锁 observeMs，等到就立刻释放再执行（看到动作完成后的屏幕）；等不到（动作卡在确认卡上）
 *    就并发执行，而不是让 screen 跟着卡 30 秒。
 * 4. 动作类（[BaseTool.startsTask]）计数/自动开任务，并确认浮层在场。
 * 5. 执行工具；结果开头拼用户留言；写审计；编码成 [McpToolResult]。
 *
 * 异常：[PhoneException] 按协议 §5 编码；其余 Throwable → INTERNAL（details 只带异常类名，不带堆栈，
 * 防止把设备上的路径/文本泄露给远端）。[call] 永不抛异常。
 *
 * 预算：等用户（确认卡、接手）的时间不计入。无法强行中断一个正在执行的平台调用，所以预算靠工具在
 * 检查点自查（[EngineContext.checkpoint] 超预算抛 TIMEOUT），以及驱动自己尊重 timeoutMs。
 */
class PhoneEngine(
    drivers: Map<DisplayKind, ScreenDriver>,
    private val confirmation: ConfirmationPort,
    private val handoff: HandoffPort,
    private val status: StatusSink,
    private val ownPackage: String,
    policy: SafetyPolicy = SafetyPolicy.defaults(),
    private val audit: AuditLog = AuditLog.NONE,
    private val clock: Clock = Clock.System,
    private val budgets: Budgets = Budgets(),
) {
    private val drivers: Map<DisplayKind, ScreenDriver> = HashMap(drivers)
    private val registry = ScreenRegistry(clock)
    private val session = TaskSession(clock, status, budgets)

    @Volatile
    private var gate = SafetyGate(policy, ownPackage)

    /** 动作串行锁。公平锁：先到先执行，模型并发发来的动作按到达顺序落地。 */
    private val actionLock = ReentrantLock(true)

    private val toolList: List<BaseTool> = BuiltinTools.create()
    private val toolsByName: Map<String, BaseTool> = toolList.associateBy { it.name }

    @Volatile
    private var shutDown = false
    private val sweeper: Thread

    /** 给浮层/通知按钮的控制口。 */
    val control: TaskControl get() = session

    init {
        // 空闲守护：agent 不再调用时也要按时结束任务、收起浮层（协议 §9 的 3 分钟）。
        // 间隔用真实时间：假时钟的 sleep 可能立刻返回，会变成忙等；判定本身仍走 Clock，测试里直接调
        // session.sweepIdle 或依赖每次调用开头的惰性检查即可。
        sweeper = Thread({
            while (!shutDown) {
                try {
                    Thread.sleep(SWEEP_INTERVAL_MS)
                    session.sweepIdle()
                } catch (_: InterruptedException) {
                    break
                } catch (_: Throwable) {
                    // 浮层回调抛异常不能杀掉守护线程
                }
            }
        }, "vantaloom-phone-idle").apply { isDaemon = true; start() }
    }

    /** 协议 §4 的 16 个工具，固定顺序。 */
    fun tools(): List<PhoneTool> = toolList

    /** 任务快照（调试控制台用）。 */
    fun taskInfo(): TaskInfo = session.info()

    /** 替换安全策略（用户在设置里改了规则）。对之后的调用生效，正在执行的用旧策略。 */
    fun updatePolicy(policy: SafetyPolicy) {
        gate = SafetyGate(policy, ownPackage)
    }

    /** 服务关闭：停守护线程、结束任务（浮层收起）。之后的 [call] 一律 SERVICE_DISABLED。 */
    fun shutdown() {
        shutDown = true
        sweeper.interrupt()
        session.endTask("服务关闭")
    }

    /** 线程安全；动作类串行。永不抛异常。 */
    fun call(name: String, arguments: Map<String, Any?>, remoteCaller: String? = null): McpToolResult {
        val startedAt = clock.nowMs()
        val tool = toolsByName[name]
        val args = ToolArgs(arguments)
        var taskId: String? = null
        val trace = CallTrace()
        val outcome: Result<ToolResult> = runCatching {
            if (tool == null) {
                throw PhoneException(ErrorCode.INVALID_ARGS, "未知工具 $name；可用：${toolList.joinToString(", ") { it.name }}")
            }
            if (shutDown) throw PhoneException(ErrorCode.SERVICE_DISABLED, "手机控制服务已关闭")
            session.sweepIdle()
            tool.validate(args)
            if (!tool.gated) {
                taskId = session.touch()
                runTool(tool, args, taskId, remoteCaller, trace)
            } else {
                runGated(tool, args, remoteCaller, trace) { taskId = it }
            }
        }
        val notes = session.drainNotes()
        val result = outcome.fold(
            onSuccess = { encodeSuccess(name, it, notes) },
            onFailure = { encodeError(name, it, notes) },
        )
        val code = outcome.exceptionOrNull()?.let { (it as? PhoneException)?.code?.wire ?: ErrorCode.INTERNAL.wire } ?: "OK"
        // AuditLog 契约是永不抛异常；这里再兜一层，保证 call() 本身永不抛。
        try {
            audit.record(
                AuditEntry(
                    timeMs = startedAt,
                    taskId = taskId ?: session.currentTaskId(),
                    tool = name,
                    args = safeSummary(args),
                    decision = trace.decision(),
                    code = code,
                    durationMs = clock.nowMs() - startedAt,
                    remoteCaller = remoteCaller,
                ),
            )
        } catch (_: Throwable) {
            // 审计尽力而为
        }
        return result
    }

    // ---------------------------------------------------------------- 执行

    private fun runGated(tool: BaseTool, args: ToolArgs, remoteCaller: String?, trace: CallTrace, onTask: (String?) -> Unit): ToolResult {
        session.callStarted()
        try {
            session.admit()
            checkDevice(tool)
            return if (tool.serialized) {
                if (!actionLock.tryLock(budgets.actionMs, TimeUnit.MILLISECONDS)) {
                    throw PhoneException(ErrorCode.TIMEOUT, "上一个动作还没结束（等了 ${budgets.actionMs}ms），稍后再试")
                }
                try {
                    val taskId = if (tool.startsTask) beginTask(tool) else session.touch()
                    onTask(taskId)
                    runTool(tool, args, taskId, remoteCaller, trace)
                } finally {
                    actionLock.unlock()
                }
            } else {
                // 观察类排在当前动作之后（见类注释）。
                if (actionLock.tryLock(budgets.observeMs, TimeUnit.MILLISECONDS)) actionLock.unlock()
                val taskId = session.touch()
                onTask(taskId)
                runTool(tool, args, taskId, remoteCaller, trace)
            }
        } finally {
            session.callFinished()
        }
    }

    /** 动作类：开任务/计数，然后确认浮层在场（浮层在 onTaskStarted 时才出现，所以要在开任务之后查）。 */
    private fun beginTask(tool: BaseTool): String {
        val taskId = session.beginAction(tool.name)
        if (tool.needsOverlay) awaitOverlay(taskId)
        return taskId
    }

    /**
     * 不变量：没有浮层就不许动作——浮层是用户看到 agent 在做什么、并能一键停止的唯一入口。
     * StatusSink 是 fire-and-forget，浮层可能在主线程上稍后才挂好，所以短暂等一下。
     */
    private fun awaitOverlay(taskId: String) {
        val main = drivers[DisplayKind.MAIN] ?: return
        val deadline = clock.nowMs() + OVERLAY_WAIT_MS
        while (true) {
            val a = main.availability()
            if (a.overlayAttached) return
            if (clock.nowMs() >= deadline) {
                session.endTask("浮层未能显示")
                throw PhoneException(
                    ErrorCode.SERVICE_DISABLED,
                    "手机上的控制浮层没有显示，为了让用户随时能停止，浮层不在时不执行动作",
                    mapOf("taskId" to taskId, "steps" to SERVICE_STEPS),
                )
            }
            clock.sleep(OVERLAY_POLL_MS)
        }
    }

    /** 设备门：无障碍服务、锁屏。只看主屏（后台屏的可用性由工具自己查）。 */
    private fun checkDevice(tool: BaseTool) {
        val main = drivers[DisplayKind.MAIN]
            ?: throw PhoneException(ErrorCode.SERVICE_DISABLED, "主屏驱动未接入", mapOf("steps" to SERVICE_STEPS))
        val a: DriverAvailability = main.availability()
        if (!a.serviceConnected) {
            throw PhoneException(
                ErrorCode.SERVICE_DISABLED,
                a.reason ?: "Vantaloom 无障碍服务未开启或被系统关闭",
                mapOf("steps" to SERVICE_STEPS),
            )
        }
        if (tool.needsScreen && (!a.screenOn || a.locked)) {
            throw PhoneException(
                ErrorCode.SCREEN_LOCKED,
                if (!a.screenOn) "手机已熄屏，请用户亮屏解锁后再试" else "手机已锁屏，请用户解锁后再试",
            )
        }
    }

    private fun runTool(tool: BaseTool, args: ToolArgs, taskId: String?, remoteCaller: String?, trace: CallTrace): ToolResult {
        val ctx = CallContext(tool, args, taskId, remoteCaller, trace)
        val phase = if (tool.kind == ToolKind.OBSERVE && !tool.startsTask) ActivityPhase.OBSERVING else ActivityPhase.ACTING
        if (taskId != null) status.onActivity(taskId, phase, tool.activityLine(args))
        try {
            return tool.call(args, ctx)
        } finally {
            if (taskId != null && session.currentTaskId() == taskId) status.onActivity(taskId, ActivityPhase.IDLE, "等 agent 下一步")
        }
    }

    // ---------------------------------------------------------------- 单次调用上下文

    /** 审计用的判定轨迹（一次调用可能过多次安全门，比如 type 先定位再提交）。 */
    private class CallTrace {
        private val parts = ArrayList<String>(2)
        @Synchronized fun add(s: String) { parts += s }
        @Synchronized fun decision(): String? = if (parts.isEmpty()) null else parts.joinToString(";")
    }

    private inner class CallContext(
        private val tool: BaseTool,
        private val args: ToolArgs,
        override val taskId: String?,
        override val remoteCaller: String?,
        private val trace: CallTrace,
    ) : EngineContext {
        private val startedAt = clock.nowMs()
        private val budgetMs = tool.budgetMs(budgets, args)
        private var userWaitMs = 0L

        override val clock: Clock get() = this@PhoneEngine.clock
        override val confirmation: ConfirmationPort get() = this@PhoneEngine.confirmation
        override val handoff: HandoffPort get() = this@PhoneEngine.handoff
        override val status: StatusSink get() = this@PhoneEngine.status
        override val budgets: Budgets get() = this@PhoneEngine.budgets
        override val registry: ScreenRegistry get() = this@PhoneEngine.registry
        override val gate: SafetyGate = this@PhoneEngine.gate // 一次调用内策略固定
        override val approvals: TaskApprovals get() = session
        override val ownPackage: String get() = this@PhoneEngine.ownPackage

        override fun driver(display: DisplayKind): ScreenDriver = drivers[display]
            ?: throw PhoneException(
                ErrorCode.UNSUPPORTED,
                if (display == DisplayKind.BACKGROUND) "后台虚拟屏尚未启用" else "没有 ${display.wire} 屏的驱动",
                if (display == DisplayKind.BACKGROUND) mapOf("requires" to listOf("Shizuku", "Android 14+")) else emptyMap(),
            )

        override fun availability(display: DisplayKind): DriverAvailability? = drivers[display]?.availability()
        override fun displays(): Set<DisplayKind> = drivers.keys
        override fun taskInfo(): TaskInfo = session.info()
        override fun endTask(reason: String) = session.endTask(reason)

        override fun remainingMs(): Long = budgetMs - (clock.nowMs() - startedAt - userWaitMs)

        override fun checkpoint() {
            session.checkpoint(taskId)
            if (remainingMs() <= 0) {
                throw PhoneException(ErrorCode.TIMEOUT, "${tool.name} 超出预算 ${budgetMs}ms，已停止后续步骤；界面可能已部分变化，请先 screen 确认")
            }
        }

        override fun <T> userWait(block: () -> T): T {
            val t0 = clock.nowMs()
            try {
                return block()
            } finally {
                userWaitMs += clock.nowMs() - t0
            }
        }

        override fun guard(intent: ActionIntent, summary: String, target: Rect?, forceConfirm: Boolean): Boolean {
            val pkg = intent.packageName
            val confirm: GateDecision.Confirm = when (val d = gate.evaluate(intent, session)) {
                is GateDecision.Deny -> {
                    trace.add("deny:${d.rule}")
                    throw PhoneException(ErrorCode.DENIED_POLICY, d.reason, mapOf("rule" to d.rule))
                }
                is GateDecision.Confirm -> if (forceConfirm) d.copy(allowTaskWide = false) else d
                GateDecision.Allow -> {
                    if (!forceConfirm) {
                        trace.add("allow")
                        pkg?.let { session.recordTouched(it) }
                        return false
                    }
                    GateDecision.Confirm("坐标动作没有语义可审，每次都要确认", Rules.COORDINATE_ACTION, false)
                }
            }
            // 「本任务内都允许」只在 task 模式下有效（ask 模式每次都问），其它模式不给这个按钮，免得选了也没用。
            val taskWide = confirm.allowTaskWide && gate.policy.modeFor(pkg) == ConfirmMode.TASK
            val id = taskId ?: ""
            status.onActivity(id, ActivityPhase.WAITING_CONFIRM, summary)
            val request = ConfirmRequest(
                taskId = id,
                tool = tool.name,
                summary = summary,
                appLabel = intent.screen?.snapshot?.foregroundLabel?.takeIf { intent.screen?.packageName == pkg },
                packageName = pkg,
                reason = confirm.reason,
                targetBounds = target,
                allowTaskWide = taskWide,
                remoteCaller = remoteCaller,
            )
            val decision = userWait { confirmation.confirm(request, budgets.confirmTimeoutMs) }
            trace.add("confirm:${confirm.rule}:${decision.name.lowercase()}")
            when (decision) {
                ConfirmDecision.ALLOW_ONCE -> Unit
                ConfirmDecision.ALLOW_TASK -> if (taskWide) session.recordTaskApproval(confirm.rule, pkg)
                ConfirmDecision.REJECT -> throw PhoneException(
                    ErrorCode.USER_REJECTED,
                    "用户在手机上拒绝了：$summary。不要换方式绕过，请向用户确认下一步",
                    mapOf("rule" to confirm.rule),
                )
                ConfirmDecision.TIMEOUT -> throw PhoneException(
                    ErrorCode.CONFIRM_TIMEOUT,
                    "手机上的确认卡 ${budgets.confirmTimeoutMs / 1000}s 没人选择，动作未执行",
                    mapOf("rule" to confirm.rule),
                )
                ConfirmDecision.CANCELLED -> {
                    session.checkpoint(taskId)
                    throw PhoneException(ErrorCode.STOPPED, "确认被取消（任务已停止），请停下来向用户汇报")
                }
            }
            pkg?.let { session.recordTouched(it) }
            status.onActivity(id, ActivityPhase.ACTING, tool.activityLine(args))
            return true
        }
    }

    // ---------------------------------------------------------------- 编码

    private fun prefixNotes(text: String, notes: List<String>): String =
        if (notes.isEmpty()) text else notes.joinToString("\n") { "用户留言：$it" } + "\n\n" + text

    private fun encodeSuccess(name: String, r: ToolResult, notes: List<String>): McpToolResult {
        val content = ArrayList<Map<String, Any?>>(1 + r.images.size)
        content += linkedMapOf("type" to "text", "text" to prefixNotes(r.text, notes))
        for (img in r.images) {
            content += linkedMapOf("type" to "image", "mimeType" to img.mimeType, "data" to Base64Codec.encode(img.bytes))
        }
        val structured = LinkedHashMap<String, Any?>()
        structured["ok"] = true
        structured["action"] = name
        structured.putAll(r.structured)
        if (notes.isNotEmpty()) structured["userNotes"] = notes
        return McpToolResult(content, JsonValues.normalizeMap(structured), isError = false)
    }

    private fun encodeError(name: String, t: Throwable, notes: List<String>): McpToolResult {
        val pe = t as? PhoneException ?: PhoneException(
            ErrorCode.INTERNAL,
            "内部错误（${t.javaClass.simpleName}）",
            mapOf("exception" to t.javaClass.name),
            cause = t,
        )
        val sb = StringBuilder()
        sb.append("错误 ").append(pe.code.wire).append('：').append(pe.message)
        if (!pe.observationText.isNullOrBlank()) sb.append("\n\n").append(pe.observationText)
        val error = linkedMapOf<String, Any?>(
            "code" to pe.code.wire,
            "message" to pe.message,
            "retry" to pe.code.retry.wire,
        )
        if (pe.details.isNotEmpty()) error["details"] = pe.details
        val structured = linkedMapOf<String, Any?>("ok" to false, "action" to name, "error" to error)
        if (notes.isNotEmpty()) structured["userNotes"] = notes
        return McpToolResult(
            listOf(linkedMapOf("type" to "text", "text" to prefixNotes(sb.toString(), notes))),
            JsonValues.normalizeMap(structured),
            isError = true,
        )
    }

    /** 审计摘要：正文类字段只记长度（协议 §9）。摘要本身出错也不能影响调用。 */
    private fun safeSummary(args: ToolArgs): String = try {
        args.auditSummary(AUDIT_REDACT)
    } catch (_: Throwable) {
        "<参数摘要失败>"
    }

    private companion object {
        const val SWEEP_INTERVAL_MS = 5_000L
        const val OVERLAY_WAIT_MS = 1_500L
        const val OVERLAY_POLL_MS = 50L
        val AUDIT_REDACT = setOf("text", "set", "body", "subject", "reason")
        val SERVICE_STEPS = listOf(
            "打开系统设置 → 无障碍 → 已安装的应用（或「已下载的应用」）",
            "找到 Vantaloom 手机控制，打开开关并确认",
            "部分国产系统还需要在电池优化里把 Vantaloom 设为不受限制，否则服务会被杀",
        )
    }
}
