package online.timefiles.vantaloom.phone.setup

import android.content.Context
import android.os.Build
import online.timefiles.vantaloom.phone.host.PhoneControlHost
import online.timefiles.vantaloom.phone.overlay.OverlayNotification

/**
 * 能力探测摘要：给 demo 与 Vantaloom 前端显示「这台手机能做什么、还差什么」。
 * 只读、便宜（不抓树、不截图），可以在 onResume 里反复调。
 */
data class Capabilities(
    val apiLevel: Int,
    val manufacturer: String,
    val model: String,
    val romHint: String?,
    val serviceState: AccessibilityGuide.ServiceState,
    val restrictedSettingsLikely: Boolean,
    val installer: String?,
    /** 截图：API 30+。 */
    val screenshot: Boolean,
    /** 单窗口截图（不含浮层、不闪烁）：API 34+。 */
    val windowScreenshot: Boolean,
    /** type(submit=true)：API 30+。 */
    val imeEnter: Boolean,
    /** key(lock_screen / screenshot)：API 28+。 */
    val lockAndSystemScreenshot: Boolean,
    /** 系统允许发通知（notify 工具、任务通知上的停止键）。 */
    val notifications: Boolean,
    /** 浮层可用（不变量 2）。服务未连接时为 false。 */
    val overlay: Boolean,
    /** MCP 服务是否在监听。 */
    val mcpListening: Boolean,
) {
    /** 一句话概括，给前端状态栏。 */
    val headline: String
        get() = when (serviceState) {
            AccessibilityGuide.ServiceState.READY -> if (mcpListening) "手机操控可用" else "无障碍已开启，服务启动中…"
            AccessibilityGuide.ServiceState.CONNECTED_NO_WINDOW -> "无障碍已连接，但暂时读不到屏幕（锁屏？）"
            AccessibilityGuide.ServiceState.ENABLED_NOT_CONNECTED -> "服务被系统关闭了，请在无障碍设置里关掉再打开"
            AccessibilityGuide.ServiceState.DISABLED -> "未开启无障碍服务"
        }

    /** 可 JSON 化的映射（给 JS 桥 / demo）。 */
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "apiLevel" to apiLevel,
        "manufacturer" to manufacturer,
        "model" to model,
        "romHint" to romHint,
        "serviceState" to serviceState.name.lowercase(),
        "restrictedSettingsLikely" to restrictedSettingsLikely,
        "installer" to installer,
        "screenshot" to screenshot,
        "windowScreenshot" to windowScreenshot,
        "imeEnter" to imeEnter,
        "lockAndSystemScreenshot" to lockAndSystemScreenshot,
        "notifications" to notifications,
        "overlay" to overlay,
        "mcpListening" to mcpListening,
        "headline" to headline,
    )

    companion object {
        /** 探测当前状态。 */
        fun probe(context: Context): Capabilities {
            val sdk = Build.VERSION.SDK_INT
            val session = PhoneControlHost.session()
            return Capabilities(
                apiLevel = sdk,
                manufacturer = Build.MANUFACTURER ?: "",
                model = Build.MODEL ?: "",
                romHint = RomInfo.hint,
                serviceState = AccessibilityGuide.state(context),
                restrictedSettingsLikely = RestrictedSettingsGuide.likelyNeeded(context),
                installer = RestrictedSettingsGuide.installerPackage(context),
                screenshot = sdk >= 30,
                windowScreenshot = sdk >= 34,
                imeEnter = sdk >= 30,
                lockAndSystemScreenshot = sdk >= 28,
                notifications = OverlayNotification.enabled(context),
                overlay = session?.overlay?.overlayReady() ?: false,
                mcpListening = session?.server?.endpoint != null,
            )
        }
    }
}
