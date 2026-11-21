package online.timefiles.vantaloom.phone.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import online.timefiles.vantaloom.phone.BuildConfig
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
import online.timefiles.vantaloom.phone.core.model.RawNode
import online.timefiles.vantaloom.phone.core.model.RawSnapshot
import online.timefiles.vantaloom.phone.core.model.RawWindow
import online.timefiles.vantaloom.phone.core.model.Rect
import online.timefiles.vantaloom.phone.core.model.WindowType
import online.timefiles.vantaloom.phone.core.model.phoneError
import online.timefiles.vantaloom.phone.core.ports.Clock
import online.timefiles.vantaloom.phone.core.tools.Budgets
import online.timefiles.vantaloom.phone.overlay.OverlayNotification
import online.timefiles.vantaloom.phone.setup.RomInfo
import online.timefiles.vantaloom.phone.util.MainThread
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 主屏驱动：把 core 的 [ScreenDriver] 落到 AccessibilityService 上。
 *
 * 设计要点（为什么这样写）：
 *  - **句柄表**：core 只拿到 [RawSnapshot]（纯数据），动作时用 (captureId, nodeId) 回来找真实节点。
 *    保留最近 [KEEP_CAPTURES] 次 capture 的 AccessibilityNodeInfo；更早的在 API < 33 上 recycle
 *    （33 起 recycle 是空操作且被废弃）。
 *  - **线程**：所有方法都在工具线程同步调用；需要回调的（手势、截图）用 CountDownLatch 同步化并带超时。
 *    performAction / getChild 是 binder 调用，任何线程都可用，不必切主线程。
 *  - **时间**：一律走 core 的 [Clock]（事件时间戳、注入时间戳、awaitSettle 的 sleep），测试可替换。
 *  - **错误**：已知失败抛 [PhoneException]；其余异常交给引擎包成 INTERNAL。
 *
 * [overlay] 在构造后由宿主注入（浮层依赖本驱动的 [lastInjectionAtMs]，二者互相引用）。
 */
class AndroidScreenDriver(
    private val service: PhoneAccessibilityService,
    private val clock: Clock,
    private val budgets: Budgets = Budgets(),
) : ScreenDriver {

    /** 浮层接缝；宿主组装完浮层后设置。默认 NONE → 动作类工具因「浮层不在场」被拒。 */
    @Volatile
    var overlay: OverlayHooks = OverlayHooks.NONE

    override val display: DisplayKind = DisplayKind.MAIN

    private val context: Context get() = service
    private val pm: PackageManager get() = service.packageManager

    private val captureSeq = AtomicLong(0)
    private val lastInjection = AtomicLong(0)
    private val handleLock = Any()

    /** captureId → (nodeId → 原生节点)。插入序即时间序。 */
    private val handles = LinkedHashMap<Long, HashMap<Int, AccessibilityNodeInfo>>()

    /** 包名 → 显示名缓存。安装/卸载很少发生；最多缓存 512 个，满了整体清空（简单且足够）。 */
    private val labelCache = ConcurrentHashMap<String, String>()

    // ------------------------------------------------------------------ availability

    override fun availability(): DriverAvailability {
        val connected = service.connected && PhoneAccessibilityService.current() === service
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val screenOn = power?.isInteractive ?: true
        val locked = keyguard?.isKeyguardLocked ?: false
        val canShot = canScreenshot()
        val overlayOk = overlay.overlayReady()
        val reason = when {
            !connected -> "无障碍服务未连接，请在系统设置里重新开启「Vantaloom 手机操控」"
            !screenOn -> "屏幕已熄灭"
            locked -> "手机处于锁屏状态，请先解锁"
            !overlayOk -> "浮层无法显示（可能被系统或 ROM 拦截），动作类工具不可用"
            else -> null
        }
        return DriverAvailability(
            serviceConnected = connected,
            screenOn = screenOn,
            locked = locked,
            apiLevel = Build.VERSION.SDK_INT,
            canScreenshot = canShot,
            canImeEnter = Build.VERSION.SDK_INT >= 30,
            overlayAttached = overlayOk,
            manufacturer = Build.MANUFACTURER ?: "",
            model = Build.MODEL ?: "",
            romHint = RomInfo.hint,
            reason = reason,
        )
    }

    /** 截图能力 = API 30+ 且服务配置里真的拿到了截图能力（ROM 可能裁掉）。 */
    private fun canScreenshot(): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val caps = service.serviceInfo?.capabilities ?: return true
        return (caps and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT) != 0
    }

    private fun requireConnected() {
        if (!service.connected) phoneError(ErrorCode.SERVICE_DISABLED, "无障碍服务未连接")
    }

    // ------------------------------------------------------------------ capture

    override fun capture(budgetMs: Long): RawSnapshot {
        requireConnected()
        val started = clock.nowMs()
        val deadline = started + budgetMs.coerceAtLeast(1)
        val captureId = captureSeq.incrementAndGet()
        val table = HashMap<Int, AccessibilityNodeInfo>(256)
        val walker = Walker(table, deadline)

        val (screenW, screenH, rotation) = screenGeometry()
        val rawWindows = ArrayList<RawWindow>()
        var imeVisible = false
        var foreground: String? = null

        val windows: List<AccessibilityWindowInfo> = try {
            service.windows ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
        try {
            // layer 越大越靠上；协议要求「按 z 序从上到下」。
            val sorted = windows.sortedByDescending { it.layer }
            for (w in sorted) {
                val type = mapWindowType(w.type)
                if (type == WindowType.ACCESSIBILITY_OVERLAY) continue // 自己（或其他无障碍服务）的浮层
                if (type == WindowType.INPUT_METHOD) imeVisible = true
                val bounds = android.graphics.Rect().also { w.getBoundsInScreen(it) }
                val root = if (walker.stopped) null else try {
                    w.root
                } catch (_: Throwable) {
                    null
                }
                val pkg = root?.packageName?.toString()
                if (w.isActive && type == WindowType.APPLICATION && foreground == null) foreground = pkg
                val rawRoot = root?.let { walker.walk(it, w.id, 0) }
                rawWindows += RawWindow(
                    windowId = w.id,
                    type = type,
                    packageName = pkg,
                    title = w.title?.toString(),
                    bounds = bounds.toCore(),
                    active = w.isActive,
                    focused = w.isFocused,
                    layer = w.layer,
                    root = rawRoot,
                )
            }
        } finally {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                for (w in windows) runCatching { w.recycle() }
            }
        }

        // 部分 ROM 不尊重 flagRetrieveInteractiveWindows，windows 为空：退回活动窗口根节点。
        if (rawWindows.isEmpty()) {
            val root = try {
                service.rootInActiveWindow
            } catch (_: Throwable) {
                null
            }
            if (root != null) {
                val pkg = root.packageName?.toString()
                foreground = pkg
                val wid = root.windowId
                val rawRoot = walker.walk(root, wid, 0)
                rawWindows += RawWindow(
                    windowId = wid, type = WindowType.APPLICATION, packageName = pkg, title = null,
                    bounds = Rect(0, 0, screenW, screenH), active = true, focused = true, layer = 0, root = rawRoot,
                )
            }
        }

        if (foreground == null) foreground = service.events.foregroundPackage
        registerCapture(captureId, table)

        return RawSnapshot(
            captureId = captureId,
            display = DisplayKind.MAIN,
            foregroundPackage = foreground,
            foregroundLabel = foreground?.let { labelOf(it) },
            screenWidth = screenW,
            screenHeight = screenH,
            rotation = rotation,
            imeVisible = imeVisible,
            // 无障碍 API 看不到窗口的 FLAG_SECURE；尽力而为给 false，由截图侧（失败码/全黑）再判。
            secureWindow = false,
            windows = rawWindows,
            complete = !walker.truncated,
            capturedAtMs = started,
        )
    }

    /**
     * 前序遍历器。节点号在整个 capture 内连续分配（跨窗口唯一）。
     * 超预算或超节点上限后不再取子节点（[truncated]=true），已取到的部分照常返回。
     */
    private inner class Walker(
        private val table: HashMap<Int, AccessibilityNodeInfo>,
        private val deadline: Long,
    ) {
        var nextId = 0
        var truncated = false
        val stopped: Boolean get() = truncated

        fun walk(node: AccessibilityNodeInfo, windowId: Int, depth: Int): RawNode {
            val id = nextId++
            table[id] = node
            val children = ArrayList<RawNode>()
            if (depth < MAX_DEPTH) {
                val n = node.childCount
                for (i in 0 until n) {
                    if (nextId >= MAX_NODES || clock.nowMs() > deadline) {
                        truncated = true
                        break
                    }
                    val child = try {
                        node.getChild(i)
                    } catch (_: Throwable) {
                        null
                    } ?: continue
                    children += walk(child, windowId, depth + 1)
                }
            } else if (node.childCount > 0) {
                truncated = true
            }
            return mapNode(id, windowId, node, children)
        }
    }

    private fun mapNode(id: Int, windowId: Int, n: AccessibilityNodeInfo, children: List<RawNode>): RawNode {
        val r = android.graphics.Rect()
        n.getBoundsInScreen(r)
        val password = n.isPassword
        val coll = n.collectionInfo
        // 各字段直接写 Build.VERSION.SDK_INT 判断（不用局部变量），让 lint 的 NewApi 检查能识别守卫。
        return RawNode(
            id = id,
            windowId = windowId,
            className = n.className?.toString(),
            packageName = n.packageName?.toString(),
            // 纵深防御：密码框的文本在平台层就丢掉，core 再怎么出错也渲染不出来。
            text = if (password) null else n.text?.toString(),
            contentDescription = n.contentDescription?.toString(),
            hintText = if (Build.VERSION.SDK_INT >= 26) n.hintText?.toString() else null,
            viewId = n.viewIdResourceName,
            paneTitle = if (Build.VERSION.SDK_INT >= 28) n.paneTitle?.toString() else null,
            stateDescription = if (Build.VERSION.SDK_INT >= 30) n.stateDescription?.toString() else null,
            tooltip = if (Build.VERSION.SDK_INT >= 28) n.tooltipText?.toString() else null,
            bounds = r.toCore(),
            clickable = n.isClickable,
            longClickable = n.isLongClickable,
            focusable = n.isFocusable,
            focused = n.isFocused,
            editable = n.isEditable,
            scrollable = n.isScrollable,
            checkable = n.isCheckable,
            checked = n.isChecked,
            selected = n.isSelected,
            enabled = n.isEnabled,
            password = password,
            visibleToUser = n.isVisibleToUser,
            heading = if (Build.VERSION.SDK_INT >= 28) n.isHeading else false,
            actions = mapActions(n),
            collectionRows = coll?.rowCount ?: -1,
            collectionColumns = coll?.columnCount ?: -1,
            maxTextLength = n.maxTextLength,
            children = children,
        )
    }

    private fun mapActions(n: AccessibilityNodeInfo): Set<NodeAction> {
        val list = n.actionList ?: return emptySet()
        if (list.isEmpty()) return emptySet()
        val out = HashSet<NodeAction>()
        val table = actionIdToNode
        for (a in list) table[a.id]?.let { out += it }
        return out
    }

    private fun registerCapture(captureId: Long, table: HashMap<Int, AccessibilityNodeInfo>) {
        val evicted = ArrayList<HashMap<Int, AccessibilityNodeInfo>>()
        synchronized(handleLock) {
            handles[captureId] = table
            while (handles.size > KEEP_CAPTURES) {
                val oldest = handles.keys.first()
                handles.remove(oldest)?.let { evicted += it }
            }
        }
        if (Build.VERSION.SDK_INT < 33) {
            @Suppress("DEPRECATION")
            for (t in evicted) for (node in t.values) runCatching { node.recycle() }
        }
    }

    /** 服务断开时释放全部句柄。 */
    fun release() {
        val all: List<HashMap<Int, AccessibilityNodeInfo>>
        synchronized(handleLock) {
            all = handles.values.toList()
            handles.clear()
        }
        if (Build.VERSION.SDK_INT < 33) {
            @Suppress("DEPRECATION")
            for (t in all) for (node in t.values) runCatching { node.recycle() }
        }
    }

    private fun lookup(captureId: Long, nodeId: Int): AccessibilityNodeInfo {
        val node = synchronized(handleLock) { handles[captureId]?.get(nodeId) }
            ?: phoneError(ErrorCode.STALE_SCREEN, "屏幕已过期（capture $captureId 已释放）")
        // refresh 失败 = 节点已从界面上消失或视图被重建。
        val alive = try {
            node.refresh()
        } catch (_: Throwable) {
            false
        }
        if (!alive) phoneError(ErrorCode.STALE_SCREEN, "目标元素已不在屏幕上")
        return node
    }

    // ------------------------------------------------------------------ node actions

    override fun performNode(captureId: Long, nodeId: Int, action: NodeAction, text: String?): Boolean {
        requireConnected()
        val node = lookup(captureId, nodeId)
        val (actionId, args) = when (action) {
            NodeAction.CLICK -> AccessibilityNodeInfo.ACTION_CLICK to null
            NodeAction.LONG_CLICK -> AccessibilityNodeInfo.ACTION_LONG_CLICK to null
            NodeAction.FOCUS -> AccessibilityNodeInfo.ACTION_FOCUS to null
            NodeAction.SET_TEXT -> AccessibilityNodeInfo.ACTION_SET_TEXT to Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text ?: "")
            }
            NodeAction.IME_ENTER ->
                if (Build.VERSION.SDK_INT >= 30) {
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id to null
                } else {
                    phoneError(ErrorCode.UNSUPPORTED, "IME_ENTER 需要 Android 11（API 30）以上")
                }
            NodeAction.SCROLL_FORWARD -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD to null
            NodeAction.SCROLL_BACKWARD -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD to null
            // 方向滚动与 SHOW_ON_SCREEN 是 API 23；minSdk 24 已满足，无需守卫。
            NodeAction.SCROLL_UP -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id to null
            NodeAction.SCROLL_DOWN -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id to null
            NodeAction.SCROLL_LEFT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id to null
            NodeAction.SCROLL_RIGHT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id to null
            NodeAction.SHOW_ON_SCREEN -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id to null
            NodeAction.SELECT -> AccessibilityNodeInfo.ACTION_SELECT to null
            NodeAction.EXPAND -> AccessibilityNodeInfo.ACTION_EXPAND to null
            NodeAction.COLLAPSE -> AccessibilityNodeInfo.ACTION_COLLAPSE to null
            NodeAction.DISMISS -> AccessibilityNodeInfo.ACTION_DISMISS to null
            NodeAction.COPY -> AccessibilityNodeInfo.ACTION_COPY to null
            NodeAction.PASTE -> AccessibilityNodeInfo.ACTION_PASTE to null
        }
        // 语义点击同样记为「注入」：确认卡的 GuardedButton 不接受这个时间窗里的触摸。
        markInjection()
        return try {
            if (args != null) node.performAction(actionId, args) else node.performAction(actionId)
        } catch (t: Throwable) {
            throw PhoneException(ErrorCode.ACTION_FAILED, "系统执行动作失败：${t.javaClass.simpleName}", cause = t)
        } finally {
            markInjection()
        }
    }

    // ------------------------------------------------------------------ gestures

    override fun tap(x: Int, y: Int, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(clampX(x), clampY(y)) }
        return dispatch(path, durationMs.coerceIn(1, MAX_GESTURE_MS))
    }

    override fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(clampX(fromX), clampY(fromY))
            lineTo(clampX(toX), clampY(toY))
        }
        return dispatch(path, durationMs.coerceIn(1, MAX_GESTURE_MS))
    }

    private fun clampX(x: Int): Float {
        val (w, _, _) = screenGeometry()
        return x.coerceIn(0, (w - 1).coerceAtLeast(0)).toFloat()
    }

    private fun clampY(y: Int): Float {
        val (_, h, _) = screenGeometry()
        return y.coerceIn(0, (h - 1).coerceAtLeast(0)).toFloat()
    }

    /**
     * 派发手势并同步等回调。回调用主线程 Handler（dispatchGesture 要求 handler 所在线程有 Looper；
     * 工具线程没有）。超时（手势时长 + 余量）视为失败——这时手势可能已经部分执行，所以返回 false
     * 让 core 按 ACTION_FAILED(retry=unsafe) 处理，而不是重试。
     */
    private fun dispatch(path: Path, durationMs: Long): Boolean {
        requireConnected()
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        val latch = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                completed.set(true)
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                latch.countDown()
            }
        }
        overlay.setGestureStandAside(true)
        try {
            markInjection()
            val accepted = try {
                service.dispatchGesture(gesture, callback, MainThread.handler)
            } catch (t: Throwable) {
                debug("dispatchGesture 抛异常：$t")
                false
            }
            if (!accepted) return false
            val ok = latch.await(durationMs + GESTURE_SLACK_MS, TimeUnit.MILLISECONDS)
            return ok && completed.get()
        } finally {
            markInjection()
            overlay.setGestureStandAside(false)
        }
    }

    private fun markInjection() {
        lastInjection.set(clock.nowMs())
    }

    override fun lastInjectionAtMs(): Long = lastInjection.get()

    override fun globalAction(action: GlobalAction): Boolean {
        requireConnected()
        val id = when (action) {
            GlobalAction.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            GlobalAction.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            GlobalAction.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
            GlobalAction.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            GlobalAction.QUICK_SETTINGS -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            GlobalAction.LOCK_SCREEN ->
                if (Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
                else phoneError(ErrorCode.UNSUPPORTED, "锁屏需要 Android 9（API 28）以上")
            GlobalAction.SCREENSHOT ->
                if (Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
                else phoneError(ErrorCode.UNSUPPORTED, "系统截屏需要 Android 9（API 28）以上")
        }
        markInjection()
        return try {
            service.performGlobalAction(id)
        } finally {
            markInjection()
        }
    }

    // ------------------------------------------------------------------ screenshot

    override fun screenshot(timeoutMs: Long): Screenshot {
        requireConnected()
        if (Build.VERSION.SDK_INT >= 30) return screenshot30(timeoutMs)
        phoneError(ErrorCode.UNSUPPORTED, "截图需要 Android 11（API 30）以上")
    }

    @RequiresApi(30)
    private fun screenshot30(timeoutMs: Long): Screenshot {
        if (!canScreenshot()) phoneError(ErrorCode.UNSUPPORTED, "系统没有授予截图能力（可能被 ROM 限制）")
        val deadline = clock.nowMs() + timeoutMs.coerceAtLeast(500)
        var outcome = takeOnce(deadline)
        if (outcome is ScreenshotTaker.Outcome.Failed && ScreenshotTaker.isIntervalTooShort(outcome.code)) {
            // 系统限制截图频率（约 3 次/秒）；等一下再试一次。
            clock.sleep(SCREENSHOT_RETRY_MS)
            outcome = takeOnce(deadline)
        }
        return when (outcome) {
            is ScreenshotTaker.Outcome.Ok -> encode(outcome.bitmap)
            is ScreenshotTaker.Outcome.Failed ->
                if (ScreenshotTaker.isSecureError(outcome.code)) {
                    Screenshot(jpeg = null, width = 0, height = 0, scale = 1.0, secure = true)
                } else {
                    phoneError(ErrorCode.ACTION_FAILED, "截图失败（系统错误码 ${outcome.code}）")
                }
            ScreenshotTaker.Outcome.TimedOut -> phoneError(ErrorCode.TIMEOUT, "截图超时")
        }
    }

    /**
     * 选截图方式：
     *  - 34+ 且活动应用窗口铺满全屏、并且它上面没有别的非浮层窗口（对话框/输入法/系统弹窗）时，
     *    用 takeScreenshotOfWindow——天然不含浮层，不用闪一下。
     *  - 否则整屏截图：单窗口截图会漏掉输入法与弹窗，且窗口不铺满时坐标系不是屏幕坐标，
     *    会破坏「截图像素 = 屏幕像素 × scale」的约定。整屏截图前隐藏浮层，截完恢复。
     */
    @RequiresApi(30)
    private fun takeOnce(deadline: Long): ScreenshotTaker.Outcome {
        val remaining = (deadline - clock.nowMs()).coerceAtLeast(200)
        if (Build.VERSION.SDK_INT >= 34) {
            val wid = soleFullScreenAppWindow()
            if (wid != null) {
                val o = ScreenshotTaker.window(service, wid, remaining)
                if (o is ScreenshotTaker.Outcome.Ok || (o is ScreenshotTaker.Outcome.Failed && ScreenshotTaker.isSecureError(o.code))) {
                    return o
                }
                debug("单窗口截图失败，退回整屏：$o")
            }
        }
        overlay.setCaptureHidden(true)
        try {
            // 等一帧让窗口隐藏真正合成到屏幕上，否则截图里可能还留着浮层残影。
            clock.sleep(OVERLAY_HIDE_SETTLE_MS)
            return ScreenshotTaker.display(service, (deadline - clock.nowMs()).coerceAtLeast(200))
        } finally {
            overlay.setCaptureHidden(false)
        }
    }

    private fun soleFullScreenAppWindow(): Int? {
        val windows = try {
            service.windows ?: return null
        } catch (_: Throwable) {
            return null
        }
        try {
            val (sw, sh, _) = screenGeometry()
            val sorted = windows.sortedByDescending { it.layer }
            for (w in sorted) {
                when (w.type) {
                    AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> continue
                    AccessibilityWindowInfo.TYPE_APPLICATION -> {
                        if (!w.isActive) return null
                        val r = android.graphics.Rect().also { w.getBoundsInScreen(it) }
                        return if (r.left == 0 && r.top == 0 && r.width() == sw && r.height() == sh) w.id else null
                    }
                    // 状态栏/导航栏是 SYSTEM 窗口，总在应用之上；它们的存在不影响内容截图，跳过。
                    // 但系统弹窗（权限框等）也是 SYSTEM：无法区分，于是只有在没有 IME 时才走单窗口。
                    AccessibilityWindowInfo.TYPE_SYSTEM -> continue
                    else -> return null // 输入法、分屏分隔条等
                }
            }
            return null
        } finally {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                for (w in windows) runCatching { w.recycle() }
            }
        }
    }

    private fun encode(full: Bitmap): Screenshot {
        val scaled = ImageCodec.scaleToLongEdge(full, budgets.screenshotLongEdge)
        if (scaled.bitmap !== full) full.recycle()
        val bmp = scaled.bitmap
        val variance = ImageCodec.lumaVariance(bmp)
        val jpeg = ImageCodec.encodeJpeg(bmp, budgets.screenshotQuality)
        val quality = budgets.screenshotQuality
        val scale = scaled.scale
        return Screenshot(
            jpeg = jpeg,
            width = bmp.width,
            height = bmp.height,
            scale = scale,
            secure = false,
            lumaVariance = variance,
            // 闭包持有缩放后的位图；Screenshot 是一次工具调用内的短命对象，随 GC 释放。
            annotate = { marks -> ImageCodec.annotate(bmp, scale, marks, quality) },
        )
    }

    // ------------------------------------------------------------------ apps & intents

    override fun launchPackage(packageName: String): Boolean {
        val intent = pm.getLaunchIntentForPackage(packageName)
            ?: phoneError(ErrorCode.INVALID_ARGS, "找不到应用 $packageName 的启动入口")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return start(intent)
    }

    override fun openUri(uri: String): Boolean {
        val parsed = Uri.parse(uri.trim())
        val scheme = parsed.scheme?.lowercase()
            ?: phoneError(ErrorCode.INVALID_ARGS, "URL 缺少协议（如 https://）")
        // intent: 可以拼出任意组件与 extras，file:/content: 会把本 App 的文件交给别人——都不是「打开网页/深链」。
        if (scheme in BLOCKED_SCHEMES) phoneError(ErrorCode.INVALID_ARGS, "不允许打开 $scheme: 链接")
        val intent = Intent(Intent.ACTION_VIEW, parsed).apply {
            // BROWSABLE：只交给声明了可从网页打开的 Activity，防止深链直达 App 内部未公开页面。
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return start(intent)
    }

    override fun sendIntent(request: IntentRequest): Boolean {
        var intent = IntentFactory.build(request)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(pm) == null) {
            val fallback = IntentFactory.webSearchFallback(request)
            if (fallback != null && fallback.resolveActivity(pm) != null) {
                intent = fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            } else {
                phoneError(ErrorCode.UNSUPPORTED, "手机上没有能处理「${request.kind.wire}」的应用")
            }
        }
        return start(intent)
    }

    private fun start(intent: Intent): Boolean {
        markInjection()
        return try {
            service.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            phoneError(ErrorCode.UNSUPPORTED, "没有应用能打开它")
        } catch (e: SecurityException) {
            throw PhoneException(ErrorCode.ACTION_FAILED, "系统拒绝启动：${e.message ?: "SecurityException"}", cause = e)
        }
    }

    override fun launchableApps(): List<AppEntry> {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val infos = pm.queryIntentActivities(query, 0)
        val seen = HashSet<String>()
        val out = ArrayList<AppEntry>(infos.size)
        for (ri in infos) {
            val ai = ri.activityInfo?.applicationInfo ?: continue
            val pkg = ai.packageName ?: continue
            if (!seen.add(pkg)) continue
            val label = runCatching { ri.loadLabel(pm).toString() }.getOrNull()?.takeIf { it.isNotBlank() } ?: pkg
            labelCache.putIfAbsent(pkg, label)
            val system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            out += AppEntry(label = label, packageName = pkg, system = system)
        }
        out.sortWith(compareBy({ it.system }, { it.label }))
        return out
    }

    /** 包名 → 显示名；取不到（不可见/已卸载）为 null。 */
    fun labelOf(pkg: String): String? {
        labelCache[pkg]?.let { return it }
        val label = try {
            @Suppress("DEPRECATION")
            val ai = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(ai).toString()
        } catch (_: Throwable) {
            null
        } ?: return null
        if (labelCache.size > 512) labelCache.clear()
        labelCache[pkg] = label
        return label
    }

    // ------------------------------------------------------------------ clipboard & notify

    /**
     * Android 10+ 只有前台应用 / 默认输入法能读剪贴板；无障碍服务不在豁免名单里。
     * 读不到时返回 null，core 映射为 UNSUPPORTED。ClipboardManager 在部分版本要求 Looper，所以切主线程。
     */
    override fun clipboardGet(): String? = try {
        MainThread.call(MAIN_CALL_TIMEOUT_MS) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = cm?.primaryClip
            if (clip == null || clip.itemCount == 0) null else clip.getItemAt(0)?.coerceToText(context)?.toString()
        }
    } catch (_: Throwable) {
        null
    }

    override fun clipboardSet(text: String): Boolean = try {
        MainThread.call(MAIN_CALL_TIMEOUT_MS) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (cm == null) {
                false
            } else {
                cm.setPrimaryClip(ClipData.newPlainText("Vantaloom", text))
                true
            }
        } ?: false
    } catch (_: Throwable) {
        false
    }

    override fun postNotification(title: String, text: String): Boolean =
        OverlayNotification.postAgentMessage(context, title, text)

    // ------------------------------------------------------------------ settle

    override fun awaitSettle(quietMs: Long, maxMs: Long): Long {
        val start = clock.nowMs()
        while (true) {
            val now = clock.nowMs()
            val waited = now - start
            if (waited >= maxMs) return waited
            val sinceEvent = now - service.events.lastEventAtMs()
            if (sinceEvent >= quietMs) return waited
            // 睡到「可能安静」的时刻，但最多 50ms 一轮，避免错过新事件后多等。
            val sleep = minOf(quietMs - sinceEvent, maxMs - waited, SETTLE_POLL_MS).coerceAtLeast(5)
            clock.sleep(sleep)
        }
    }

    // ------------------------------------------------------------------ geometry

    /** (宽, 高, 旋转角度)。用 DisplayManager 的默认屏真实尺寸（含系统栏），与 getBoundsInScreen 同一坐标系。 */
    private fun screenGeometry(): Triple<Int, Int, Int> {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val d: Display? = dm?.getDisplay(Display.DEFAULT_DISPLAY)
        if (d == null) {
            val m = context.resources.displayMetrics
            return Triple(m.widthPixels, m.heightPixels, 0)
        }
        val p = Point()
        @Suppress("DEPRECATION")
        d.getRealSize(p)
        val rot = when (d.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return Triple(p.x, p.y, rot)
    }

    private fun debug(msg: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, msg)
    }

    companion object {
        private const val TAG = "VtlPhoneDriver"

        /** 保留最近几次 capture 的原生句柄（RawSnapshot 约定至少 2）。 */
        const val KEEP_CAPTURES = 2

        /** 单次 capture 的节点上限：再多 core 也渲染不下，徒增 binder 往返。 */
        const val MAX_NODES = 3000

        /** 递归深度上限：防异常深的自绘树把栈打爆。 */
        const val MAX_DEPTH = 80

        private const val MAX_GESTURE_MS = 10_000L
        private const val GESTURE_SLACK_MS = 2_000L
        private const val SCREENSHOT_RETRY_MS = 350L
        private const val OVERLAY_HIDE_SETTLE_MS = 48L
        private const val SETTLE_POLL_MS = 50L
        private const val MAIN_CALL_TIMEOUT_MS = 1_500L

        private val BLOCKED_SCHEMES = setOf("intent", "file", "content", "javascript", "android-app")

        /** AccessibilityAction id → NodeAction。按 API 级别填充（方向滚动 23+、IME_ENTER 30+）。 */
        private val actionIdToNode: Map<Int, NodeAction> by lazy {
            val m = HashMap<Int, NodeAction>()
            m[AccessibilityNodeInfo.ACTION_CLICK] = NodeAction.CLICK
            m[AccessibilityNodeInfo.ACTION_LONG_CLICK] = NodeAction.LONG_CLICK
            m[AccessibilityNodeInfo.ACTION_FOCUS] = NodeAction.FOCUS
            m[AccessibilityNodeInfo.ACTION_SET_TEXT] = NodeAction.SET_TEXT
            m[AccessibilityNodeInfo.ACTION_SCROLL_FORWARD] = NodeAction.SCROLL_FORWARD
            m[AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD] = NodeAction.SCROLL_BACKWARD
            m[AccessibilityNodeInfo.ACTION_SELECT] = NodeAction.SELECT
            m[AccessibilityNodeInfo.ACTION_EXPAND] = NodeAction.EXPAND
            m[AccessibilityNodeInfo.ACTION_COLLAPSE] = NodeAction.COLLAPSE
            m[AccessibilityNodeInfo.ACTION_DISMISS] = NodeAction.DISMISS
            m[AccessibilityNodeInfo.ACTION_COPY] = NodeAction.COPY
            m[AccessibilityNodeInfo.ACTION_PASTE] = NodeAction.PASTE
            if (Build.VERSION.SDK_INT >= 23) {
                m[AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id] = NodeAction.SCROLL_UP
                m[AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id] = NodeAction.SCROLL_DOWN
                m[AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id] = NodeAction.SCROLL_LEFT
                m[AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id] = NodeAction.SCROLL_RIGHT
                m[AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id] = NodeAction.SHOW_ON_SCREEN
            }
            if (Build.VERSION.SDK_INT >= 30) {
                m[AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id] = NodeAction.IME_ENTER
            }
            m
        }

        private fun mapWindowType(t: Int): WindowType = when (t) {
            AccessibilityWindowInfo.TYPE_APPLICATION -> WindowType.APPLICATION
            AccessibilityWindowInfo.TYPE_INPUT_METHOD -> WindowType.INPUT_METHOD
            AccessibilityWindowInfo.TYPE_SYSTEM -> WindowType.SYSTEM
            AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> WindowType.ACCESSIBILITY_OVERLAY
            AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> WindowType.SPLIT_SCREEN_DIVIDER
            TYPE_MAGNIFICATION_OVERLAY_COMPAT -> WindowType.MAGNIFICATION_OVERLAY
            else -> WindowType.UNKNOWN
        }

        /** AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY（API 33）的值；低版本不会出现这个值。 */
        private const val TYPE_MAGNIFICATION_OVERLAY_COMPAT = 6

        private fun android.graphics.Rect.toCore(): Rect = Rect(left, top, right, bottom)
    }
}
