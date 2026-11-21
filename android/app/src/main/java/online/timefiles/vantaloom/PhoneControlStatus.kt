package online.timefiles.vantaloom

import android.content.Context
import online.timefiles.vantaloom.phone.setup.AccessibilityGuide
import online.timefiles.vantaloom.phone.setup.Capabilities
import online.timefiles.vantaloom.phone.setup.RestrictedSettingsGuide
import org.json.JSONArray
import org.json.JSONObject

/**
 * 手机操控状态（给设置页的卡片）：无障碍服务实测状态 + 能力 + 插件注册同步结果 +
 * 受限设置引导。只读，任何异常都折成 `{"available":false,"error":...}`，绝不抛给 JS。
 */
object PhoneControlStatus {
    fun json(context: Context): String = try {
        val caps = Capabilities.probe(context)
        val out = JSONObject()
        caps.toMap().forEach { (k, v) -> out.put(k, v ?: JSONObject.NULL) }
        out.put("available", true)
        out.put("enabledInSettings", AccessibilityGuide.isEnabledInSettings(context))
        PhoneControlIntegration.syncState()?.let { st ->
            out.put(
                "registration",
                JSONObject()
                    .put("ok", st.ok)
                    .put("step", st.step)
                    .put("message", st.message ?: JSONObject.NULL)
                    .put("atMs", st.atMs),
            )
        }
        if (caps.restrictedSettingsLikely && caps.serviceState == AccessibilityGuide.ServiceState.DISABLED) {
            val guide = RestrictedSettingsGuide.guide()
            out.put(
                "restrictedGuide",
                JSONObject()
                    .put("vendorName", guide.vendorName)
                    .put("steps", JSONArray(guide.steps))
                    .put("note", guide.note ?: JSONObject.NULL),
            )
        }
        out.toString()
    } catch (t: Throwable) {
        JSONObject()
            .put("available", false)
            .put("error", t.message ?: t.javaClass.simpleName)
            .toString()
    }
}
