package online.timefiles.vantaloom.phone.host

import android.content.Context
import android.content.SharedPreferences
import online.timefiles.vantaloom.phone.core.safety.SafetyPolicy

/**
 * 安全策略的持久化：SharedPreferences 里存 [SafetyPolicy.toJsonString] 的整段文本。
 *
 * 为什么存整段 JSON 而不是拆成多个键：策略的字段由 core 定义、会演进，fromJson 自己处理缺省与版本升级
 * （缺失字段取更严格的默认值），平台层不必跟着改。
 *
 * 没有存过 / 解析失败 → [SafetyPolicy.defaults]（全部规则开启）。
 */
class SafetyPolicyStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 读取当前策略。 */
    fun load(): SafetyPolicy {
        val text = prefs.getString(KEY, null) ?: return SafetyPolicy.defaults()
        return SafetyPolicy.fromJsonString(text)
    }

    /** 保存（同步提交：策略变更是安全相关的，不希望进程被杀时丢失）。 */
    fun save(policy: SafetyPolicy) {
        prefs.edit().putString(KEY, policy.toJsonString(pretty = false)).commit()
    }

    /** 从 JSON 文本保存（demo 的策略编辑框）。返回实际生效的策略（解析失败则为默认）。 */
    fun saveJson(text: String): SafetyPolicy {
        val p = SafetyPolicy.fromJsonString(text)
        save(p)
        return p
    }

    /** 恢复默认。 */
    fun reset(): SafetyPolicy {
        prefs.edit().remove(KEY).commit()
        return SafetyPolicy.defaults()
    }

    private companion object {
        const val PREFS = "vantaloom_phone_policy"
        const val KEY = "policy_json"
    }
}
