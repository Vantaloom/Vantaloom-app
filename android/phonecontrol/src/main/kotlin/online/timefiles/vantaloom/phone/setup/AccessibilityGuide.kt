package online.timefiles.vantaloom.phone.setup

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import online.timefiles.vantaloom.phone.a11y.PhoneAccessibilityService

/**
 * 无障碍服务的开启引导（设计文档 §6）。形状与现役 APK 的 launchStorageAccessGrant 一致：
 * 判断 → 拉起设置页 → 待决 → 用户回来后实测。「用户在前端点了同意」不等于「能操控」，只以 [state] 的实测为准。
 */
object AccessibilityGuide {

    /** 服务状态。 */
    enum class ServiceState {
        /** 已连接并能拿到活动窗口根节点：真正可用。 */
        READY,
        /** 已连接但拿不到根节点（锁屏、刚开启还在初始化、被 ROM 限制）。 */
        CONNECTED_NO_WINDOW,
        /** 设置里是开的，但服务没连上——典型是被 ROM 杀了，需要关掉重开。 */
        ENABLED_NOT_CONNECTED,
        /** 没开。 */
        DISABLED,
    }

    /** 本服务的组件名。 */
    fun component(context: Context): ComponentName = ComponentName(context, PhoneAccessibilityService::class.java)

    /** 实测状态：服务实例 + 根节点，而不只是 Settings 里的开关。 */
    fun state(context: Context): ServiceState {
        val svc = PhoneAccessibilityService.current()
        if (svc != null) {
            val root = try {
                svc.rootInActiveWindow
            } catch (_: Throwable) {
                null
            }
            return if (root != null) ServiceState.READY else ServiceState.CONNECTED_NO_WINDOW
        }
        return if (isEnabledInSettings(context)) ServiceState.ENABLED_NOT_CONNECTED else ServiceState.DISABLED
    }

    /**
     * 解析 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES（冒号分隔的组件名列表）。
     * 组件名可能是完整形式 pkg/pkg.Cls 或缩写 pkg/.Cls，用 unflattenFromString 统一比较。
     */
    fun isEnabledInSettings(context: Context): Boolean {
        val raw = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        } catch (_: Throwable) {
            null
        } ?: return false
        val me = component(context)
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(raw)
        for (entry in splitter) {
            val cn = ComponentName.unflattenFromString(entry) ?: continue
            if (cn == me) return true
        }
        return false
    }

    private const val ACTION_ACCESSIBILITY_DETAILS_SETTINGS = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"

    /**
     * 拉起无障碍设置。API 33+ 尽量直达本服务详情页（用户少找一层），失败退回总列表。
     * 返回是否成功拉起。调用方应把 UI 置为「待决」，在 onResume 时重新 [state]。
     */
    fun openAccessibilitySettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            // 常量 Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS 在 compileSdk 34 的公开 stub 里不可见（@SystemApi），
            // 但 action 字符串在 33+ 的设置应用里可用；直接写字面量，拉不起来就退回总列表。
            val detail = Intent(ACTION_ACCESSIBILITY_DETAILS_SETTINGS)
                .putExtra(Intent.EXTRA_COMPONENT_NAME, component(context).flattenToString())
            if (launch(context, detail)) return true
        }
        // 部分 ROM 认这个非公开 extra，把列表滚到并高亮本服务；不认也无害。
        val list = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            val key = component(context).flattenToString()
            putExtra(":settings:fragment_args_key", key)
            putExtra(":settings:show_fragment_args", android.os.Bundle().apply { putString(":settings:fragment_args_key", key) })
        }
        return launch(context, list)
    }

    /** 拉起本应用的「应用信息」页：受限设置的「… → 允许受限设置」就在这里。 */
    fun openAppInfo(context: Context): Boolean {
        val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        return launch(context, i)
    }

    private fun launch(context: Context, intent: Intent): Boolean {
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}

/**
 * 「受限设置」引导（Android 13+）。侧载（非应用商店安装）的应用，无障碍开关默认是灰的，
 * 用户要先去应用信息页右上角 ⋮ 里点「允许受限制的设置」。各家 ROM 入口位置和叫法不同，按厂商给步骤。
 *
 * 注意：本 APK targetSdk 28，受限设置是否仍拦截要在真机上验证（设计文档 §3）。这里只给「可能需要」的判断，
 * 最终以用户回来后的 [AccessibilityGuide.state] 为准。
 */
object RestrictedSettingsGuide {

    /** 一条引导。steps 是给人看的编号步骤，note 是补充说明。 */
    data class Guide(val vendorName: String, val steps: List<String>, val note: String?)

    /** 已知的应用商店安装源：这些来源安装的应用不受「受限设置」限制。 */
    private val STORE_INSTALLERS = setOf(
        "com.android.vending",
        "com.xiaomi.market",
        "com.huawei.appmarket",
        "com.hihonor.appmarket",
        "com.heytap.market",
        "com.oppo.market",
        "com.bbk.appstore",
        "com.sec.android.app.samsungapps",
        "com.tencent.android.qqdownloader",
    )

    /**
     * 是否可能需要「允许受限设置」：API 33+，且不是从应用商店安装。
     * 取不到安装来源时保守地返回 true（多显示一步引导的代价远小于用户卡在灰色开关上）。
     */
    fun likelyNeeded(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val installer = installerPackage(context) ?: return true
        return installer !in STORE_INSTALLERS
    }

    /** 安装来源包名。API 30+ 用 getInstallSourceInfo，更早用已废弃的 getInstallerPackageName。 */
    fun installerPackage(context: Context): String? = try {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= 30) {
            val info = pm.getInstallSourceInfo(context.packageName)
            info.installingPackageName ?: info.initiatingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(context.packageName)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    } catch (_: Throwable) {
        null
    }

    /** 按当前设备厂商给引导。 */
    fun guide(vendor: RomInfo.Vendor = RomInfo.vendor()): Guide = when (vendor) {
        RomInfo.Vendor.XIAOMI -> Guide(
            "小米 / 红米（MIUI / HyperOS）",
            listOf(
                "打开「设置 → 应用设置 → 应用管理」，找到本应用（或点下方「应用信息」按钮直达）。",
                "点右上角「⋮」，选「允许受限制的设置」，按提示验证。",
                "回到「设置 → 更多设置 → 无障碍 → 已下载的应用」，打开「Vantaloom 手机操控」。",
                "建议在「应用信息 → 省电策略」里选「无限制」，并在最近任务里锁定本应用，防止服务被后台清理。",
            ),
            "MIUI 的「神隐模式」会在锁屏后关闭无障碍服务，关掉它可以减少「服务被关闭」。",
        )
        RomInfo.Vendor.OPPO -> Guide(
            "OPPO / 一加 / realme（ColorOS）",
            listOf(
                "打开「设置 → 应用 → 应用管理」，找到本应用。",
                "点右上角「⋮」，选「允许受限制的设置」。",
                "回到「设置 → 其他设置 / 系统设置 → 无障碍 → 已安装的服务」，打开「Vantaloom 手机操控」。",
                "在「应用信息 → 耗电管理」里允许后台运行。",
            ),
            null,
        )
        RomInfo.Vendor.VIVO -> Guide(
            "vivo / iQOO（OriginOS / Funtouch）",
            listOf(
                "打开「设置 → 应用与权限 → 应用管理」，找到本应用。",
                "点右上角「⋮」，选「允许受限制的设置」。",
                "回到「设置 → 快捷与辅助 → 无障碍」，打开「Vantaloom 手机操控」。",
                "在「i 管家 → 应用管理 → 自启动 / 后台高耗电」里放行本应用。",
            ),
            null,
        )
        RomInfo.Vendor.HUAWEI -> Guide(
            "华为 / 荣耀（EMUI / HarmonyOS / MagicOS）",
            listOf(
                "打开「设置 → 应用和服务 → 应用管理」，找到本应用。",
                "如果右上角有「⋮ → 允许受限制的设置」就先打开；没有则跳过。",
                "回到「设置 → 辅助功能 → 无障碍」，打开「Vantaloom 手机操控」。",
                "在「设置 → 电池 → 应用启动管理」里把本应用改为手动管理并全部允许。",
            ),
            "纯血鸿蒙（HarmonyOS NEXT）不再运行安卓应用，不在支持范围内。",
        )
        RomInfo.Vendor.SAMSUNG -> Guide(
            "三星（One UI）",
            listOf(
                "打开「设置 → 应用程序」，找到本应用。",
                "点右上角「⋮」，选「允许受限制的设置」。",
                "回到「设置 → 辅助功能 → 已安装的应用程序」，打开「Vantaloom 手机操控」。",
                "在「设置 → 电池 → 后台使用限制」里把本应用加入「从不休眠的应用程序」。",
            ),
            null,
        )
        RomInfo.Vendor.GOOGLE, RomInfo.Vendor.OTHER -> Guide(
            if (vendor == RomInfo.Vendor.GOOGLE) "Pixel / 原生 Android" else "其他机型（按原生 Android 步骤）",
            listOf(
                "打开「设置 → 应用 → 查看全部应用」，找到本应用。",
                "点右上角「⋮」，选「允许受限制的设置」（Allow restricted settings）。",
                "回到「设置 → 无障碍 → 已下载的应用」，打开「Vantaloom 手机操控」。",
            ),
            "如果⋮菜单里没有这一项，先去无障碍页点一次灰色的开关（会弹「受限设置」提示），再回来就会出现。",
        )
    }
}
