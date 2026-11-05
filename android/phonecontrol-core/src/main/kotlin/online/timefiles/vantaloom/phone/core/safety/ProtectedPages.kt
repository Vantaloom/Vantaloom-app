package online.timefiles.vantaloom.phone.core.safety

import online.timefiles.vantaloom.phone.core.observe.Observation

/**
 * 系统设置里的受保护页面判定（协议 §6：无障碍、开发者选项、应用权限、安装未知应用、设备管理器、默认应用
 * —— 只能看不能动）。
 *
 * 为什么按「标题/文本关键词」而不是 Activity 名：无障碍服务拿不到 Activity 类名（只有窗口标题和节点），
 * 而且各家 ROM 的 Activity 名完全不同；页面标题和条目文字反而相当稳定。
 *
 * 判定：前台包在受保护设置包表里，并且（窗口标题 / paneTitle / heading 节点 / 顶部区的文本）
 * 之一命中关键词。只看「标题类」文本，而不是全屏任意文本：设置首页的列表里本来就有「无障碍」这一项，
 * 首页不应被整体锁死；进入该子页面后标题才会是「无障碍」。
 */
object ProtectedPages {

    /** 默认关键词（中英，含各 ROM 常见措辞）。 */
    val DEFAULT_KEYWORDS: List<String> = listOf(
        "无障碍", "辅助功能", "无障碍服务", "已下载的应用", "已安装的服务",
        "开发者选项", "开发人员选项", "开发者模式",
        "应用权限", "权限管理", "权限管理器", "特殊应用权限", "特殊权限", "应用权限管理",
        "安装未知应用", "安装未知来源应用", "未知来源", "外部来源应用", "安装外部来源应用",
        "设备管理", "设备管理器", "设备管理应用", "设备管理员",
        "默认应用", "默认应用设置",
        "悬浮窗", "显示在其他应用的上层", "通知使用权", "使用情况访问权限", "修改系统设置",
        "VPN", "锁屏密码", "屏幕锁定", "密码与安全", "生物识别和密码", "指纹", "人脸",
        "Accessibility", "Installed services", "Downloaded apps",
        "Developer options", "Developer mode",
        "App permissions", "Permission manager", "Special app access",
        "Install unknown apps", "Unknown sources",
        "Device admin", "Device admin apps", "Device administrators",
        "Default apps", "Display over other apps", "Notification access", "Usage access", "Modify system settings",
        "Screen lock", "Security", "Biometrics", "Fingerprint", "Face unlock",
    )

    /**
     * 当前屏是否是受保护页面。返回命中的关键词（给用户看原因），否则 null。
     * @param settingsPackages 视为「系统设置」的包名集合。
     * @param keywords 关键词表。
     */
    fun match(obs: Observation, settingsPackages: Set<String>, keywords: List<String>): String? {
        val pkg = obs.packageName ?: return null
        if (pkg !in settingsPackages) return null
        val titles = titleTexts(obs)
        if (titles.isEmpty()) return null
        val normTitles = titles.map { SensitiveWords.normalize(it) }
        for (k in keywords) {
            val nk = SensitiveWords.normalize(k)
            if (nk.isEmpty()) continue
            // 标题等于关键词，或标题以关键词开头/结尾（「无障碍」「无障碍服务」「Accessibility settings」）。
            if (normTitles.any { it == nk || it.startsWith(nk) || it.endsWith(nk) }) return k
        }
        return null
    }

    /** 收集标题类文本：窗口标题、paneTitle、heading 节点，以及顶部区（< 15%）的前几个文本元素。 */
    fun titleTexts(obs: Observation): List<String> {
        val out = ArrayList<String>()
        val s = obs.snapshot
        for (w in s.windows) {
            if (w.packageName != null && w.packageName != obs.packageName) continue
            w.title?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        }
        for (n in obs.nodesById.values) {
            if (n.packageName != null && n.packageName != obs.packageName) continue
            n.paneTitle?.takeIf { it.isNotBlank() }?.let { out.add(it) }
            if (n.heading) (n.text ?: n.contentDescription)?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        }
        val topTexts = obs.elements.asSequence()
            .filter { it.zone == online.timefiles.vantaloom.phone.core.observe.Zone.TOP && it.packageName == obs.packageName }
            .map { it.rawLabel }
            .filter { it.isNotBlank() && it.length <= 30 }
            .take(4)
        out.addAll(topTexts)
        return out
    }
}
