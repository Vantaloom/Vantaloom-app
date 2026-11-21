package online.timefiles.vantaloom.phone.setup

import android.annotation.SuppressLint
import android.os.Build

/**
 * ROM 识别（尽力而为）。国产 ROM 的后台管理、受限设置入口位置各不相同，引导文案与 status 工具需要知道
 * 「这是哪家的系统」。厂商不提供公开 API，只能用反射读系统属性；反射失败（被黑名单拦截等）时返回 null，
 * 绝不抛异常。结果在进程内缓存，系统属性运行期不会变。
 */
object RomInfo {
    /** (系统属性名, ROM 前缀)。顺序即优先级：新系统名在前（HyperOS 比 MIUI 属性更准）。 */
    private val PROBES = listOf(
        "ro.mi.os.version.name" to "HyperOS",
        "ro.miui.ui.version.name" to "MIUI",
        "ro.build.version.oplusrom" to "ColorOS",
        "ro.build.version.opporom" to "ColorOS",
        "ro.vivo.os.version" to "OriginOS/Funtouch",
        "hw_sc.build.platform.version" to "HarmonyOS",
        "ro.build.version.magic" to "MagicOS",
        "ro.build.version.emui" to "EMUI",
        "ro.build.version.oneui" to "OneUI",
        "ro.flyme.version.id" to "Flyme",
    )

    /** 形如 "MIUI V140"、"ColorOS V13.1"；识别不出为 null。 */
    val hint: String? by lazy { detect() }

    private fun detect(): String? {
        for ((prop, name) in PROBES) {
            val v = systemProperty(prop)
            if (!v.isNullOrBlank()) return "$name $v".trim()
        }
        return null
    }

    /**
     * 反射 android.os.SystemProperties.get。它是隐藏 API，但不在 greylist-max 限制里
     * （大量应用在用）；万一被拦，返回 null 即可。
     */
    @SuppressLint("PrivateApi")
    fun systemProperty(name: String): String? = try {
        val cls = Class.forName("android.os.SystemProperties")
        val get = cls.getMethod("get", String::class.java)
        (get.invoke(null, name) as? String)?.takeIf { it.isNotBlank() }
    } catch (_: Throwable) {
        null
    }

    /** 归一化的厂商族，引导文案按它分支。 */
    enum class Vendor { XIAOMI, OPPO, VIVO, HUAWEI, SAMSUNG, GOOGLE, OTHER }

    /** 按 Build.MANUFACTURER / BRAND 归类。红米/POCO 归小米，一加/realme 归 OPPO（同一套 ColorOS），iQOO 归 vivo，荣耀归华为。 */
    fun vendor(manufacturer: String = Build.MANUFACTURER ?: "", brand: String = Build.BRAND ?: ""): Vendor {
        val m = (manufacturer + " " + brand).lowercase()
        return when {
            listOf("xiaomi", "redmi", "poco").any { it in m } -> Vendor.XIAOMI
            listOf("oppo", "oneplus", "realme").any { it in m } -> Vendor.OPPO
            listOf("vivo", "iqoo").any { it in m } -> Vendor.VIVO
            listOf("huawei", "honor").any { it in m } -> Vendor.HUAWEI
            "samsung" in m -> Vendor.SAMSUNG
            "google" in m -> Vendor.GOOGLE
            else -> Vendor.OTHER
        }
    }
}
