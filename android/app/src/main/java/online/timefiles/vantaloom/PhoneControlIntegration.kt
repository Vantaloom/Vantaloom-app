package online.timefiles.vantaloom

import android.content.Context
import android.util.Log
import online.timefiles.vantaloom.phone.host.PhoneControlHost
import online.timefiles.vantaloom.phone.host.VantaloomRuntimeBridge

/**
 * 手机操控（实验，feat/phone-control）与本地运行时之间的唯一接缝。
 *
 * LocalRuntime 只在两处调用它：runtime 就绪 [onRuntimeReady]、runtime 停止 [onRuntimeStopped]。
 * 其余都由 :phonecontrol 库自己完成：无障碍服务连上时起 loopback MCP 服务；
 * [VantaloomRuntimeBridge] 用 runtime 的插件 API（install → grant → PATCH config → enable）把它注册进去。
 * Go 侧零改动，见主仓 docs/android-device-control.md §4。
 *
 * 失败一律只记日志，绝不影响 runtime 本身的启动与停止。
 */
object PhoneControlIntegration {
    private const val TAG = "VantaloomPhone"

    @Volatile private var bridge: VantaloomRuntimeBridge? = null

    private fun bridgeFor(context: Context): VantaloomRuntimeBridge {
        bridge?.let { return it }
        synchronized(this) {
            bridge?.let { return it }
            PhoneControlHost.init(context.applicationContext)
            val created = VantaloomRuntimeBridge(context.applicationContext)
            created.start()
            bridge = created
            return created
        }
    }

    /** runtime 就绪（含重启后）。runtime 重启后 url/token 都过期，bridge 会重放 PATCH。 */
    fun onRuntimeReady(context: Context, port: Int, bearerToken: String) {
        try {
            bridgeFor(context).onRuntimeReady("http://127.0.0.1:$port", bearerToken)
        } catch (t: Throwable) {
            Log.w(TAG, "phone control bridge: runtime ready hook failed: ${t.javaClass.simpleName}")
        }
    }

    fun onRuntimeStopped() {
        try {
            bridge?.onRuntimeGone()
        } catch (t: Throwable) {
            Log.w(TAG, "phone control bridge: runtime stop hook failed: ${t.javaClass.simpleName}")
        }
    }
}
