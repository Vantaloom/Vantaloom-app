package online.timefiles.vantaloom.phone.core.tools.impl

import online.timefiles.vantaloom.phone.core.ServerInfo
import online.timefiles.vantaloom.phone.core.driver.Screenshot
import online.timefiles.vantaloom.phone.core.model.DisplayKind
import online.timefiles.vantaloom.phone.core.model.ErrorCode
import online.timefiles.vantaloom.phone.core.model.NodeAction
import online.timefiles.vantaloom.phone.core.model.PhoneException
import online.timefiles.vantaloom.phone.core.model.phoneError
import online.timefiles.vantaloom.phone.core.observe.ElementState
import online.timefiles.vantaloom.phone.core.observe.Observation
import online.timefiles.vantaloom.phone.core.observe.ObservedElement
import online.timefiles.vantaloom.phone.core.ports.TaskState
import online.timefiles.vantaloom.phone.core.tools.Budgets
import online.timefiles.vantaloom.phone.core.tools.SemanticRisk
import online.timefiles.vantaloom.phone.core.tools.ToolArgs
import online.timefiles.vantaloom.phone.core.tools.ToolContext
import online.timefiles.vantaloom.phone.core.tools.ToolImage
import online.timefiles.vantaloom.phone.core.tools.ToolKind
import online.timefiles.vantaloom.phone.core.tools.ToolResult

/** 解析 display 参数；只认 main/background。 */
internal fun ToolArgs.display(): DisplayKind =
    DisplayKind.fromWire(enumOf("display", listOf("main", "background"), "main"))!!

/**
 * 后台屏没有驱动时的统一错误（协议 §11）。放在 core 里而不是等 ctx.driver 抛，
 * 是为了 details 里给出一致的引导信息。
 */
internal fun EngineContext.requireDisplay(display: DisplayKind) {
    if (display !in displays()) {
        if (display == DisplayKind.BACKGROUND) {
            phoneError(
                ErrorCode.UNSUPPORTED,
                "后台虚拟屏尚未启用",
                mapOf("requires" to listOf("Shizuku", "Android 14+"), "hint" to "后台虚拟屏需要 Shizuku 授权且系统为 Android 14 及以上；v1 只支持主屏"),
            )
        }
        phoneError(ErrorCode.UNSUPPORTED, "没有 ${display.wire} 屏的驱动")
    }
}

// =============================================================================================== status

/** 协议 §4 `status`：不需要任务、不受门控，模型任何时候都能查。不抓树、不开任务。 */
class StatusTool : BaseTool("status", ToolKind.OBSERVE, SemanticRisk.SAFE) {
    override val description = "查看手机控制是否可用：无障碍服务、锁屏、截图能力、当前任务是否被用户暂停/停止。出错时先调它定位原因。"
    override val inputSchema = Schema.obj()
    override val gated get() = false
    override val needsScreen get() = false

    override fun call(args: ToolArgs, ctx: ToolContext): ToolResult {
        val e = ctx.engine()
        val main = e.availability(DisplayKind.MAIN)
        val task = e.taskInfo()
        val displays = e.displays()
        val lines = ArrayList<String>()
        if (main == null) {
            lines += "主屏驱动：未接入"
        } else {
            lines += "无障碍服务：" + if (main.serviceConnected) "已连接" else "未开启" + (main.reason?.let { "（$it）" } ?: "")
            lines += "浮层：" + if (main.overlayAttached) "在场" else "未显示（动作类工具不可用）"
            lines += "屏幕：" + when {
                !main.screenOn -> "熄屏"
                main.locked -> "已锁屏"
                else -> "亮屏已解锁"
            }
            lines += "系统：Android API ${main.apiLevel} · ${main.manufacturer} ${main.model}" + (main.romHint?.let { " · $it" } ?: "")
            lines += "截图：" + if (main.canScreenshot) "支持" else "不支持（只有文本观察）"
            lines += "输入法回车（type submit）：" + if (main.canImeEnter) "支持" else "不支持"
        }
        lines += "可用屏幕：" + displays.joinToString("、") { it.wire } +
            if (DisplayKind.BACKGROUND !in displays) "（后台屏需要 Shizuku + Android 14）" else ""
        lines += "任务：" + when (task.state) {
            TaskState.NONE -> "无（第一次动作时自动开始）" + (task.lastEndReason?.let { "，上一个任务结束于：$it" } ?: "")
            TaskState.RUNNING -> "进行中 ${task.taskId} · 已用动作 ${task.actionCount}/${task.maxActions} · 空闲 ${task.idleRemainingMs / 1000}s 后自动结束"
            TaskState.PAUSED -> "已被用户暂停（等用户点「继续」）"
            TaskState.STOPPED -> "已被用户停止（${task.lastEndReason ?: ""}）：请停手并向用户汇报"
        }
        lines += "服务：${ServerInfo.NAME} ${ServerInfo.VERSION}"

        val structured = linkedMapOf<String, Any?>(
            "ok" to true,
            "action" to "status",
            "server" to mapOf("name" to ServerInfo.NAME, "version" to ServerInfo.VERSION),
            "displays" to displays.map { it.wire },
            "availability" to main?.let {
                linkedMapOf(
                    "serviceConnected" to it.serviceConnected,
                    "overlayAttached" to it.overlayAttached,
                    "screenOn" to it.screenOn,
                    "locked" to it.locked,
                    "apiLevel" to it.apiLevel.toLong(),
                    "canScreenshot" to it.canScreenshot,
                    "canImeEnter" to it.canImeEnter,
                    "manufacturer" to it.manufacturer,
                    "model" to it.model,
                    "romHint" to it.romHint,
                    "reason" to it.reason,
                )
            },
            "task" to linkedMapOf(
                "state" to task.state.name.lowercase(),
                "id" to task.taskId,
                "actions" to task.actionCount.toLong(),
                "maxActions" to task.maxActions.toLong(),
                "idleRemainingMs" to task.idleRemainingMs,
                "lastEndReason" to task.lastEndReason,
            ),
        )
        return ToolResult(lines.joinToString("\n"), structured)
    }
}

// =============================================================================================== apps

/** 协议 §4 `apps`：可启动应用列表。不受任务门控（只读 PackageManager，不碰屏幕）。 */
class AppsTool : BaseTool("apps", ToolKind.OBSERVE, SemanticRisk.SAFE) {
    override val description = "列出可启动的 App（名称 + 包名）。用 query 按名称或包名过滤。打开 App 用 open。"
    override val inputSchema = Schema.obj(
        "query" to Schema.str("可选，按名称或包名包含匹配（不区分大小写），如「微信」「maps」", maxLength = 100),
        "limit" to Schema.int("最多返回几条，默认 50，最大 200", min = 1, max = 200, default = 50),
    )
    override val gated get() = false
    override val needsScreen get() = false

    override fun call(args: ToolArgs, ctx: ToolContext): ToolResult {
        val q = args.optString("query")?.trim()?.lowercase().orEmpty()
        val limit = args.intIn("limit", 1..200, 50)
        val all = ctx.driver(DisplayKind.MAIN).launchableApps()
        val matched = all.filter { q.isEmpty() || q in it.label.lowercase() || q in it.packageName.lowercase() }
            // 用户应用排前、精确命中排前：模型多半只看前几行。
            .sortedWith(compareBy({ !(q.isNotEmpty() && (it.label.lowercase() == q || it.packageName.lowercase() == q)) }, { it.system }, { it.label }))
        val shown = matched.take(limit)
        val sb = StringBuilder()
        sb.append(if (q.isEmpty()) "可启动应用 ${all.size} 个" else "匹配「$q」的应用 ${matched.size} 个")
        if (shown.size < matched.size) sb.append("（列出前 ${shown.size} 个）")
        for (a in shown) sb.append("\n- ").append(a.label).append(" (").append(a.packageName).append(')').append(if (a.system) " 系统" else "")
        if (matched.isEmpty() && q.isNotEmpty()) sb.append("\n没有匹配的应用；可以换个关键词，或不带 query 看全部。")
        val structured = linkedMapOf<String, Any?>(
            "ok" to true,
            "action" to "apps",
            "total" to matched.size.toLong(),
            "apps" to shown.map { mapOf("label" to it.label, "package" to it.packageName, "system" to it.system) },
        )
        return ToolResult(sb.toString(), structured)
    }
}

// =============================================================================================== screen

/**
 * 协议 §4 `screen`：观察当前屏幕。image=true 时附带序号标注截图（协议 §10）。
 * 截图安全规则：安全窗口、近乎全黑、屏上有打码对象 → 不附图，文本里说明原因；不当作错误，
 * 因为文字观察仍然有用。
 */
class ScreenTool : BaseTool("screen", ToolKind.OBSERVE, SemanticRisk.SAFE) {
    override val description =
        "观察手机当前屏幕，返回带序号的元素列表（ref 形如 s12:5）。动作后会自动返回新屏幕，一般不必再调。" +
            "界面以图形为主时用 image=true 看标注截图。"
    override val inputSchema = Schema.obj(
        "detail" to Schema.bool("每行追加屏幕坐标 @x,y,w,h 和 id。排查定位问题时才用，会变长", false),
        "image" to Schema.bool("附带编号标注截图（JPEG）。截图里的像素坐标可以给 tap_xy/swipe 用；安全窗口或屏上有卡号时不会附图", false),
        "display" to Schema.enum("观察哪块屏：main 主屏（默认）；background 后台虚拟屏（需 Shizuku + Android 14，v1 未启用）", listOf("main", "background"), "main"),
    )

    override fun budgetMs(b: Budgets, args: ToolArgs): Long =
        if (args.raw["image"] == true) b.observeMs * 2 else b.observeMs

    override fun call(args: ToolArgs, ctx: ToolContext): ToolResult {
        val e = ctx.engine()
        val display = args.display()
        e.requireDisplay(display)
        val detail = args.bool("detail")
        val wantImage = args.bool("image")
        val obs = e.observe(display, detail)
        if (!wantImage) return ToolResult(obs.text, linkedMapOf("ok" to true, "action" to "screen", "observation" to obs.structured))
        val (image, note, geometry) = captureAnnotated(e, display, obs)
        val structured = linkedMapOf<String, Any?>("ok" to true, "action" to "screen", "observation" to obs.structured)
        if (geometry != null) structured["image"] = geometry
        val text = if (note != null) obs.text + "\n" + note else obs.text
        return ToolResult(text, structured, listOfNotNull(image))
    }

    /**
     * 截图 + 标注。返回 (图片, 不附图的说明, structured.image)。
     * 截图没有一致性保证：观察和截图之间界面可能变了。可接受，因为 tap_xy 每次都要用户确认。
     */
    private fun captureAnnotated(e: EngineContext, display: DisplayKind, obs: Observation): Triple<ToolImage?, String?, Map<String, Any?>?> {
        val avail = e.availability(display)
        if (avail != null && !avail.canScreenshot) return Triple(null, "（本机不支持截图，只有文本观察；截图需要 Android 11+）", null)
        if (obs.snapshot.secureWindow) return Triple(null, "（截图被系统禁止：当前窗口是安全窗口）", null)
        if (obs.hasMasked) return Triple(null, "（屏幕上有银行卡号/身份证号等敏感信息，不附截图）", null)
        val shot: Screenshot = try {
            e.status.setStandAside(true)
            try {
                e.driver(display).screenshot(e.budgets.observeMs)
            } finally {
                e.status.setStandAside(false)
            }
        } catch (ex: PhoneException) {
            if (ex.code == ErrorCode.UNSUPPORTED || ex.code == ErrorCode.SECURE_WINDOW) {
                return Triple(null, "（截图不可用：${ex.message}）", null)
            }
            throw ex
        }
        if (shot.secure) return Triple(null, "（截图被系统禁止）", null)
        // lumaVariance = -1 表示未计算，不算全黑。
        if (shot.lumaVariance in 0.0..2.0) return Triple(null, "（截图被系统禁止：画面几乎全黑）", null)
        val jpeg = shot.jpeg ?: return Triple(null, "（截图失败：系统没有返回像素）", null)
        val marks = obs.elements
            .filter { !it.bounds.isEmpty && ElementState.OFFSCREEN !in it.states }
            .map { Screenshot.Mark(it.index.toString(), it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom, it.needsConfirm) }
        val annotated = try {
            shot.annotate?.invoke(marks) ?: jpeg
        } catch (_: Throwable) {
            jpeg // 标注失败时退回未标注的图：有图比没图强，序号仍可从文本对照。
        }
        if (annotated.size > MAX_IMAGE_BYTES) return Triple(null, "（截图过大，已省略）", null)
        e.registry.attachImageGeometry(obs.screenId, shot.width, shot.height, shot.scale)
        val geometry = linkedMapOf<String, Any?>(
            "w" to shot.width.toLong(), "h" to shot.height.toLong(), "scale" to shot.scale, "screen" to obs.screenId,
        )
        val note = "截图 ${shot.width}×${shot.height}（屏幕像素×${String.format(java.util.Locale.ROOT, "%.3f", shot.scale)}），tap_xy/swipe 用截图像素坐标并传 observation=\"${obs.screenId}\""
        return Triple(ToolImage("image/jpeg", annotated), note, geometry)
    }

    private companion object {
        /** 协议 §1：图片 base64 后不超过 4 MB，即原始字节 ≤ 3 MB。 */
        const val MAX_IMAGE_BYTES = 3 * 1024 * 1024
    }
}

// =============================================================================================== collect

/**
 * 协议 §4 `collect`：滚动一个容器并累积去重后的子项，用于读长列表（聊天记录、搜索结果）。
 *
 * 只读语义：不点击，但滚动会改变界面位置，所以走动作串行锁（避免和 tap 交错）。
 * 不过安全门：滚动不产生副作用，而且在确认卡上反复问「允许滚动吗」没有意义。
 * 但它会开任务并计一次动作（[startsTask]）：屏幕在自动滚动，浮层必须在场，用户才能看见并停止它。
 *
 * 去重键用「角色 + 原始文本」而不是指纹：指纹含 bounds，滚动后同一项的位置会变。
 * 代价是完全相同文本的两行（如两条「好的」）会合并；同一屏内的重复项保留，只跨屏去重。
 */
class CollectTool : BaseTool("collect", ToolKind.OBSERVE, SemanticRisk.SAFE) {
    override val description =
        "自动滚动一个列表并汇总去重后的内容（只读，不点击）。适合读长聊天记录、搜索结果、设置项。" +
            "ref 必须是「可上下滑/可左右滑」的容器；结束后屏幕停在最后一页。"
    override val inputSchema = Schema.obj(
        "ref" to Schema.str("可滚动容器的 ref（来自最新一次观察，如 s12:3）"),
        "max_pages" to Schema.int("最多滚几页，默认 5", min = 1, max = 30, default = 5),
        "until" to Schema.str("可选：出现这段文本就停（包含匹配，不区分大小写）", maxLength = 200),
        "direction" to Schema.enum("滚动方向，默认 down（往下看更多）；聊天记录往上翻旧消息用 up", listOf("up", "down", "left", "right"), "down"),
    )
    override val serialized get() = true
    override val needsOverlay get() = true
    override val startsTask get() = true
    override fun budgetMs(b: Budgets, args: ToolArgs): Long = b.collectMs
    override fun activityLine(args: ToolArgs): String = "滚动收集列表内容"

    override fun call(args: ToolArgs, ctx: ToolContext): ToolResult {
        val e = ctx.engine()
        val ref = args.string("ref")
        val maxPages = args.intIn("max_pages", 1..30, 5)
        val until = args.optString("until")?.trim()?.takeIf { it.isNotEmpty() }
        val direction = args.enumOf("direction", listOf("up", "down", "left", "right"), "down")
        val display = displayOfRef(ref)
        e.requireDisplay(display)
        val driver = e.driver(display)

        var target = e.registry.resolve(ref)
        if (!target.scrollable && target.actions.none { it in SCROLL_ACTIONS }) {
            phoneError(ErrorCode.NOT_ACTIONABLE, "${describe(target)} 不可滚动；请传标记为「可上下滑」或「可左右滑」的容器")
        }
        var obs: Observation

        val seen = HashSet<String>()
        val items = ArrayList<String>()
        var pages = 0
        var stopReason = "已滚动 $maxPages 页"
        var hit: String? = null

        // 返回本页新增项数。跨页去重（seen 只在本页处理完后并入），同页重复项保留。
        fun harvest(o: Observation, container: ObservedElement): Int {
            var added = 0
            val keysThisPage = HashSet<String>()
            for (el in o.elements) {
                if (el.nodeId == container.nodeId) continue
                if (!isInside(el, container)) continue
                if (el.rawLabel.isBlank()) continue
                val key = el.role.wire + "|" + el.rawLabel
                if (key in seen) continue
                keysThisPage += key
                if (items.size < MAX_ITEMS) {
                    items += "${el.role.zh}「${clip(el.rawLabel, 200)}」"
                    added++
                }
                if (until != null && hit == null && el.rawLabel.contains(until, ignoreCase = true)) hit = el.rawLabel
            }
            seen += keysThisPage
            return added
        }

        // 先重定位一次：确认 ref 仍指向同一个容器（协议 §2），并拿到最新的 captureId。
        // 之后每页的 obs 都是刚观察并登记的最新屏，不必再重新抓树。
        val first = e.registry.relocate(driver, target, e.budgets, e.gate)
        target = first.first
        obs = first.second
        harvest(obs, target)
        while (hit == null && pages < maxPages) {
            e.checkpoint()
            if (e.remainingMs() < COLLECT_STEP_RESERVE_MS) { stopReason = "时间预算用尽"; break }
            if (items.size >= MAX_ITEMS) { stopReason = "已收集 $MAX_ITEMS 项上限"; break }
            val moved = e.standAside { ScrollMechanics.scroll(driver, target, direction, half = false, screenW = obs.snapshot.screenWidth, screenH = obs.snapshot.screenHeight, budgets = e.budgets) }
            if (moved == null) { stopReason = "容器无法继续滚动"; break }
            pages++
            obs = e.settleAndObserve(display)
            // 重新找到容器：滚动后容器自身的指纹通常不变（bounds 不动），按指纹优先、bounds 兼容。
            val prev = target
            val again = obs.elements.firstOrNull { it.fingerprint == prev.fingerprint }
                ?: obs.elements.firstOrNull { it.bounds == prev.bounds && it.scrollable }
            if (again == null) { stopReason = "滚动后找不到原容器（界面可能已跳转）"; break }
            target = again
            val added = harvest(obs, target)
            if (added == 0) { stopReason = "没有新内容（已到尽头）"; break }
        }
        if (hit != null) stopReason = "找到「$until」"

        val sb = StringBuilder()
        sb.append("收集到 ${items.size} 项（滚动 $pages 页，$stopReason）")
        items.forEachIndexed { i, s -> sb.append('\n').append(i + 1).append(". ").append(s) }
        if (until != null && hit == null) sb.append("\n未找到「$until」。")
        sb.append("\n\n").append(obs.text)
        val structured = linkedMapOf<String, Any?>(
            "ok" to true,
            "action" to "collect",
            "pages" to pages.toLong(),
            "items" to items,
            "found" to (hit != null),
            "stopReason" to stopReason,
            "observation" to obs.structured,
        )
        return ToolResult(sb.toString(), structured)
    }

    /** 元素中心在容器内：比走父链便宜，且对「可点击容器吸收子文本」的折叠结果同样有效。 */
    private fun isInside(el: ObservedElement, container: ObservedElement): Boolean =
        container.bounds.contains(el.bounds.centerX, el.bounds.centerY)

    private companion object {
        const val MAX_ITEMS = 600
        const val COLLECT_STEP_RESERVE_MS = 3_000L
        val SCROLL_ACTIONS = setOf(
            NodeAction.SCROLL_FORWARD, NodeAction.SCROLL_BACKWARD, NodeAction.SCROLL_UP,
            NodeAction.SCROLL_DOWN, NodeAction.SCROLL_LEFT, NodeAction.SCROLL_RIGHT,
        )
    }
}

// =============================================================================================== wait

/**
 * 协议 §4 `wait`：轮询观察直到条件满足。三个条件可组合，全部满足才算成功（AND）——
 * 「等微信到前台且出现发送」这种组合比 OR 更常见，而 OR 可以拆成多次调用。
 * 不计动作、不开任务。轮询间隔 300 ms，走 Clock。
 */
class WaitTool : BaseTool("wait", ToolKind.OBSERVE, SemanticRisk.SAFE) {
    override val description =
        "等待界面变化：某段文本出现（text）、消失（gone）或某个 App 到前台（package）。多个条件须同时满足。" +
            "超时返回 TIMEOUT 并附当前屏幕。用于等加载、等跳转，不要用它干等。"
    override val inputSchema = Schema.obj(
        "text" to Schema.str("等这段文本出现在屏幕上（包含匹配，不区分大小写）", maxLength = 200),
        "gone" to Schema.str("等这段文本从屏幕上消失（如「加载中」）", maxLength = 200),
        "package" to Schema.str("等这个包名的 App 到前台（如 com.tencent.mm）", maxLength = 200),
        "timeout_ms" to Schema.int("最长等多久，默认 5000，最大 15000", min = 300, max = 15_000, default = 5_000),
    )
    override fun budgetMs(b: Budgets, args: ToolArgs): Long =
        ((args.raw["timeout_ms"] as? Number)?.toLong() ?: 5_000L).coerceIn(300, b.waitMaxMs) + b.observeMs

    override fun call(args: ToolArgs, ctx: ToolContext): ToolResult {
        val e = ctx.engine()
        val text = args.optString("text")?.trim()?.takeIf { it.isNotEmpty() }
        val gone = args.optString("gone")?.trim()?.takeIf { it.isNotEmpty() }
        val pkg = args.optString("package")?.trim()?.takeIf { it.isNotEmpty() }
        if (text == null && gone == null && pkg == null) phoneError(ErrorCode.INVALID_ARGS, "text、gone、package 至少给一个")
        val timeout = args.intIn("timeout_ms", 300..e.budgets.waitMaxMs.toInt(), 5_000).toLong()
        val start = e.clock.nowMs()
        var obs: Observation
        var polls = 0
        while (true) {
            e.checkpoint()
            obs = e.observe(DisplayKind.MAIN)
            polls++
            if (satisfied(obs, text, gone, pkg)) break
            val elapsed = e.clock.nowMs() - start
            if (elapsed + POLL_MS > timeout) {
                val cond = listOfNotNull(text?.let { "出现「$it」" }, gone?.let { "「$it」消失" }, pkg?.let { "$it 到前台" }).joinToString("、")
                throw PhoneException(
                    ErrorCode.TIMEOUT,
                    "等了 ${elapsed}ms 仍未等到 $cond",
                    mapOf("elapsedMs" to elapsed, "polls" to polls.toLong()),
                    observationText = obs.text,
                )
            }
            e.clock.sleep(POLL_MS)
        }
        val elapsed = e.clock.nowMs() - start
        return ToolResult(
            "已等到（${elapsed}ms）\n\n" + obs.text,
            linkedMapOf("ok" to true, "action" to "wait", "elapsedMs" to elapsed, "observation" to obs.structured),
        )
    }

    private fun satisfied(obs: Observation, text: String?, gone: String?, pkg: String?): Boolean {
        if (pkg != null && obs.packageName != pkg) return false
        if (text != null && !screenHasText(obs, text)) return false
        if (gone != null && screenHasText(obs, gone)) return false
        return true
    }

    /** 在全部元素（包括文本里被截掉的）里找。 */
    private fun screenHasText(obs: Observation, needle: String): Boolean =
        obs.elements.any { it.rawLabel.contains(needle, ignoreCase = true) }

    private companion object {
        const val POLL_MS = 300L
    }
}
