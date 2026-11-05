package online.timefiles.vantaloom.phone.core.safety

import online.timefiles.vantaloom.phone.core.json.Json
import online.timefiles.vantaloom.phone.core.json.jObj
import online.timefiles.vantaloom.phone.core.json.jStrList

/**
 * 按包的确认模式（协议 §6「确认模式」）。硬拒绝不受它影响。
 */
enum class ConfirmMode(val wire: String) {
    /** 每次都问。 */
    ASK("ask"),
    /** 本任务内问一次（用户选「本任务内都允许」后记住）。 */
    TASK("task"),
    /** 不问（跳过所有确认类规则，但不跳过硬拒绝）。 */
    ALWAYS("always");

    companion object {
        /** wire 反查；未知返回 null。 */
        fun fromWire(v: String?): ConfirmMode? = entries.firstOrNull { it.wire == v }
    }
}

/**
 * 安全策略：默认规则表 + 用户覆盖。不可变；用户改设置时构造新的实例并调 PhoneEngine.updatePolicy。
 *
 * 为什么所有名单都放在策略里而不是 SafetyGate 里写死：用户要能在设置页增删（把某个银行 App 从名单里
 * 拿掉、给某个包设 always），并且要能 JSON 持久化后在服务重启时原样恢复。
 *
 * @property denyPackages 硬拒绝包名（支付、银行、券商、密码管理器 + 用户自定义）。规则名 `deny_package`。
 * @property denyPackagePrefixes 硬拒绝包名前缀。规则名 `deny_package`。
 * @property securityPackages 安全中心/手机管家/权限控制器类包，整体拒绝动作。规则名 `security_package`。
 * @property confirmEveryWritePackages 每步确认的包。规则名 `confirm_every_write`。
 * @property protectedSettingsPackages 视为「系统设置」的包。受保护页面规则名 `protected_settings_page`。
 * @property protectedPageKeywords 受保护页面关键词。
 * @property sensitiveWords 敏感词（中英）。规则名 `sensitive_word`。
 * @property secretFieldKeywords 秘密字段关键词（验证码/支付密码/CVV…）。规则名 `secret_field`。
 * @property perPackageMode 包 → 确认模式。没写的包按 [defaultMode]。
 * @property defaultMode 未单独设置的包的确认模式。
 * @property userAllowedRules 用户解除的规则名（硬拒绝和确认规则都可以解除；`own_app` 不可解除）。
 */
data class SafetyPolicy(
    val denyPackages: Set<String> = PackageLists.DENY_ALL,
    val denyPackagePrefixes: List<String> = PackageLists.DENY_PREFIXES,
    val securityPackages: Set<String> = PackageLists.SECURITY_CENTERS,
    val confirmEveryWritePackages: Set<String> = PackageLists.CONFIRM_EVERY_WRITE,
    val protectedSettingsPackages: Set<String> = PackageLists.SETTINGS,
    val protectedPageKeywords: List<String> = ProtectedPages.DEFAULT_KEYWORDS,
    val sensitiveWords: List<String> = SensitiveWords.DEFAULT,
    val secretFieldKeywords: List<String> = SensitiveWords.SECRET_FIELDS,
    val perPackageMode: Map<String, ConfirmMode> = emptyMap(),
    val defaultMode: ConfirmMode = ConfirmMode.TASK,
    val userAllowedRules: Set<String> = emptySet(),
) {
    /** 某个包的确认模式。 */
    fun modeFor(pkg: String?): ConfirmMode = pkg?.let { perPackageMode[it] } ?: defaultMode

    /** 规则是否被用户解除。`own_app` 永远不能解除（见 [Rules.UNREMOVABLE]）。 */
    fun ruleDisabled(rule: String): Boolean = rule in userAllowedRules && rule !in Rules.UNREMOVABLE

    /** 包是否在硬拒绝名单（精确或前缀）。 */
    fun isDeniedPackage(pkg: String): Boolean = pkg in denyPackages || denyPackagePrefixes.any { it.isNotEmpty() && pkg.startsWith(it) }

    /** 序列化为 JSON 对象（键稳定、集合排序，便于 diff 与持久化）。 */
    fun toJson(): Map<String, Any?> = Json.obj(
        "v" to VERSION,
        "denyPackages" to denyPackages.sorted(),
        "denyPackagePrefixes" to denyPackagePrefixes,
        "securityPackages" to securityPackages.sorted(),
        "confirmEveryWritePackages" to confirmEveryWritePackages.sorted(),
        "protectedSettingsPackages" to protectedSettingsPackages.sorted(),
        "protectedPageKeywords" to protectedPageKeywords,
        "sensitiveWords" to sensitiveWords,
        "secretFieldKeywords" to secretFieldKeywords,
        "perPackageMode" to perPackageMode.toSortedMap().mapValues { it.value.wire },
        "defaultMode" to defaultMode.wire,
        "userAllowedRules" to userAllowedRules.sorted(),
    )

    /** 序列化为 JSON 文本。 */
    fun toJsonString(pretty: Boolean = true): String = Json.stringify(toJson(), pretty)

    companion object {
        /** 持久化格式版本。 */
        const val VERSION = 1

        /** 默认策略：协议 §6 的全部规则默认开启。 */
        fun defaults(): SafetyPolicy = SafetyPolicy()

        /**
         * 从 JSON 对象恢复。缺失的字段取默认值（老版本存档升级时新规则自动生效），
         * 类型不对的字段同样取默认值而不是抛错——策略文件损坏不应让手机控制整体不可用，
         * 而默认值永远是更严格的那一边。
         */
        fun fromJson(json: Map<String, Any?>): SafetyPolicy {
            val d = defaults()
            val modes = LinkedHashMap<String, ConfirmMode>()
            json.jObj("perPackageMode")?.forEach { (k, v) -> ConfirmMode.fromWire(v as? String)?.let { modes[k] = it } }
            return SafetyPolicy(
                denyPackages = json.jStrList("denyPackages")?.toSet() ?: d.denyPackages,
                denyPackagePrefixes = json.jStrList("denyPackagePrefixes") ?: d.denyPackagePrefixes,
                securityPackages = json.jStrList("securityPackages")?.toSet() ?: d.securityPackages,
                confirmEveryWritePackages = json.jStrList("confirmEveryWritePackages")?.toSet() ?: d.confirmEveryWritePackages,
                protectedSettingsPackages = json.jStrList("protectedSettingsPackages")?.toSet() ?: d.protectedSettingsPackages,
                protectedPageKeywords = json.jStrList("protectedPageKeywords") ?: d.protectedPageKeywords,
                sensitiveWords = json.jStrList("sensitiveWords") ?: d.sensitiveWords,
                secretFieldKeywords = json.jStrList("secretFieldKeywords") ?: d.secretFieldKeywords,
                perPackageMode = modes,
                defaultMode = ConfirmMode.fromWire(json["defaultMode"] as? String) ?: d.defaultMode,
                userAllowedRules = json.jStrList("userAllowedRules")?.toSet() ?: d.userAllowedRules,
            )
        }

        /** 从 JSON 文本恢复；解析失败返回默认策略。 */
        fun fromJsonString(text: String): SafetyPolicy = try {
            fromJson(Json.parseObject(text))
        } catch (_: RuntimeException) {
            defaults()
        }
    }
}

/** 稳定的规则名（审计、DENIED_POLICY.details.rule、userAllowedRules 共用）。改名就是破坏兼容。 */
object Rules {
    const val OWN_APP = "own_app"
    const val DENY_PACKAGE = "deny_package"
    const val SECURITY_PACKAGE = "security_package"
    const val PROTECTED_SETTINGS_PAGE = "protected_settings_page"
    const val SECRET_FIELD = "secret_field"
    const val FIRST_TOUCH_PACKAGE = "first_touch_package"
    const val SENSITIVE_WORD = "sensitive_word"
    const val COORDINATE_ACTION = "coordinate_action"
    const val SUBMIT_TEXT = "submit_text"
    const val DEEP_LINK = "deep_link"
    const val OUTBOUND_INTENT = "outbound_intent"
    const val CONFIRM_EVERY_WRITE = "confirm_every_write"

    /** 硬拒绝类规则。 */
    val DENY: Set<String> = setOf(OWN_APP, DENY_PACKAGE, SECURITY_PACKAGE, PROTECTED_SETTINGS_PAGE, SECRET_FIELD)

    /** 确认类规则。 */
    val CONFIRM: Set<String> = setOf(FIRST_TOUCH_PACKAGE, SENSITIVE_WORD, COORDINATE_ACTION, SUBMIT_TEXT, DEEP_LINK, OUTBOUND_INTENT, CONFIRM_EVERY_WRITE)

    /** 不可被用户解除的规则：放开它等于让 agent 能点自己的审批卡。 */
    val UNREMOVABLE: Set<String> = setOf(OWN_APP)

    /** 规则的人话（设置页和确认卡用）。 */
    fun describe(rule: String): String = when (rule) {
        OWN_APP -> "不操作 Vantaloom 自身"
        DENY_PACKAGE -> "不碰支付、银行、券商、密码管理类应用"
        SECURITY_PACKAGE -> "不操作安全中心、手机管家、权限与安装确认"
        PROTECTED_SETTINGS_PAGE -> "系统设置的敏感页面只能看不能动"
        SECRET_FIELD -> "不往密码、验证码、支付密码类输入框里填内容"
        FIRST_TOUCH_PACKAGE -> "每个任务第一次操作某个应用时确认"
        SENSITIVE_WORD -> "目标含支付、删除、发送等敏感词时确认"
        COORDINATE_ACTION -> "坐标点击/滑动每次确认"
        SUBMIT_TEXT -> "输入后直接提交时确认"
        DEEP_LINK -> "打开深链时确认"
        OUTBOUND_INTENT -> "拨号、短信、邮件、分享草稿时确认"
        CONFIRM_EVERY_WRITE -> "标为「每步确认」的应用每次操作都确认"
        else -> rule
    }
}
