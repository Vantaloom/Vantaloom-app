package online.timefiles.vantaloom.phone.host

import android.content.Context
import android.os.Process
import android.util.Log
import online.timefiles.vantaloom.phone.BuildConfig
import online.timefiles.vantaloom.phone.a11y.AndroidScreenDriver
import online.timefiles.vantaloom.phone.a11y.PhoneAccessibilityService
import online.timefiles.vantaloom.phone.core.PhoneEngine
import online.timefiles.vantaloom.phone.core.mcp.McpEndpoint
import online.timefiles.vantaloom.phone.core.mcp.PhoneMcpServer
import online.timefiles.vantaloom.phone.core.model.DisplayKind
import online.timefiles.vantaloom.phone.core.ports.Clock
import online.timefiles.vantaloom.phone.core.ports.TaskControl
import online.timefiles.vantaloom.phone.core.safety.SafetyPolicy
import online.timefiles.vantaloom.phone.core.session.FileAuditLog
import online.timefiles.vantaloom.phone.core.tools.Budgets
import online.timefiles.vantaloom.phone.overlay.ControlOverlay
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 生命周期中枢（进程内单例）。无障碍服务连上 → [attach] 组装全部部件并启动 MCP 服务；断开 → [detach] 拆掉。
 *
 * 组装关系：
 * ```
 * PhoneAccessibilityService ─┬─ AndroidScreenDriver ──(OverlayHooks)── ControlOverlay
 *                            └─ PhoneEngine(driver, overlay×3 端口, policy, FileAuditLog)
 *                                 └─ PhoneMcpServer → endpoint.json (0600) → EndpointListener / VantaloomRuntimeBridge
 * ```
 *
 * 为什么是单例 object：服务、通知接收器、demo/宿主 Activity 都要找到同一个引擎，而它们之间没有依赖注入。
 * 状态都放在 [Session] 里，attach/detach 只是替换这一个引用，避免半初始化状态被别处读到。
 *
 * 线程：attach/detach 由服务在主线程调用；MCP 服务的启动/停止（绑端口、写文件）切到 [io] 单线程执行，
 * 不阻塞主线程，也保证 start/stop 的先后顺序。
 */
object PhoneControlHost {
    private const val TAG = "VtlPhoneHost"

    /** 全局时钟。测试或仪器化场景可以在服务连接前替换。 */
    @Volatile
    var clock: Clock = Clock.System

    /** 预算（截图尺寸、超时等）。同上，可在连接前替换。 */
    @Volatile
    var budgets: Budgets = Budgets()

    /** 端点变化的监听者（Vantaloom 接入、demo 界面）。 */
    fun interface EndpointListener {
        /** endpoint 为 null = 服务已停止。在 [io] 线程回调，监听者自己切线程。 */
        fun onEndpointChanged(endpoint: McpEndpoint?)
    }

    /** 一次服务连接期间的全部部件。 */
    class Session internal constructor(
        val service: PhoneAccessibilityService,
        val driver: AndroidScreenDriver,
        val overlay: ControlOverlay,
        val engine: PhoneEngine,
        val server: PhoneMcpServer,
        val audit: FileAuditLog,
        val connectedAtMs: Long,
    )

    /** 给 demo / 前端看的状态摘要。 */
    data class Status(
        val serviceConnected: Boolean,
        val endpoint: McpEndpoint?,
        val endpointFile: String?,
        val connectedAtMs: Long,
        val lastError: String?,
    )

    @Volatile
    private var session: Session? = null

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var appContext: Context? = null

    private val listeners = CopyOnWriteArrayList<EndpointListener>()
    private val io: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "vantaloom-phone-host").apply { isDaemon = true }
    }

    /** 当前引擎；服务未连接为 null。demo 的调试控制台直接调 engine.call。 */
    val engine: PhoneEngine? get() = session?.engine

    /** 当前任务控制（浮层/通知/宿主的停止按钮）。 */
    val control: TaskControl? get() = session?.engine?.control

    /** 当前会话（调试用）。 */
    fun session(): Session? = session

    /**
     * 宿主（Application.onCreate 或 Activity）调用：记住 applicationContext，便于服务未连接时也能
     * 读策略、读 endpoint 路径。可选；服务连接时也会自动设置。
     */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // ------------------------------------------------------------------ 生命周期

    /** 由 [PhoneAccessibilityService.onServiceConnected] 调用。重复调用会先拆掉旧会话。 */
    fun attach(service: PhoneAccessibilityService) {
        session?.let { if (it.service !== service) detach(it.service) else return }
        val ctx = service.applicationContext
        appContext = ctx
        try {
            val c = clock
            val b = budgets
            val driver = AndroidScreenDriver(service, c, b)
            val overlay = ControlOverlay(service, c) { driver.lastInjectionAtMs() }
            driver.overlay = overlay
            overlay.probe()
            val policy = SafetyPolicyStore(ctx).load()
            val audit = FileAuditLog(PhonePaths.auditDir(ctx))
            val engine = PhoneEngine(
                drivers = mapOf(DisplayKind.MAIN to driver),
                confirmation = overlay,
                handoff = overlay,
                status = overlay,
                ownPackage = ctx.packageName,
                policy = policy,
                audit = audit,
                clock = c,
                budgets = b,
            )
            overlay.control = engine.control
            val server = PhoneMcpServer(engine)
            val s = Session(service, driver, overlay, engine, server, audit, c.nowMs())
            session = s
            lastError = null
            io.execute { startServer(s) }
        } catch (t: Throwable) {
            lastError = "组装失败：${t.javaClass.simpleName}: ${t.message}"
            Log.e(TAG, "attach 失败", t)
        }
    }

    /** 由服务的 onUnbind/onDestroy 调用。只拆属于这个服务实例的会话（防旧实例迟到的回调拆掉新会话）。 */
    fun detach(service: PhoneAccessibilityService) {
        val s = session ?: return
        if (s.service !== service) return
        session = null
        try {
            s.engine.shutdown()
        } catch (t: Throwable) {
            Log.w(TAG, "engine.shutdown 失败", t)
        }
        s.overlay.destroy()
        s.driver.release()
        io.execute { stopServer(s) }
    }

    private fun startServer(s: Session) {
        if (session !== s) return
        val ctx = s.service.applicationContext
        try {
            val ep = s.server.start()
            PhonePaths.writePrivate(PhonePaths.endpointFile(ctx), ep.toJson(Process.myPid()))
            debug("MCP 服务已启动：${ep.url}")
            notifyListeners(ep)
        } catch (t: Throwable) {
            lastError = "MCP 服务启动失败：${t.javaClass.simpleName}: ${t.message}"
            Log.e(TAG, "startServer 失败", t)
        }
    }

    private fun stopServer(s: Session) {
        val ctx = s.service.applicationContext
        try {
            s.server.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "server.stop 失败", t)
        }
        s.audit.close()
        // 只在没有新会话接手时删文件：新会话可能已写入新的 endpoint.json。
        if (session == null) {
            PhonePaths.endpointFile(ctx).delete()
            notifyListeners(null)
        }
    }

    /**
     * 重启 MCP 服务（换端口与 token）。用于怀疑 token 泄露、或 runtime 侧连接异常时手动轮换。
     */
    fun restartServer() {
        val s = session ?: return
        io.execute {
            try {
                s.server.stop()
            } catch (_: Throwable) {
            }
            startServer(s)
        }
    }

    // ------------------------------------------------------------------ 监听者

    /** 注册端点监听；立即用当前值回调一次（在 io 线程）。 */
    fun addEndpointListener(l: EndpointListener) {
        listeners.addIfAbsent(l)
        val ep = session?.server?.endpoint
        io.execute { safeCall(l, ep) }
    }

    fun removeEndpointListener(l: EndpointListener) {
        listeners.remove(l)
    }

    private fun notifyListeners(ep: McpEndpoint?) {
        for (l in listeners) safeCall(l, ep)
    }

    private fun safeCall(l: EndpointListener, ep: McpEndpoint?) {
        try {
            l.onEndpointChanged(ep)
        } catch (t: Throwable) {
            Log.w(TAG, "EndpointListener 抛异常", t)
        }
    }

    // ------------------------------------------------------------------ 外部操作

    /** 停止当前任务（通知按钮、宿主停止键）。没有会话时无操作。 */
    fun stopTask(reason: String) {
        try {
            session?.engine?.control?.stop(reason)
        } catch (t: Throwable) {
            Log.w(TAG, "stopTask 失败", t)
        }
    }

    /** 更新并持久化安全策略，立即对后续调用生效。 */
    fun updatePolicy(context: Context, policy: SafetyPolicy) {
        SafetyPolicyStore(context).save(policy)
        session?.engine?.updatePolicy(policy)
    }

    /** 清空审计日志（demo）。先关句柄再删，下次 record 会重新打开。 */
    fun clearAudit(context: Context) {
        session?.audit?.close()
        val dir = PhonePaths.auditDir(context)
        dir.listFiles()?.forEach { it.delete() }
    }

    /** 状态摘要。 */
    fun status(): Status {
        val s = session
        val ctx = appContext
        return Status(
            serviceConnected = s != null && s.service.connected,
            endpoint = s?.server?.endpoint,
            endpointFile = ctx?.let { PhonePaths.endpointFile(it).absolutePath },
            connectedAtMs = s?.connectedAtMs ?: 0L,
            lastError = lastError,
        )
    }

    private fun debug(msg: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, msg)
    }
}
