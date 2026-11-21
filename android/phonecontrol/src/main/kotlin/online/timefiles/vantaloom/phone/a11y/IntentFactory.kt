package online.timefiles.vantaloom.phone.a11y

import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import online.timefiles.vantaloom.phone.core.driver.IntentKind
import online.timefiles.vantaloom.phone.core.driver.IntentRequest
import online.timefiles.vantaloom.phone.core.model.ErrorCode
import online.timefiles.vantaloom.phone.core.model.phoneError
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * intent 工具的「kind + 字段 → Android Intent」映射。独立成一个纯函数对象，是为了将来可以在
 * Robolectric/仪器测试里单独校验映射，不必启动 Activity。
 *
 * 字段名以 core 的 intent 工具（inputSchema + buildFields）为准，写在前面的是主名；括号里是容错别名：
 *  - alarm：hour(0-23)、minute(0-59)、label?（别名 message）
 *  - timer：seconds、label?（别名 message）
 *  - calendar：title、begin、end?、location?、description?
 *    core 已把 ISO8601 解析成 epoch 毫秒放在 beginMs/endMs（end 缺省 = begin+1h），这里优先用它们；
 *    没有时才解析 begin/end 原文（毫秒时间戳或本地 `yyyy-MM-dd HH:mm` / `yyyy-MM-ddTHH:mm` / `yyyy-MM-dd`）
 *  - dial：number
 *  - sms_draft：number、body?
 *  - email_draft：to、subject?、body?
 *  - share_text：text
 *  - navigate：query（地点名或地址）
 *  - web_search：query
 *  - settings：page（见 [SETTINGS_PAGES]，与 core IntentTool.SETTINGS_PAGES 保持一致）
 *  另接受 core 目前不会发的扩展字段：alarm days?/skip_ui?、timer skip_ui?、calendar all_day?、email cc?、share title?。
 *
 * dial/sms/email/share 都只打开草稿界面，由用户自己点拨出/发送（协议 §4）。
 */
object IntentFactory {

    /**
     * settings 可打开的页面。**刻意不包含** 无障碍、开发者选项、安全/设备管理器、安装未知应用、
     * 默认应用——这些是受保护页面（协议 §6），agent 没有任何理由把用户带过去。
     */
    val SETTINGS_PAGES: Map<String, String> = linkedMapOf(
        "main" to Settings.ACTION_SETTINGS,
        "wifi" to Settings.ACTION_WIFI_SETTINGS,
        "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
        "airplane" to Settings.ACTION_AIRPLANE_MODE_SETTINGS,
        "mobile_data" to Settings.ACTION_DATA_ROAMING_SETTINGS,
        "display" to Settings.ACTION_DISPLAY_SETTINGS,
        "sound" to Settings.ACTION_SOUND_SETTINGS,
        "battery" to Settings.ACTION_BATTERY_SAVER_SETTINGS,
        "location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
        "date" to Settings.ACTION_DATE_SETTINGS,
        "language" to Settings.ACTION_LOCALE_SETTINGS,
        "storage" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
        "apps" to Settings.ACTION_APPLICATION_SETTINGS,
        "nfc" to Settings.ACTION_NFC_SETTINGS,
        "wireless" to Settings.ACTION_WIRELESS_SETTINGS,
        "sync" to Settings.ACTION_SYNC_SETTINGS,
    )

    /** 构造 Intent（未加 FLAG_ACTIVITY_NEW_TASK，由调用方统一加）。字段缺失/非法抛 INVALID_ARGS。 */
    fun build(request: IntentRequest): Intent {
        val f = Fields(request.fields)
        return when (request.kind) {
            IntentKind.ALARM -> Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, f.int(0..23, "hour"))
                putExtra(AlarmClock.EXTRA_MINUTES, f.optInt(0..59, "minute", "minutes") ?: 0)
                f.opt("label", "message")?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                f.opt("days")?.let { days ->
                    val list = ArrayList<Int>()
                    for (part in days.split(',', ' ', '，')) {
                        val d = part.trim().toIntOrNull() ?: continue
                        if (d in 1..7) list += d
                    }
                    // 必须是 IntegerArrayList extra（putExtra 会落到 Serializable 重载，闹钟应用读不到）。
                    if (list.isNotEmpty()) putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, list)
                }
                putExtra(AlarmClock.EXTRA_SKIP_UI, f.bool("skip_ui"))
            }

            IntentKind.TIMER -> Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, f.int(1..86_400, "seconds", "length"))
                f.opt("label", "message")?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                putExtra(AlarmClock.EXTRA_SKIP_UI, f.bool("skip_ui"))
            }

            IntentKind.CALENDAR -> Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, f.req("title"))
                // core 已解析好的毫秒优先：begin 原文是带时区偏移的 ISO8601，这里的 parseTime 不认偏移。
                val begin = f.opt("beginMs")?.toLongOrNull() ?: parseTime(f.req("begin", "start"))
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                (f.opt("endMs")?.toLongOrNull() ?: f.opt("end")?.let { parseTime(it) })
                    ?.let { putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }
                f.opt("location")?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
                f.opt("description", "notes")?.let { putExtra(CalendarContract.Events.DESCRIPTION, it) }
                if (f.bool("all_day")) putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
            }

            IntentKind.DIAL -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(phone(f.req("number", "phone", "to")))))

            IntentKind.SMS_DRAFT -> Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(phone(f.req("number", "phone", "to"))))).apply {
                f.opt("body", "text")?.let { putExtra("sms_body", it) }
            }

            IntentKind.EMAIL_DRAFT -> Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
                putExtra(Intent.EXTRA_EMAIL, splitAddresses(f.req("to")))
                f.opt("cc")?.let { putExtra(Intent.EXTRA_CC, splitAddresses(it)) }
                f.opt("subject")?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
                f.opt("body", "text")?.let { putExtra(Intent.EXTRA_TEXT, it) }
            }

            IntentKind.SHARE_TEXT -> {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, f.req("text", "body"))
                    f.opt("subject")?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
                }
                // 走选择器：由用户决定分享给谁，agent 不指定目标应用。
                Intent.createChooser(send, f.opt("title"))
            }

            IntentKind.NAVIGATE -> Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(f.req("query", "destination", "to"))))

            IntentKind.WEB_SEARCH -> Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(SearchManager.QUERY, f.req("query", "q"))
            }

            IntentKind.SETTINGS -> {
                val page = f.req("page", "name").lowercase(Locale.ROOT)
                val action = settingsAction(page)
                    ?: phoneError(ErrorCode.INVALID_ARGS, "不支持的设置页 $page，可选：" + settingsPages().joinToString("|"))
                Intent(action)
            }
        }
    }

    /** web_search 在许多国产机上没有处理者：退化为用浏览器打开搜索页。 */
    fun webSearchFallback(request: IntentRequest): Intent? {
        if (request.kind != IntentKind.WEB_SEARCH) return null
        val q = Fields(request.fields).opt("query", "q") ?: return null
        return Intent(Intent.ACTION_VIEW, Uri.parse("https://www.bing.com/search?q=" + Uri.encode(q)))
    }

    /** 当前系统支持的 settings page 名。 */
    fun settingsPages(): List<String> {
        val pages = SETTINGS_PAGES.keys.toMutableList()
        if (Build.VERSION.SDK_INT >= 29) pages += listOf("internet_panel", "volume_panel")
        return pages
    }

    private fun settingsAction(page: String): String? {
        SETTINGS_PAGES[page]?.let { return it }
        if (Build.VERSION.SDK_INT >= 29) {
            when (page) {
                "internet_panel" -> return Settings.Panel.ACTION_INTERNET_CONNECTIVITY
                "volume_panel" -> return Settings.Panel.ACTION_VOLUME
            }
        }
        return null
    }

    private fun phone(raw: String): String {
        val cleaned = raw.filter { it.isDigit() || it == '+' || it == '*' || it == '#' || it == ',' || it == ';' }
        if (cleaned.isEmpty()) phoneError(ErrorCode.INVALID_ARGS, "号码不合法")
        return cleaned
    }

    private fun splitAddresses(raw: String): Array<String> =
        raw.split(',', ';', ' ', '，', '；').map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray()

    /** 毫秒时间戳或本地时区的日期时间。用 SimpleDateFormat 而不是 java.time：minSdk 24 没有 java.time（未开 desugaring）。 */
    internal fun parseTime(raw: String): Long {
        val s = raw.trim()
        if (s.all { it.isDigit() } && s.length >= 10) {
            val v = s.toLong()
            // 10 位按秒，13 位按毫秒。
            return if (s.length <= 10) v * 1000 else v
        }
        val patterns = listOf("yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd")
        for (p in patterns) {
            val fmt = SimpleDateFormat(p, Locale.ROOT).apply { isLenient = false }
            try {
                val d = fmt.parse(s) ?: continue
                return d.time
            } catch (_: ParseException) {
                // 试下一个格式
            }
        }
        phoneError(ErrorCode.INVALID_ARGS, "时间格式不认识：$s（用毫秒时间戳或 yyyy-MM-dd HH:mm）")
    }

    /** 带别名的字段访问器。 */
    private class Fields(private val raw: Map<String, String>) {
        fun opt(vararg names: String): String? {
            for (n in names) {
                val v = raw[n]?.trim()
                if (!v.isNullOrEmpty()) return v
            }
            return null
        }

        fun req(vararg names: String): String =
            opt(*names) ?: phoneError(ErrorCode.INVALID_ARGS, "缺少字段 ${names.first()}")

        fun optInt(range: IntRange, vararg names: String): Int? {
            val s = opt(*names) ?: return null
            val v = s.toIntOrNull() ?: phoneError(ErrorCode.INVALID_ARGS, "字段 ${names.first()} 应为整数")
            if (v !in range) phoneError(ErrorCode.INVALID_ARGS, "字段 ${names.first()} 应在 ${range.first}..${range.last}")
            return v
        }

        fun int(range: IntRange, vararg names: String): Int =
            optInt(range, *names) ?: phoneError(ErrorCode.INVALID_ARGS, "缺少字段 ${names.first()}")

        fun bool(vararg names: String): Boolean = when (opt(*names)?.lowercase(Locale.ROOT)) {
            "true", "1", "yes" -> true
            else -> false
        }
    }
}
