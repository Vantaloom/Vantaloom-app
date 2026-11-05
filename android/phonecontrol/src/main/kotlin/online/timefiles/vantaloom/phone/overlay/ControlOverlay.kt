package online.timefiles.vantaloom.phone.overlay

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import online.timefiles.vantaloom.phone.BuildConfig
import online.timefiles.vantaloom.phone.R
import online.timefiles.vantaloom.phone.a11y.OverlayHooks
import online.timefiles.vantaloom.phone.core.ports.ActivityPhase
import online.timefiles.vantaloom.phone.core.ports.Clock
import online.timefiles.vantaloom.phone.core.ports.ConfirmDecision
import online.timefiles.vantaloom.phone.core.ports.ConfirmRequest
import online.timefiles.vantaloom.phone.core.ports.ConfirmationPort
import online.timefiles.vantaloom.phone.core.ports.HandoffKind
import online.timefiles.vantaloom.phone.core.ports.HandoffPort
import online.timefiles.vantaloom.phone.core.ports.HandoffRequest
import online.timefiles.vantaloom.phone.core.ports.HandoffResult
import online.timefiles.vantaloom.phone.core.ports.StatusSink
import online.timefiles.vantaloom.phone.core.ports.TaskControl
import online.timefiles.vantaloom.phone.core.ports.TaskState
import online.timefiles.vantaloom.phone.util.MainThread
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.hypot

/**
 * 设备浮层：core 的 [StatusSink] / [ConfirmationPort] / [HandoffPort] 实现，同时作为驱动的 [OverlayHooks]。
 *
 * 全部窗口都是 TYPE_ACCESSIBILITY_OVERLAY，由无障碍服务的 WindowManager 添加，不需要 SYSTEM_ALERT_WINDOW
 * （协议 §8）。驱动在 capture 时排除这一类窗口，所以 agent 在观察里看不到浮层的任何节点。
 *
 * 窗口清单：
 *  - **小球**：任务进行中常驻。颜色 = 阶段；可拖动、松手吸边；点按展开胶囊；长按 1 秒停止。
 *  - **胶囊**：当前动作一句话 + 暂停/继续 + 停止 + 给 agent 留言。
 *  - **确认卡**：「Vantaloom 想要…」+ 三个 [GuardedButton] + 30 秒倒计时；另有一个全屏、不可触摸的高亮窗口框出目标。
 *  - **接手卡**：顶部窄条（不挡住下面的 App，用户要在 App 里登录/输验证码），继续 / 放弃。
 *  - **留言窗**：唯一可获得焦点的窗口（要弹输入法），其余窗口都是 FLAG_NOT_FOCUSABLE，绝不抢前台 App 的焦点。
 *
 * 线程：core 从工具线程调用；所有 View/WindowManager 操作都切主线程。阻塞方法（confirm/handoff）在调用线程
 * 用 CountDownLatch 等主线程上的用户选择，并每 100ms 检查一次任务是否已停止（停止 → 立即 CANCELLED）。
 *
 * 「让开」有三个独立来源，叠加生效互不覆盖：引擎的 [setStandAside]（动作期间）、驱动的
 * [setGestureStandAside]（手势注入那一刻，计数）、驱动的 [setCaptureHidden]（整屏截图那一刻）。
 */
class ControlOverlay(
    private val service: AccessibilityService,
    private val clock: Clock,
    private val lastInjectionAtMs: () -> Long,
) : StatusSink, ConfirmationPort, HandoffPort, OverlayHooks {

    /** 浮层按钮调用的任务控制（引擎建好后由宿主注入）。为 null 时暂停/停止按钮只收起浮层。 */
    @Volatile
    var control: TaskControl? = null

    private val ctx: Context = ContextThemeWrapper(service, android.R.style.Theme_DeviceDefault)
    private val wm: WindowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    // ---- 跨线程读取的状态
    @Volatile private var taskActive = false
    @Volatile private var ballAttached = false
    @Volatile private var probeOk = false
    @Volatile private var currentTaskId: String? = null
    private val gestureAside = AtomicInteger(0)

    /** 同一时刻只有一张阻塞卡（确认或接手）。 */
    private val dialogGate = Semaphore(1)
    @Volatile private var cancelPending: (() -> Unit)? = null

    // ---- 只在主线程读写
    private var phase: ActivityPhase = ActivityPhase.IDLE
    private var line: String = ""
    private var engineStandAside = false
    private var captureHidden = false
    private var expanded = false
    private var lastNotifyUptime = 0L

    private var ball: BallView? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var savedBallX = 0
    private var savedBallY = 0
    private var ballInCorner = false

    private var capsule: View? = null
    private var capsuleParams: WindowManager.LayoutParams? = null
    private var capsuleLine: TextView? = null
    private var capsulePause: GuardedButton? = null

    private var card: View? = null
    private var cardParams: WindowManager.LayoutParams? = null
    private var cardButtons: List<GuardedButton> = emptyList()
    private var countdown: Runnable? = null

    private var highlight: HighlightView? = null
    private var highlightParams: WindowManager.LayoutParams? = null

    private var note: View? = null

    /** 被 GuardedButton 拒绝的触摸总数（累加各按钮，demo 显示）。 */
    @Volatile
    var rejectedTouches: Int = 0
        private set

    // =================================================================== 生命周期

    /** 服务连上后调用一次：探测能否添加浮层窗口（有的 ROM 会拦），结果用于 [overlayReady]。 */
    fun probe() {
        MainThread.post {
            val v = View(ctx)
            val lp = params(1, 1, FLAGS_PASSIVE, Gravity.TOP or Gravity.START)
            probeOk = try {
                wm.addView(v, lp)
                wm.removeViewImmediate(v)
                true
            } catch (t: Throwable) {
                debug("浮层探测失败：$t")
                false
            }
        }
    }

    /** 服务断开时调用：取消挂起的卡片、移除所有窗口。 */
    fun destroy() {
        taskActive = false
        cancelPending?.invoke()
        MainThread.post { removeAll() }
        OverlayNotification.hideTask(service)
    }

    // =================================================================== OverlayHooks

    override fun overlayReady(): Boolean = if (taskActive) ballAttached else probeOk

    override fun setCaptureHidden(hidden: Boolean) {
        callMain {
            captureHidden = hidden
            // 卡片从隐藏恢复时重新武装：防「隐藏 → 注入 → 恢复」这类时序上的取巧。
            if (!hidden) cardButtons.forEach { it.arm() }
            applyState()
        }
    }

    override fun setGestureStandAside(active: Boolean) {
        if (active) gestureAside.incrementAndGet() else gestureAside.updateAndGet { (it - 1).coerceAtLeast(0) }
        callMain { applyState() }
    }

    // =================================================================== StatusSink

    override fun onTaskStarted(taskId: String) {
        currentTaskId = taskId
        taskActive = true
        // 同步建小球：引擎可能紧接着检查 overlayReady()（不变量 2），异步的话会读到旧值。
        callMain {
            phase = ActivityPhase.IDLE
            line = "任务已开始"
            showBall()
        }
        OverlayNotification.showTask(service, "任务已开始")
    }

    override fun onActivity(taskId: String, phase: ActivityPhase, line: String) {
        MainThread.post {
            this.phase = phase
            this.line = line
            ball?.phase = phase
            capsuleLine?.text = line
            updatePauseButton()
            // 通知最多每秒刷新一次：高频 notify 会被系统限流并丢更新。
            val now = SystemClock.uptimeMillis()
            if (now - lastNotifyUptime > 1_000) {
                lastNotifyUptime = now
                OverlayNotification.showTask(service, OverlayStyle.phaseLabel(phase) + "：" + line)
            }
        }
    }

    override fun onTaskEnded(taskId: String, reason: String) {
        if (currentTaskId != null && currentTaskId != taskId) return
        taskActive = false
        currentTaskId = null
        // 任务结束（含用户停止）时挂起的确认卡/接手卡立刻以 CANCELLED 返回。
        cancelPending?.invoke()
        MainThread.post { removeAll() }
        OverlayNotification.hideTask(service)
    }

    override fun setStandAside(standAside: Boolean) {
        callMain {
            engineStandAside = standAside
            applyState()
        }
    }

    // =================================================================== ConfirmationPort

    override fun confirm(request: ConfirmRequest, timeoutMs: Long): ConfirmDecision {
        // 主线程上不能阻塞等用户（会死锁 UI）；理论上不会发生，保守按取消处理。
        if (MainThread.isMain()) return ConfirmDecision.CANCELLED
        if (!dialogGate.tryAcquire(GATE_WAIT_MS, TimeUnit.MILLISECONDS)) return ConfirmDecision.CANCELLED
        val pending = Pending(ConfirmDecision.CANCELLED)
        cancelPending = { pending.cancel() }
        try {
            val deadline = clock.nowMs() + timeoutMs.coerceAtLeast(1_000)
            val shown = try {
                callMain { showConfirm(request, timeoutMs, pending) } == true
            } catch (t: Throwable) {
                debug("确认卡显示失败：$t")
                false
            }
            // 卡片显示不出来 = 用户没机会同意，绝不能当成允许。
            if (!shown) return ConfirmDecision.CANCELLED
            return waitFor(pending, deadline) ?: ConfirmDecision.TIMEOUT
        } finally {
            cancelPending = null
            MainThread.post { dismissCard() }
            dialogGate.release()
        }
    }

    // =================================================================== HandoffPort

    override fun handoff(request: HandoffRequest, timeoutMs: Long): HandoffResult {
        if (MainThread.isMain()) return HandoffResult.CANCELLED
        if (!dialogGate.tryAcquire(GATE_WAIT_MS, TimeUnit.MILLISECONDS)) return HandoffResult.CANCELLED
        val pending = Pending(HandoffResult.CANCELLED)
        cancelPending = { pending.cancel() }
        try {
            val deadline = clock.nowMs() + timeoutMs.coerceAtLeast(1_000)
            val shown = try {
                callMain { showHandoff(request, timeoutMs, pending) } == true
            } catch (t: Throwable) {
                debug("接手卡显示失败：$t")
                false
            }
            if (!shown) return HandoffResult.CANCELLED
            return waitFor(pending, deadline) ?: HandoffResult.TIMEOUT
        } finally {
            cancelPending = null
            MainThread.post { dismissCard() }
            dialogGate.release()
        }
    }

    /**
     * 分片等待：每 100ms 看一眼任务是否已停止，保证「停止」立刻生效（协议 §8）。
     * 返回 null = 超时。
     */
    private fun <T> waitFor(p: Pending<T>, deadline: Long): T? {
        while (true) {
            val remaining = deadline - clock.nowMs()
            if (remaining <= 0) return p.peek()
            if (p.await(minOf(remaining, POLL_MS))) return p.peek()
            if (control?.state() == TaskState.STOPPED) {
                p.cancel()
                return p.peek()
            }
        }
    }

    // =================================================================== 小球与胶囊

    private fun showBall() {
        if (ball != null) {
            ball?.phase = phase
            applyState()
            return
        }
        val size = OverlayStyle.dp(ctx, BALL_DP)
        val v = BallView(ctx)
        val lp = params(size, size, FLAGS_PASSIVE_TOUCHABLE, Gravity.TOP or Gravity.START)
        val dm = ctx.resources.displayMetrics
        lp.x = dm.widthPixels - size - OverlayStyle.dp(ctx, 8f)
        lp.y = (dm.heightPixels * 0.35f).toInt()
        v.setOnTouchListener(BallTouch())
        ballAttached = try {
            wm.addView(v, lp)
            true
        } catch (t: Throwable) {
            debug("小球添加失败：$t")
            false
        }
        if (ballAttached) {
            ball = v
            ballParams = lp
            v.phase = phase
            applyState()
        }
    }

    /** 小球触摸：拖动 / 吸边 / 点按展开 / 长按 1 秒停止。UI 节奏用 uptime（与 MotionEvent 同时基）。 */
    private inner class BallTouch : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var downAt = 0L
        private var dragging = false
        private var stopped = false
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private val tick = object : Runnable {
            override fun run() {
                val b = ball ?: return
                val p = (SystemClock.uptimeMillis() - downAt) / LONG_PRESS_STOP_MS.toFloat()
                if (p >= 1f) {
                    b.stopProgress = 0f
                    stopped = true
                    userStop("用户长按小球停止")
                } else {
                    // 前 200ms 不画进度，避免普通点按也闪一下红圈。
                    b.stopProgress = if (p < 0.2f) 0f else p
                    MainThread.handler.postDelayed(this, 30)
                }
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val lp = ballParams ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX
                    downRawY = e.rawY
                    startX = lp.x
                    startY = lp.y
                    downAt = SystemClock.uptimeMillis()
                    dragging = false
                    stopped = false
                    MainThread.handler.postDelayed(tick, 30)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (!dragging && hypot(dx, dy) > slop) {
                        dragging = true
                        cancelTick()
                    }
                    if (dragging && !ballInCorner) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        clampBall(lp)
                        update(v, lp)
                        positionCapsule()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    cancelTick()
                    if (stopped) return true
                    if (dragging) snapBall() else toggleCapsule()
                }
                MotionEvent.ACTION_CANCEL -> cancelTick()
            }
            return true
        }

        private fun cancelTick() {
            MainThread.handler.removeCallbacks(tick)
            ball?.stopProgress = 0f
        }
    }

    private fun clampBall(lp: WindowManager.LayoutParams) {
        val dm = ctx.resources.displayMetrics
        val size = lp.width
        lp.x = lp.x.coerceIn(0, (dm.widthPixels - size).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(0, (dm.heightPixels - size).coerceAtLeast(0))
    }

    private fun snapBall() {
        val v = ball ?: return
        val lp = ballParams ?: return
        val dm = ctx.resources.displayMetrics
        val margin = OverlayStyle.dp(ctx, 8f)
        lp.x = if (lp.x + lp.width / 2 < dm.widthPixels / 2) margin else dm.widthPixels - lp.width - margin
        clampBall(lp)
        update(v, lp)
        positionCapsule()
    }

    private fun ballOnLeft(): Boolean {
        val lp = ballParams ?: return false
        return lp.x + lp.width / 2 < ctx.resources.displayMetrics.widthPixels / 2
    }

    private fun toggleCapsule() {
        expanded = !expanded
        if (expanded) showCapsule() else removeCapsule()
    }

    private fun showCapsule() {
        if (capsule != null) {
            applyState()
            return
        }
        val root = OverlayStyle.card(ctx)
        val maxW = (ctx.resources.displayMetrics.widthPixels * 0.72f).toInt()
        val title = OverlayStyle.text(ctx, OverlayStyle.phaseLabel(phase), 12f, OverlayStyle.TEXT_SECONDARY)
        val lineView = OverlayStyle.text(ctx, line.ifEmpty { "等待 agent…" }, 14f).apply {
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = maxW
        }
        root.addView(title)
        root.addView(lineView, OverlayStyle.lp(ctx, 2f, LinearLayout.LayoutParams.WRAP_CONTENT))

        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        // 暂停/继续用 GuardedButton：agent 不能替用户「继续」一个被暂停的任务。
        val pause = guarded(R.string.vantaloom_phone_pause, OverlayStyle.NEUTRAL) { togglePause() }
        // 停止用普通按钮：任何来源按下停止都只会让 agent 失去能力，不需要防注入。
        val stop = Button(ctx).apply {
            setText(R.string.vantaloom_phone_stop)
            OverlayStyle.styleButton(this, OverlayStyle.DANGER)
            setOnClickListener { userStop("用户在浮层上停止") }
        }
        val noteBtn = Button(ctx).apply {
            setText(R.string.vantaloom_phone_leave_note)
            OverlayStyle.styleButton(this, OverlayStyle.NEUTRAL)
            setOnClickListener { showNote() }
        }
        val gap = OverlayStyle.dp(ctx, 8f)
        for ((i, b) in listOf<View>(pause, stop, noteBtn).withIndex()) {
            row.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (i > 0) marginStart = gap
            })
        }
        root.addView(row, OverlayStyle.lp(ctx, 10f, LinearLayout.LayoutParams.WRAP_CONTENT))

        val lp = params(WRAP, WRAP, FLAGS_PASSIVE_TOUCHABLE, Gravity.TOP or Gravity.START)
        try {
            wm.addView(root, lp)
        } catch (t: Throwable) {
            debug("胶囊添加失败：$t")
            expanded = false
            return
        }
        capsule = root
        capsuleParams = lp
        capsuleLine = lineView
        capsulePause = pause
        updatePauseButton()
        positionCapsule()
        applyState()
    }

    private fun positionCapsule() {
        val v = capsule ?: return
        val lp = capsuleParams ?: return
        val b = ballParams ?: return
        val gap = OverlayStyle.dp(ctx, 6f)
        if (ballOnLeft()) {
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = b.x + b.width + gap
        } else {
            // 右侧：用 END 重力，x 表示距右边缘的距离，免得要先量胶囊宽度。
            lp.gravity = Gravity.TOP or Gravity.END
            lp.x = ctx.resources.displayMetrics.widthPixels - b.x + gap
        }
        lp.y = b.y
        update(v, lp)
    }

    private fun removeCapsule() {
        capsule?.let { remove(it) }
        capsule = null
        capsuleParams = null
        capsuleLine = null
        capsulePause = null
    }

    private fun updatePauseButton() {
        val b = capsulePause ?: return
        val paused = control?.state() == TaskState.PAUSED || phase == ActivityPhase.PAUSED
        b.setText(if (paused) R.string.vantaloom_phone_resume else R.string.vantaloom_phone_pause)
    }

    private fun togglePause() {
        val c = control ?: return
        if (c.state() == TaskState.PAUSED) c.resume() else c.pause()
        updatePauseButton()
    }

    private fun userStop(reason: String) {
        // 先取消挂起的卡片，再停任务：停止要「立即」生效。
        cancelPending?.invoke()
        val c = control
        if (c != null) c.stop(reason) else removeAll()
    }

    // =================================================================== 留言窗

    /**
     * 「给 agent 留言」：唯一需要输入焦点的浮层窗口。其余窗口都带 FLAG_NOT_FOCUSABLE，
     * 这里单独开一个不带该标志的窗口（即「临时去掉 FLAG_NOT_FOCUSABLE」），关掉即恢复，前台 App 焦点不受长期影响。
     */
    private fun showNote() {
        if (note != null) return
        val root = OverlayStyle.card(ctx)
        root.addView(OverlayStyle.text(ctx, ctx.getString(R.string.vantaloom_phone_leave_note), 16f, bold = true))
        val edit = EditText(ctx).apply {
            hint = "例如：别发了，先帮我看看价格"
            setTextColor(OverlayStyle.TEXT_PRIMARY)
            setHintTextColor(OverlayStyle.TEXT_SECONDARY)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            maxLines = 5
            filters = arrayOf<android.text.InputFilter>(android.text.InputFilter.LengthFilter(500))
        }
        root.addView(edit, OverlayStyle.lp(ctx, 8f))
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val cancel = Button(ctx).apply {
            setText(R.string.vantaloom_phone_cancel)
            OverlayStyle.styleButton(this, OverlayStyle.NEUTRAL)
            setOnClickListener { removeNote() }
        }
        // 发送用 GuardedButton：留言会以「用户留言」身份进入 agent 上下文，不能被 agent 自己伪造。
        // 允许部分遮挡：输入法窗口必然与留言窗重叠。
        val send = GuardedButton(ctx, clock, lastInjectionAtMs, allowPartialObscure = true).apply {
            setText(R.string.vantaloom_phone_send)
            OverlayStyle.styleButton(this, OverlayStyle.POSITIVE)
            onGuardedClick = {
                val text = edit.text?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) control?.leaveNote(text)
                removeNote()
            }
        }
        row.addView(cancel)
        row.addView(send, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = OverlayStyle.dp(ctx, 8f) })
        root.addView(row, OverlayStyle.lp(ctx, 10f))
        edit.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                removeNote()
                true
            } else {
                false
            }
        }

        val wrapper = FrameLayout(ctx).apply {
            val m = OverlayStyle.dp(ctx, 16f)
            setPadding(m, m, m, m)
            addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WRAP))
        }
        val lp = params(ViewGroup.LayoutParams.MATCH_PARENT, WRAP, FLAGS_FOCUSABLE, Gravity.CENTER).apply {
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }
        try {
            wm.addView(wrapper, lp)
        } catch (t: Throwable) {
            debug("留言窗添加失败：$t")
            return
        }
        note = wrapper
        edit.requestFocus()
        edit.post {
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
        }
        applyState()
    }

    private fun removeNote() {
        note?.let { remove(it) }
        note = null
    }

    // =================================================================== 确认卡

    private fun showConfirm(req: ConfirmRequest, timeoutMs: Long, pending: Pending<ConfirmDecision>): Boolean {
        dismissCard()
        val dm = ctx.resources.displayMetrics
        val target = req.targetBounds

        // 先加高亮层（在下），再加卡片（在上）：同 UID 的窗口不会触发 OBSCURED 标志，但层次清楚些。
        if (target != null && !target.isEmpty) {
            val hv = HighlightView(ctx)
            hv.target = target
            val hlp = params(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, FLAGS_PASSIVE, Gravity.TOP or Gravity.START)
            try {
                wm.addView(hv, hlp)
                highlight = hv
                highlightParams = hlp
            } catch (t: Throwable) {
                debug("高亮层添加失败：$t")
            }
        }

        val root = OverlayStyle.card(ctx)
        root.addView(OverlayStyle.text(ctx, ctx.getString(R.string.vantaloom_phone_confirm_title), 13f, OverlayStyle.TEXT_SECONDARY))
        root.addView(OverlayStyle.text(ctx, req.summary, 17f, bold = true), OverlayStyle.lp(ctx, 4f))
        root.addView(OverlayStyle.text(ctx, "原因：" + req.reason, 14f, OverlayStyle.ACCENT), OverlayStyle.lp(ctx, 6f))
        val app = when {
            req.appLabel != null && req.packageName != null -> "${req.appLabel}（${req.packageName}）"
            req.appLabel != null -> req.appLabel
            req.packageName != null -> req.packageName
            else -> null
        }
        if (app != null) root.addView(OverlayStyle.text(ctx, "应用：$app", 13f, OverlayStyle.TEXT_SECONDARY), OverlayStyle.lp(ctx, 4f))
        req.remoteCaller?.let {
            root.addView(OverlayStyle.text(ctx, "发起方：$it（远端会话）", 13f, OverlayStyle.TEXT_SECONDARY), OverlayStyle.lp(ctx, 2f))
        }
        val timer = OverlayStyle.text(ctx, "", 12f, OverlayStyle.TEXT_SECONDARY)
        root.addView(timer, OverlayStyle.lp(ctx, 6f))

        val once = guarded(R.string.vantaloom_phone_allow_once, OverlayStyle.POSITIVE) { pending.resolve(ConfirmDecision.ALLOW_ONCE) }
        val reject = guarded(R.string.vantaloom_phone_reject, OverlayStyle.DANGER) { pending.resolve(ConfirmDecision.REJECT) }
        val buttons = ArrayList<GuardedButton>()
        root.addView(once, OverlayStyle.lp(ctx, 12f))
        buttons += once
        if (req.allowTaskWide) {
            val task = guarded(R.string.vantaloom_phone_allow_task, OverlayStyle.NEUTRAL) { pending.resolve(ConfirmDecision.ALLOW_TASK) }
            root.addView(task, OverlayStyle.lp(ctx, 8f))
            buttons += task
        }
        root.addView(reject, OverlayStyle.lp(ctx, 8f))
        buttons += reject

        val wrapper = cardWrapper(root)
        // 目标在屏幕下半部时卡片放上面，别把用户要核对的那个元素挡住。
        val targetLow = target != null && target.centerY > dm.heightPixels * 0.5f
        val lp = params(ViewGroup.LayoutParams.MATCH_PARENT, WRAP, FLAGS_CARD, if (targetLow) Gravity.TOP else Gravity.BOTTOM)
        lp.y = OverlayStyle.dp(ctx, if (targetLow) 48f else 24f)
        try {
            wm.addView(wrapper, lp)
        } catch (t: Throwable) {
            debug("确认卡添加失败：$t")
            dismissCard()
            return false
        }
        card = wrapper
        cardParams = lp
        cardButtons = buttons
        buttons.forEach { it.arm() }
        startCountdown(timer, timeoutMs, "秒后自动拒绝")
        applyState()
        return true
    }

    // =================================================================== 接手卡

    private fun showHandoff(req: HandoffRequest, timeoutMs: Long, pending: Pending<HandoffResult>): Boolean {
        dismissCard()
        val root = OverlayStyle.card(ctx)
        val kind = when (req.kind) {
            HandoffKind.LOGIN -> "需要你登录"
            HandoffKind.CAPTCHA -> "需要你完成验证"
            HandoffKind.PAYMENT -> "需要你完成支付"
            HandoffKind.CONFIRM -> "需要你确认"
            HandoffKind.OTHER -> ctx.getString(R.string.vantaloom_phone_handoff_title)
        }
        root.addView(OverlayStyle.text(ctx, kind, 15f, bold = true))
        root.addView(OverlayStyle.text(ctx, req.reason, 14f).apply {
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
        }, OverlayStyle.lp(ctx, 4f))
        val timer = OverlayStyle.text(ctx, "", 12f, OverlayStyle.TEXT_SECONDARY)
        root.addView(timer, OverlayStyle.lp(ctx, 4f))
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val resume = guarded(R.string.vantaloom_phone_handoff_resume, OverlayStyle.POSITIVE) { pending.resolve(HandoffResult.RESUMED) }
        val abandon = guarded(R.string.vantaloom_phone_handoff_abandon, OverlayStyle.NEUTRAL) { pending.resolve(HandoffResult.ABANDONED) }
        row.addView(resume, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(abandon, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = OverlayStyle.dp(ctx, 8f) })
        root.addView(row, OverlayStyle.lp(ctx, 10f))

        // 顶部窄条、不拦截卡片以外的触摸：用户要在下面的 App 里自己操作。
        val wrapper = cardWrapper(root)
        val lp = params(ViewGroup.LayoutParams.MATCH_PARENT, WRAP, FLAGS_CARD, Gravity.TOP)
        lp.y = OverlayStyle.dp(ctx, 32f)
        try {
            wm.addView(wrapper, lp)
        } catch (t: Throwable) {
            debug("接手卡添加失败：$t")
            return false
        }
        card = wrapper
        cardParams = lp
        cardButtons = listOf(resume, abandon)
        cardButtons.forEach { it.arm() }
        startCountdown(timer, timeoutMs, "秒后自动放弃")
        applyState()
        return true
    }

    private fun cardWrapper(root: View): FrameLayout = FrameLayout(ctx).apply {
        val m = OverlayStyle.dp(ctx, 12f)
        setPadding(m, 0, m, 0)
        addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WRAP))
    }

    private fun startCountdown(view: TextView, timeoutMs: Long, suffix: String) {
        countdown?.let { MainThread.handler.removeCallbacks(it) }
        val endUptime = SystemClock.uptimeMillis() + timeoutMs
        val r = object : Runnable {
            override fun run() {
                val left = ((endUptime - SystemClock.uptimeMillis()) / 1000L).coerceAtLeast(0)
                view.text = "$left $suffix"
                if (left > 0 && countdown === this) MainThread.handler.postDelayed(this, 1_000)
            }
        }
        countdown = r
        r.run()
    }

    private fun dismissCard() {
        countdown?.let { MainThread.handler.removeCallbacks(it) }
        countdown = null
        for (b in cardButtons) {
            rejectedTouches += b.rejectedCount
            b.disarm()
        }
        cardButtons = emptyList()
        card?.let { remove(it) }
        card = null
        cardParams = null
        highlight?.let { remove(it) }
        highlight = null
        highlightParams = null
    }

    // =================================================================== 状态应用

    /** 把三种「让开」来源合成到各窗口的可见性、可触性与位置上。只在主线程调用。 */
    private fun applyState() {
        val hidden = captureHidden
        val untouchable = engineStandAside || gestureAside.get() > 0

        ball?.let { v ->
            val lp = ballParams ?: return@let
            v.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
            v.alpha = if (untouchable) 0.4f else 1f
            setFlag(lp, WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, untouchable)
            if (untouchable && !ballInCorner) {
                // 让开：记住原位置，挪到右上角，免得挡住手势落点。
                savedBallX = lp.x
                savedBallY = lp.y
                val dm = ctx.resources.displayMetrics
                lp.x = dm.widthPixels - lp.width - OverlayStyle.dp(ctx, 4f)
                lp.y = OverlayStyle.dp(ctx, 4f)
                ballInCorner = true
            } else if (!untouchable && ballInCorner) {
                lp.x = savedBallX
                lp.y = savedBallY
                ballInCorner = false
            }
            update(v, lp)
        }
        capsule?.let { v ->
            val lp = capsuleParams ?: return@let
            v.visibility = if (hidden || untouchable) View.GONE else View.VISIBLE
            setFlag(lp, WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, untouchable)
            update(v, lp)
        }
        card?.let { v ->
            val lp = cardParams ?: return@let
            v.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
            setFlag(lp, WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, gestureAside.get() > 0)
            update(v, lp)
        }
        highlight?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        note?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
    }

    private fun removeAll() {
        dismissCard()
        removeNote()
        removeCapsule()
        expanded = false
        ball?.let { remove(it) }
        ball = null
        ballParams = null
        ballAttached = false
        ballInCorner = false
        engineStandAside = false
        captureHidden = false
        phase = ActivityPhase.IDLE
        line = ""
    }

    // =================================================================== 工具

    private fun guarded(textRes: Int, color: Int, onClick: () -> Unit): GuardedButton =
        GuardedButton(ctx, clock, lastInjectionAtMs).apply {
            setText(textRes)
            OverlayStyle.styleButton(this, color)
            onGuardedClick = onClick
        }

    private fun params(w: Int, h: Int, flags: Int, gravity: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT).apply {
            this.gravity = gravity
            title = "VantaloomPhoneOverlay"
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

    private fun setFlag(lp: WindowManager.LayoutParams, flag: Int, on: Boolean) {
        lp.flags = if (on) lp.flags or flag else lp.flags and flag.inv()
    }

    private fun update(v: View, lp: WindowManager.LayoutParams) {
        if (!v.isAttachedToWindow) return
        try {
            wm.updateViewLayout(v, lp)
        } catch (t: Throwable) {
            debug("updateViewLayout 失败：$t")
        }
    }

    private fun remove(v: View) {
        try {
            if (v.isAttachedToWindow) wm.removeViewImmediate(v)
        } catch (t: Throwable) {
            debug("removeView 失败：$t")
        }
    }

    /** 同步切主线程执行；主线程卡住时最多等 [UI_TIMEOUT_MS]，不让工具线程无限挂起。 */
    private fun <T> callMain(block: () -> T): T? = MainThread.call(UI_TIMEOUT_MS, block)

    private fun debug(msg: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, msg)
    }

    /**
     * 一次性结果槽：第一次 resolve 生效，之后忽略（用户点击与停止/超时可能同时发生，
     * 先到先得）。[cancelValue] 是任务停止时的结果（CANCELLED）。
     */
    private class Pending<T>(private val cancelValue: T) {
        private val latch = CountDownLatch(1)
        @Volatile private var value: T? = null

        @Synchronized
        fun resolve(v: T) {
            if (latch.count == 0L) return
            value = v
            latch.countDown()
        }

        fun cancel() = resolve(cancelValue)
        fun await(ms: Long): Boolean = latch.await(ms, TimeUnit.MILLISECONDS)
        fun peek(): T? = value
    }

    companion object {
        private const val TAG = "VtlPhoneOverlay"
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val BALL_DP = 44f
        private const val LONG_PRESS_STOP_MS = 1_000L
        private const val POLL_MS = 100L
        private const val UI_TIMEOUT_MS = 2_000L
        private const val GATE_WAIT_MS = 2_000L

        /** 纯展示、完全不接触摸（高亮层、探测窗口）。 */
        private const val FLAGS_PASSIVE =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        /** 接触摸但不抢焦点、不拦窗口外的触摸（小球、胶囊）。 */
        private const val FLAGS_PASSIVE_TOUCHABLE =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        /** 卡片：同上，但不要 LAYOUT_NO_LIMITS（让系统把它放在可见区域内）。 */
        private const val FLAGS_CARD =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

        /** 留言窗：可获得焦点（弹输入法），不拦窗口外的触摸。 */
        private const val FLAGS_FOCUSABLE = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
    }
}
