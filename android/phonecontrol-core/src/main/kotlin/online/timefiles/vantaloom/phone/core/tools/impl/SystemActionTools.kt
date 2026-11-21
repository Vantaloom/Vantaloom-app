package online.timefiles.vantaloom.phone.core.tools.impl

import online.timefiles.vantaloom.phone.core.driver.AppEntry
import online.timefiles.vantaloom.phone.core.driver.GlobalAction
import online.timefiles.vantaloom.phone.core.driver.IntentKind
import online.timefiles.vantaloom.phone.core.driver.IntentRequest
import online.timefiles.vantaloom.phone.core.model.DisplayKind
import online.timefiles.vantaloom.phone.core.model.ErrorCode
import online.timefiles.vantaloom.phone.core.model.PhoneException
import online.timefiles.vantaloom.phone.core.model.Rect
import online.timefiles.vantaloom.phone.core.model.phoneError
import online.timefiles.vantaloom.phone.core.observe.Observation
import online.timefiles.vantaloom.phone.core.ports.ActivityPhase
import online.timefiles.vantaloom.phone.core.ports.HandoffKind
import online.timefiles.vantaloom.phone.core.ports.HandoffRequest
import online.timefiles.vantaloom.phone.core.ports.HandoffResult
import online.timefiles.vantaloom.phone.core.safety.ActionIntent
import online.timefiles.vantaloom.phone.core.tools.Budgets
import online.timefiles.vantaloom.phone.core.tools.SemanticRisk
import online.timefiles.vantaloom.phone.core.tools.ToolArgs
import online.timefiles.vantaloom.phone.core.tools.ToolContext
import online.timefiles.vantaloom.phone.core.tools.ToolKind
import online.timefiles.vantaloom.phone.core.tools.ToolResult

// =============================================================================================== tap_xy / swipe

/**
 * 坐标动作的公共前序：observation 必须是该 display 最新且带截图的屏，坐标按截图几何映射回屏幕。
 * 坐标在图外直接 INVALID_ARGS：悄悄夹边会点到屏幕边缘的东西（返回键、状态栏），比报错更糟。
 */
private fun EngineContext.mapPoints(screenId: String, points: List<Pair<Int, Int>>): Pair<Observation, List<Pair<Int, Int>>> {
    val obs = registry.requireLatestScreen(screenId)
    val geo = registry.imageGeometry(screenId)
        ?: phoneError(ErrorCode.INVALID_ARGS, "屏幕 $screenId 没有附带截图；先调 screen(image=true) 拿到截图，再用截图像素坐标")
    val mapped = points.map { (x, y) ->
        if (!geo.contains(x, y)) phoneError(ErrorCode.INVALID_ARGS, "坐标 ($x,$y) 不在截图 ${geo.width}×${geo.height} 范围内")
        geo.toScreen(x, y)
    }
    return obs to mapped
}

/** 协议 §4 `tap_xy`：截图像素坐标点击，每次都确认（坐标没有语义可审）。 */
class TapXyTool : BaseTool("tap_xy", ToolKind.ACT, SemanticRisk.RISKY) {
    override val description =
        "按截图像素坐标点击，只在元素列表里找不到目标时使用。先 screen(image=true)，用那张截图的坐标并传它的屏幕号。" +
            "每次都会在手机上弹确认卡。"
    override val inputSchema = Schema.obj(
        "x" to Schema.int("截图像素 x（不是屏幕像素）", min = 0),
        "y" to Schema.int("截图像素 y", min = 0),
        "observation" to Schema.str("这张截图所属的屏幕号（如 s12），必须是最新一次带截图的观察"),
        "long" to Schema.bool("长按。默认 false", false),
        required = listOf("x", "y", "observation"),
    )
    override fun activityLine(args: ToolArgs): String = "按坐标点击"

    override fun call(args: ToolArgs, ctx: ToolContext): ToolResult {
        val e = ctx.engine()
        val x = args.long("x").toInt()
        val y = args.long("y").toInt()
        val screenId = args.string("observation")
        val long = args.bool("long")
        val display = displayOfScreen(screenId)
        e.requireDisplay(display)
        val (obs, pts) = e.mapPoints(screenId, listOf(x to y))
        val (sx, sy) = pts[0]
        val hit = obs.elements.filter { it.bounds.contains(sx, sy) }.minByOrNull { it.bounds.area() }
        val verb = if (long) "长按" else "点击"
        val where = hit?.let { "（落在 ${describe(it)} 上）" } ?: ""
        e.guard(
            ActionIntent(tool = name, packageName = hit?.packageName ?: obs.packageName, element = hit, screen = obs, coordinate = true),
            "$verb ${inApp(obs)}屏幕坐标 ($sx,$sy)$where",
            Rect(sx - 24, sy - 24, sx + 24, sy + 24),
            forceConfirm = true,
        )
        // 确认期间界面可能已变：坐标动作无法重定位，所以要求前台包没变，否则宁可报 STALE_SCREEN。
        val now = e.driver(display).capture(e.budgets.captureBudgetMs)
        if (now.foregroundPackage != obs.packageName) {
            val fresh = e.observe(display)
            throw PhoneException(ErrorCode.STALE_SCREEN, "确认期间前台应用变了，坐标已失效；请重新截图", observationText = fresh.text)
        }
        e.checkpoint()
        val ms = if (long) e.budgets.longPressMs else e.budgets.tapMs
        val ok = e.standAside { e.driver(display).tap(sx, sy, ms) }
        if (!ok) throw PhoneException(ErrorCode.ACTION_FAILED, "手势被系统拒绝", observationText = obs.text)
        val (newObs, note) = e.settleAndObserveSoft(display)
        return actionResult(name, "已${verb}屏幕坐标 ($sx,$sy)$where", newObs, "gesture", note, mapOf("screenPoint" to listOf(sx.toLong(), sy.toLong())))
    }
}

/** 协议 §4 `swipe`：截图像素坐标滑动，每次都确认。 */
class SwipeTool : BaseTool("swipe", ToolKind.ACT, SemanticRisk.RISKY) {
    override val description =
        "按截图像素坐标滑动（如拖动滑块、解锁图案以外的手势）。普通滚动请用 scroll。" +
            "需先 screen(image=true)；每次都会在手机上弹确认卡。"
    override val inputSchema = Schema.obj(
        "from" to Schema.point("起点 [x, y]，截图像素"),
        "to" to Schema.point("终点 [x, y]，截图像素"),
        "duration_ms" to Schema.int("滑动时长，默认 350；越长越像拖动，越短越像快速甩动", min = 100, max = 3_000, default = 350),
        "observation" to Schema.str("截图所属的屏幕号（如 s12），必须是最新一次带截图的观察"),
        required = listOf("from", "to", "observation"),
    )
    override fun activityLine(args: ToolArgs): String = "按坐标滑动"
    override fun budgetMs(b: Budgets, args: ToolArgs): Long = b.actionMs + 3_000

    override fun call(args: ToolArgs, ctx: ToolContext): ToolResult {
        val e = ctx.engine()
        val from = args.point("from")
        val to = args.point("to")
        val duration = args.intIn("duration_ms", 100..3_000, e.budgets.swipeMs.toInt().coerceIn(100, 3_000)).toLong()
        val screenId = args.string("observation")
        val display = displayOfScreen(screenId)
        e.requireDisplay(display)
        val (obs, pts) = e.mapPoints(screenId, listOf(from, to))
        val (a, b) = pts
        e.guard(
            ActionIntent(tool = name, packageName = obs.packageName, element = null, screen = obs, coordinate = true),
            "在 ${inApp(obs)}屏幕上从 (${a.first},${a.second}) 滑到 (${b.first},${b.second})",
            Rect(minOf(a.first, b.first), minOf(a.second, b.second), maxOf(a.first, b.first) + 1, maxOf(a.second, b.second) + 1),
            forceConfirm = true,
        )
        val now = e.driver(display).capture(e.budgets.captureBudgetMs)
        if (now.foregroundPackage != obs.packageName) {
            val fresh = e.observe(display)
            throw PhoneException(ErrorCode.STALE_SCREEN, "确认期间前台应用变了，坐标已失效；请重新截图", observationText = fresh.text)
        }
        e.checkpoint()
        val ok = e.standAside { e.driver(display).swipe(a.first, a.second, b.first, b.second, duration) }
        if (!ok) throw PhoneException(ErrorCode.ACTION_FAILED, "滑动手势被系统拒绝", observationText = obs.text)
        val (newObs, note) = e.settleAndObserveSoft(display)
        return actionResult(name, "已从 (${a.first},${a.second}) 滑到 (${b.first},${b.second})", newObs, "gesture", note)
    }
}

// =============================================================================================== key

/** 协议 §4 `key`：全局动作。 */
class KeyTool : BaseTool("key", ToolKind.ACT, SemanticRisk.RISKY) {
    override val description = "按系统键：back 返回、home 回桌面、recents 最近任务、notifications 通知栏、quick_settings 快捷设置、lock_screen 锁屏、screenshot 系统截屏。"
    override val inputSchema = Schema.obj(
        "key" to Schema.enum(
            "要按的键。lock_screen 和 screenshot 需要 Android 9+；锁屏后就无法继续操作，只在用户明确要求时使用",
            GlobalAction.entries.map { it.wire },
        ),
        required = listOf("key"),
    )
    override fun activityLine(args: ToolArgs): String = "按系统键 " + (args.raw["key"] ?: "")

    override fun call(args: ToolArgs, ctx: ToolContext): ToolResult {
        val e = ctx.engine()
        val key = GlobalAction.fromWire(args.enumOf("key", GlobalAction.entries.map { it.wire }))!!
        val api = e.availability(DisplayKind.MAIN)?.apiLevel ?: 0
        if ((key == GlobalAction.LOCK_SCREEN || key == GlobalAction.SCREENSHOT) && api < 28) {
            phoneError(ErrorCode.UNSUPPORTED, "${key.wire} 需要 Android 9（API 28）及以上，本机 API $api")
        }
        val before = e.observe(DisplayKind.MAIN)
        e.guard(
            ActionIntent(tool = name, packageName = before.packageName, element = null, screen = before, coordinate = false),
            "按系统键「${KEY_ZH[key]}」",
            null,
        )
        e.checkpoint()
        val ok = e.standAside { e.driver(DisplayKind.MAIN).globalAction(key) }
        if (!ok) throw PhoneException(ErrorCode.ACTION_FAILED, "系统拒绝了「${KEY_ZH[key]}」", observationText = before.text)
        if (key == GlobalAction.LOCK_SCREEN) {
            return actionResult(name, "已锁屏。锁屏状态下无法继续操作，请向用户汇报。", null, "semantic")
        }
        val (obs, note) = e.settleAndObserveSoft(DisplayKind.MAIN)
        return actionResult(name, "已按「${KEY_ZH[key]}」", obs, "semantic", note)
    }

    private companion object {
        val KEY_ZH = mapOf(
            GlobalAction.BACK to "返回", GlobalAction.HOME to "主屏幕", GlobalAction.RECENTS to "最近任务",
            GlobalAction.NOTIFICATIONS to "通知栏", GlobalAction.QUICK_SETTINGS to "快捷设置",
            GlobalAction.LOCK_SCREEN to "锁屏", GlobalAction.SCREENSHOT to "截屏",
        )
    }
}

// =============================================================================================== open

/**
 * 协议 §4 `open`：启动 App 或打开深链。
 * app 匹配顺序：精确包名 > 精确名称（忽略大小写）> 名称包含。任一层多个候选 → AMBIGUOUS 并列出，
 * 不帮模型猜：「地图」可能是高德也可能是百度，猜错会把用户带到别的 App 里。
 */
class OpenTool : BaseTool("open", ToolKind.ACT, SemanticRisk.RISKY) {
    override val description =
        "启动 App（app=名称或包名）或打开链接/深链（url），二选一。名称有歧义时返回候选列表，请改用包名。" +
            "深链会在手机上弹确认。启动后自动返回新屏幕。"
    override val inputSchema = Schema.obj(
        "app" to Schema.str("App 名称（如「微信」）或包名（如 com.tencent.mm）。不确定时先用 apps 查", maxLength = 200),
        "url" to Schema.str("要打开的链接或深链（https://…、weixin://…、geo:…）", maxLength = 2_048),
        "display" to Schema.enum("在哪块屏打开：main（默认）或 background（v1 未启用）", listOf("main", "background"), "main"),
    )
    override fun activityLine(args: ToolArgs): String = if (args.has("url")) "打开链接" else "打开 " + (args.raw["app"] ?: "")

    override fun call(args: ToolArgs, ctx: ToolContext): ToolResult {
        val e = ctx.engine()
        val app = args.optString("app")?.trim()?.takeIf { it.isNotEmpty() }
        val url = args.optString("url")?.trim()?.takeIf { it.isNotEmpty() }
        if ((app == null) == (url == null)) phoneError(ErrorCode.INVALID_ARGS, "app 和 url 必须且只能给一个")
        val display = args.display()
        e.requireDisplay(display)
        val driver = e.driver(display)
        val current = e.registry.latest(display)

        if (url != null) {
            validateUri(url)
            e.guard(
                ActionIntent(tool = name, packageName = null, element = null, screen = current, coordinate = false, uri = url),
                "打开链接 ${clip(url, 80)}",
                null,
            )
            e.checkpoint()
            if (!e.standAside { driver.openUri(url) }) phoneError(ErrorCode.ACTION_FAILED, "没有 App 能打开这个链接")
            val (obs, note) = e.settleAndObserveSoft(display)
            return actionResult(name, "已打开链接" + (obs?.snapshot?.foregroundLabel?.let { "（现在在 $it）" } ?: ""), obs, "intent", note)
        }

        val entry = matchApp(driver.launchableApps(), app!!)
        if (entry.packageName == e.ownPackage) {
            phoneError(ErrorCode.DENIED_POLICY, "不能操作 Vantaloom 自身", mapOf("rule" to "own_app"))
        }
        e.guard(
            ActionIntent(tool = name, packageName = entry.packageName, element = null, screen = current, coordinate = false),
            "打开 ${entry.label}",
            null,
        )
        e.checkpoint()
        if (!e.standAside { driver.launchPackage(entry.packageName) }) phoneError(ErrorCode.ACTION_FAILED, "${entry.label} 启动失败")
        val (obs, note) = e.settleAndObserveSoft(display)
        val landed = obs?.packageName
        val warn = if (obs != null && landed != entry.packageName) "（注意：前台现在是 ${obs.snapshot.foregroundLabel ?: landed}，可能还在启动或被弹窗挡住；可以用 wait(package=\"${entry.packageName}\")）" else null
        return actionResult(
            name, "已打开 ${entry.label} (${entry.packageName})", obs, "intent",
            listOfNotNull(warn, note).joinToString("\n").ifEmpty { null },
            mapOf("package" to entry.packageName),
        )
    }

    /** 模糊匹配。层级内唯一才算命中。 */
    private fun matchApp(apps: List<AppEntry>, query: String): AppEntry {
        val q = query.lowercase()
        val tiers = listOf<(AppEntry) -> Boolean>(
            { it.packageName == query },
            { it.packageName.lowercase() == q },
            { it.label.lowercase() == q },
            { it.label.lowercase().contains(q) },
        )
        for (t in tiers) {
            val hits = apps.filter(t).distinctBy { it.packageName }
            if (hits.size == 1) return hits[0]
            if (hits.size > 1) {
                throw PhoneException(
                    ErrorCode.AMBIGUOUS,
                    "「$query」匹配到 ${hits.size} 个应用，请改用包名：" + hits.take(10).joinToString("；") { "${it.label} (${it.packageName})" },
                    mapOf("candidates" to hits.take(20).map { mapOf("label" to it.label, "package" to it.packageName) }),
                )
            }
        }
        phoneError(ErrorCode.INVALID_ARGS, "没有找到可启动的应用「$query」；用 apps(query=…) 查一下名称或包名")
    }

    /** 只做形状校验：必须有 scheme，且拒绝本地文件/内容提供者/脚本类 scheme（读私有文件、执行脚本的入口）。 */
    private fun validateUri(url: String) {
        val m = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):").find(url)
            ?: phoneError(ErrorCode.INVALID_ARGS, "url 需要带 scheme，如 https://…")
        val scheme = m.groupValues[1].lowercase()
        if (scheme in BLOCKED_SCHEMES) phoneError(ErrorCode.DENIED_POLICY, "不允许打开 $scheme: 链接", mapOf("rule" to "blocked_scheme"))
        if (url.any { it.isISOControl() }) phoneError(ErrorCode.INVALID_ARGS, "url 里有控制字符")
    }

    private companion object {
        val BLOCKED_SCHEMES = setOf("file", "content", "javascript", "data", "intent", "android-app")
    }
}

// =============================================================================================== intent

/**
 * 协议 §4 `intent`：走系统意图，不点界面。字段校验全在 core（平台侧只管映射），
 * 这样同一套规则可以在 JVM 上测。dial/sms_draft 只打开草稿，由用户自己点拨出/发送。
 */
class IntentTool : BaseTool("intent", ToolKind.ACT, SemanticRisk.RISKY) {
    override val description =
        "用系统功能直接办事（不点界面）：定闹钟/计时器、加日程、打开拨号/短信/邮件草稿、分享文字、导航、网页搜索、打开设置页。" +
            "拨号和短信只打开草稿，不会自动拨出或发送。各 kind 的必填字段见参数说明。"
    override val inputSchema = Schema.obj(
        "kind" to Schema.enum(
            "意图类型：alarm(hour,minute,label?) timer(seconds,label?) calendar(title,begin,end?,location?,description?) dial(number) " +
                "sms_draft(number,body?) email_draft(to,subject?,body?) share_text(text) navigate(query) web_search(query) settings(page)",
            IntentKind.entries.map { it.wire },
        ),
        "hour" to Schema.int("alarm：小时 0-23", min = 0, max = 23),
        "minute" to Schema.int("alarm：分钟 0-59", min = 0, max = 59),
        "seconds" to Schema.int("timer：秒数 1-86400", min = 1, max = 86_400),
        "label" to Schema.str("alarm/timer：标签", maxLength = 100),
        "title" to Schema.str("calendar：日程标题", maxLength = 200),
        "begin" to Schema.str("calendar：开始时间 ISO8601，如 2026-11-05T14:00:00+08:00（不带时区按手机时区）", maxLength = 40),
        "end" to Schema.str("calendar：结束时间 ISO8601，默认开始后 1 小时", maxLength = 40),
        "location" to Schema.str("calendar：地点", maxLength = 200),
        "description" to Schema.str("calendar：备注", maxLength = 2_000),
        "number" to Schema.str("dial/sms_draft：电话号码（数字、+、空格、-）", maxLength = 32),
        "body" to Schema.str("sms_draft/email_draft：正文", maxLength = 5_000),
        "to" to Schema.str("email_draft：收件人邮箱，多个用逗号分隔", maxLength = 500),
        "subject" to Schema.str("email_draft：主题", maxLength = 300),
        "text" to Schema.str("share_text：要分享的文字", maxLength = 5_000),
        "query" to Schema.str("navigate：目的地；web_search：搜索词", maxLength = 500),
        "page" to Schema.enum("settings：要打开的设置页", SETTINGS_PAGES),
        required = listOf("kind"),
    )
    override fun activityLine(args: ToolArgs): String = "系统意图 " + (args.raw["kind"] ?: "")

    override fun call(args: ToolArgs, ctx: ToolContext): ToolResult {
        val e = ctx.engine()
        val kind = IntentKind.fromWire(args.enumOf("kind", IntentKind.entries.map { it.wire }))!!
        val (fields, summary) = buildFields(kind, args)
        val stray = args.raw.keys - FIELDS_BY_KIND.getValue(kind) - "kind"
        if (stray.isNotEmpty()) phoneError(ErrorCode.INVALID_ARGS, "kind=${kind.wire} 不接受字段 ${stray.joinToString(", ")}")
        e.guard(
            ActionIntent(tool = name, packageName = null, element = null, screen = e.registry.latest(DisplayKind.MAIN), coordinate = false, intentKind = kind.wire),
            summary,
            null,
        )
        e.checkpoint()
        val ok = e.standAside { e.driver(DisplayKind.MAIN).sendIntent(IntentRequest(kind, fields)) }
        if (!ok) phoneError(ErrorCode.ACTION_FAILED, "没有 App 能处理这个意图（${kind.wire}）")
        val (obs, note) = e.settleAndObserveSoft(DisplayKind.MAIN)
        val draftNote = when (kind) {
            IntentKind.DIAL, IntentKind.SMS_DRAFT, IntentKind.EMAIL_DRAFT, IntentKind.SHARE_TEXT -> "草稿已打开，尚未拨出/发送。"
            else -> null
        }
        return actionResult(name, "已发出系统意图：$summary", obs, "intent", listOfNotNull(draftNote, note).joinToString("\n").ifEmpty { null }, mapOf("kind" to kind.wire))
    }

    /** 按 kind 校验并抽取字段，返回 (fields, 给人看的一句话)。 */
    private fun buildFields(kind: IntentKind, args: ToolArgs): Pair<Map<String, String>, String> {
        val f = LinkedHashMap<String, String>()
        fun req(k: String): String = args.optString(k)?.trim()?.takeIf { it.isNotEmpty() }
            ?: phoneError(ErrorCode.INVALID_ARGS, "kind=${kind.wire} 需要 $k")
        fun opt(k: String): String? = args.optString(k)?.trim()?.takeIf { it.isNotEmpty() }
        val summary = when (kind) {
            IntentKind.ALARM -> {
                val h = args.intIn("hour", 0..23); val m = args.intIn("minute", 0..59)
                f["hour"] = h.toString(); f["minute"] = m.toString()
                opt("label")?.let { f["label"] = it }
                String.format(java.util.Locale.ROOT, "定闹钟 %02d:%02d", h, m) + (f["label"]?.let { "（$it）" } ?: "")
            }
            IntentKind.TIMER -> {
                val s = args.intIn("seconds", 1..86_400)
                f["seconds"] = s.toString()
                opt("label")?.let { f["label"] = it }
                "计时 ${s} 秒"
            }
            IntentKind.CALENDAR -> {
                f["title"] = req("title")
                val beginRaw = req("begin")
                val begin = parseTime(beginRaw, "begin")
                val endRaw = opt("end")
                val end = endRaw?.let { parseTime(it, "end") } ?: (begin + 3_600_000L)
                if (end <= begin) phoneError(ErrorCode.INVALID_ARGS, "end 必须晚于 begin")
                f["beginMs"] = begin.toString(); f["endMs"] = end.toString()
                f["begin"] = beginRaw; endRaw?.let { f["end"] = it }
                opt("location")?.let { f["location"] = it }
                opt("description")?.let { f["description"] = it }
                "添加日程「${clip(f["title"]!!, 30)}」${f["begin"]}"
            }
            IntentKind.DIAL -> {
                f["number"] = phone(req("number"))
                "打开拨号盘：${f["number"]}"
            }
            IntentKind.SMS_DRAFT -> {
                f["number"] = phone(req("number"))
                opt("body")?.let { f["body"] = it }
                "打开短信草稿给 ${f["number"]}" + (f["body"]?.let { "：" + clip(it, 40) } ?: "")
            }
            IntentKind.EMAIL_DRAFT -> {
                val to = req("to").split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
                if (to.isEmpty() || to.any { !EMAIL.matches(it) }) phoneError(ErrorCode.INVALID_ARGS, "to 不是有效的邮箱地址")
                f["to"] = to.joinToString(",")
                opt("subject")?.let { f["subject"] = it }
                opt("body")?.let { f["body"] = it }
                "打开邮件草稿给 ${f["to"]}" + (f["subject"]?.let { "：" + clip(it, 40) } ?: "")
            }
            IntentKind.SHARE_TEXT -> {
                f["text"] = req("text")
                "分享文字：" + clip(f["text"]!!, 40)
            }
            IntentKind.NAVIGATE -> {
                f["query"] = req("query")
                "导航到 ${clip(f["query"]!!, 40)}"
            }
            IntentKind.WEB_SEARCH -> {
                f["query"] = req("query")
                "网页搜索「${clip(f["query"]!!, 40)}」"
            }
            IntentKind.SETTINGS -> {
                f["page"] = args.enumOf("page", SETTINGS_PAGES)
                "打开设置：${f["page"]}"
            }
        }
        return f to summary
    }

    private fun phone(raw: String): String {
        if (!PHONE.matches(raw)) phoneError(ErrorCode.INVALID_ARGS, "number 只能包含数字、+、空格和 -")
        val digits = raw.count { it.isDigit() }
        if (digits !in 3..20) phoneError(ErrorCode.INVALID_ARGS, "number 位数不对")
        return raw.filter { it.isDigit() || it == '+' }
    }

    /** ISO8601 → epoch 毫秒。带偏移的按偏移，不带的按本机时区（模型多半按用户当地时间说话）。 */
    private fun parseTime(s: String, key: String): Long =
        Iso8601.parseMillis(s) ?: phoneError(ErrorCode.INVALID_ARGS, "$key 不是 ISO8601 时间（如 2026-11-05T14:00:00+08:00）")

    private companion object {
        /**
         * 与 :android 的 IntentFactory.SETTINGS_PAGES 对齐（平台侧没有映射的页面在真机上会报 INVALID_ARGS）。
         * 刻意不含 accessibility：那是受保护页面（协议 §6），agent 没有理由把用户带过去。
         */
        val SETTINGS_PAGES = listOf(
            "main", "wifi", "bluetooth", "airplane", "mobile_data", "display", "sound", "battery", "location",
            "date", "language", "storage", "apps", "nfc", "wireless", "sync",
        )
        val PHONE = Regex("^[+]?[0-9][0-9 \\-]*$")
        val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
        val FIELDS_BY_KIND: Map<IntentKind, Set<String>> = mapOf(
            IntentKind.ALARM to setOf("hour", "minute", "label"),
            IntentKind.TIMER to setOf("seconds", "label"),
            IntentKind.CALENDAR to setOf("title", "begin", "end", "location", "description"),
            IntentKind.DIAL to setOf("number"),
            IntentKind.SMS_DRAFT to setOf("number", "body"),
            IntentKind.EMAIL_DRAFT to setOf("to", "subject", "body"),
            IntentKind.SHARE_TEXT to setOf("text"),
            IntentKind.NAVIGATE to setOf("query"),
            IntentKind.WEB_SEARCH to setOf("query"),
            IntentKind.SETTINGS to setOf("page"),
        )
    }
}

// =============================================================================================== clipboard

/**
 * 协议 §4 `clipboard`：set 与 get 二选一。整体按 risky：读剪贴板会带出密码、验证码等隐私。
 * Android 10+ 后台读取会失败：驱动返回 null 时按 UNSUPPORTED 报告，而不是假装剪贴板是空的。
 */
class ClipboardTool : BaseTool("clipboard", ToolKind.ACT, SemanticRisk.RISKY) {
    override val description = "设置（set）或读取（get=true）剪贴板，二选一。读取在 Android 10+ 上常被系统拒绝（返回 UNSUPPORTED）。"
    override val inputSchema = Schema.obj(
        "set" to Schema.str("要放进剪贴板的文字", maxLength = 20_000),
        "get" to Schema.bool("读取剪贴板当前内容"),
    )
    override val needsScreen get() = false
    override fun activityLine(args: ToolArgs): String = if (args.has("set")) "设置剪贴板" else "读取剪贴板"

    override fun call(args: ToolArgs, ctx: ToolContext): ToolResult {
        val e = ctx.engine()
        val set = args.optString("set")
        val get = args.bool("get")
        if ((set != null) == get) phoneError(ErrorCode.INVALID_ARGS, "set 和 get 必须且只能给一个")
        val driver = e.driver(DisplayKind.MAIN)
        val screen = e.registry.latest(DisplayKind.MAIN)
        e.guard(
            ActionIntent(tool = name, packageName = null, element = null, screen = screen, coordinate = false, text = set),
            if (set != null) "把 ${set.length} 个字放进剪贴板：${clip(set.replace('\n', ' '), 40)}" else "读取剪贴板内容",
            null,
        )
        e.checkpoint()
        if (set != null) {
            if (!driver.clipboardSet(set)) phoneError(ErrorCode.ACTION_FAILED, "系统拒绝写入剪贴板")
            return ToolResult("已把 ${set.length} 个字放进剪贴板", linkedMapOf("ok" to true, "action" to name, "length" to set.length.toLong()))
        }
        val v = driver.clipboardGet()
            ?: phoneError(ErrorCode.UNSUPPORTED, "系统不允许后台读取剪贴板（Android 10+ 限制）")
        return ToolResult(
            if (v.isEmpty()) "剪贴板是空的" else "剪贴板内容（${v.length} 字）：\n$v",
            linkedMapOf("ok" to true, "action" to name, "text" to v),
        )
    }
}

// =============================================================================================== notify

/** 协议 §4 `notify`：发一条本 App 的通知（任务完成提醒等）。不碰别的 App，不过安全门。 */
class NotifyTool : BaseTool("notify", ToolKind.ACT, SemanticRisk.NORMAL) {
    override val description = "在手机上发一条 Vantaloom 的通知（如提醒用户任务完成）。不会以别的 App 的名义发送。"
    override val inputSchema = Schema.obj(
        "title" to Schema.str("通知标题", maxLength = 100, minLength = 1),
        "text" to Schema.str("通知正文", maxLength = 1_000, minLength = 1),
        required = listOf("title", "text"),
    )
    override val needsScreen get() = false
    // 通知不动屏幕，也就不需要浮层让用户随时叫停。
    override val needsOverlay get() = false
    override fun activityLine(args: ToolArgs): String = "发通知"

    override fun call(args: ToolArgs, ctx: ToolContext): ToolResult {
        val title = args.string("title").trim()
        val text = args.string("text").trim()
        if (title.isEmpty() || text.isEmpty()) phoneError(ErrorCode.INVALID_ARGS, "title 和 text 不能为空")
        if (title.length > 100 || text.length > 1_000) phoneError(ErrorCode.INVALID_ARGS, "title 最长 100 字，text 最长 1000 字")
        if (!ctx.driver(DisplayKind.MAIN).postNotification(title, text)) phoneError(ErrorCode.ACTION_FAILED, "通知发送失败（可能关闭了通知权限）")
        return ToolResult("已发通知「${clip(title, 30)}」", linkedMapOf("ok" to true, "action" to name))
    }
}

// =============================================================================================== handoff

/**
 * 协议 §4 `handoff`：浮层请用户接手（登录、验证码、支付……agent 不该也做不了的事）。
 * 阻塞等待不计入预算；用户放弃 kind=other 时结束任务（协议 §9）。
 */
class HandoffTool : BaseTool("handoff", ToolKind.CONTROL, SemanticRisk.SAFE) {
    override val description =
        "请用户在手机上亲自完成一步（登录、验证码、支付、确认敏感操作）。阻塞到用户点「继续」、「放弃」或超时，" +
            "继续后返回新屏幕。reason 要让用户一眼看懂该做什么。"
    override val inputSchema = Schema.obj(
        "reason" to Schema.str("给用户看的一句话，如「请输入支付密码完成付款，完成后点继续」", maxLength = 200, minLength = 1),
        "kind" to Schema.enum("接手类型，默认 other", HandoffKind.entries.map { it.wire }, "other"),
        "timeout_s" to Schema.int("最长等多少秒，默认 300，最大 600", min = 10, max = 600, default = 300),
        required = listOf("reason"),
    )
    override val serialized get() = true
    override val startsTask get() = true
    override fun activityLine(args: ToolArgs): String = "等用户接手"

    override fun call(args: ToolArgs, ctx: ToolContext): ToolResult {
        val e = ctx.engine()
        val reason = args.string("reason").trim()
        if (reason.isEmpty()) phoneError(ErrorCode.INVALID_ARGS, "reason 不能为空")
        val kind = HandoffKind.fromWire(args.enumOf("kind", HandoffKind.entries.map { it.wire }, "other"))
        val timeoutMs = (args.intIn("timeout_s", 10..600, 300) * 1000L).coerceAtMost(e.budgets.handoffMaxMs)
        val taskId = e.taskId ?: ""
        e.status.onActivity(taskId, ActivityPhase.WAITING_USER, clip(reason, 60))
        val result = e.userWait { e.handoff.handoff(HandoffRequest(taskId, reason, kind), timeoutMs) }
        e.status.onActivity(taskId, ActivityPhase.IDLE, "用户接手结束")
        return when (result) {
            HandoffResult.RESUMED -> {
                e.checkpoint()
                val (obs, note) = e.settleAndObserveSoft(DisplayKind.MAIN)
                actionResult(name, "用户已完成接手，点了「继续」", obs, note = note, extra = mapOf("result" to "resumed"))
            }
            HandoffResult.ABANDONED -> {
                if (kind == HandoffKind.OTHER) e.endTask("用户在接手卡上放弃")
                phoneError(ErrorCode.HANDOFF_ABANDONED, "用户放弃了这一步；请停下来向用户汇报，不要绕过")
            }
            HandoffResult.TIMEOUT -> throw PhoneException(
                ErrorCode.TIMEOUT, "等了 ${timeoutMs / 1000}s 用户没有响应",
                mapOf("timeoutMs" to timeoutMs),
            )
            HandoffResult.CANCELLED -> {
                e.checkpoint()
                phoneError(ErrorCode.STOPPED, "接手被取消（任务已停止），请停下来向用户汇报")
            }
        }
    }
}
