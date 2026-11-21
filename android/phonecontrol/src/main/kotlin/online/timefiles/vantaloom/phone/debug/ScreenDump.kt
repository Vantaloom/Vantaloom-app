package online.timefiles.vantaloom.phone.debug

import android.content.Context
import online.timefiles.vantaloom.phone.core.fixture.SnapshotJson
import online.timefiles.vantaloom.phone.core.model.ErrorCode
import online.timefiles.vantaloom.phone.core.model.PhoneException
import online.timefiles.vantaloom.phone.core.model.phoneError
import online.timefiles.vantaloom.phone.host.PhoneControlHost
import online.timefiles.vantaloom.phone.host.PhonePaths
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 屏幕 dump：把一次 capture 用 core 的 [SnapshotJson] 写到 files/phone-mcp/dumps/<时间>.json，
 * 可选同时保存截图。这些文件就是 core 的 JVM 测试夹具（FakeScreenDriver 回放）：
 * 在真机上遇到怪界面时 dump 一份，拷回来就能在电脑上复现观察格式化与安全门的行为。
 *
 * 隐私：dump 含屏幕文字（密码框内容已在驱动层丢弃），只写在应用私有目录，权限 0600。
 */
object ScreenDump {

    /** dump 结果。 */
    data class Result(val json: File, val image: File?, val nodeCount: Int, val complete: Boolean)

    /**
     * 抓一次树并写盘。必须在非主线程调用（截图要阻塞等回调）。
     * 服务未连接 → SERVICE_DISABLED。截图失败不影响 JSON（image=null）。
     */
    fun dump(context: Context, withScreenshot: Boolean): Result {
        val s = PhoneControlHost.session() ?: phoneError(ErrorCode.SERVICE_DISABLED, "无障碍服务未连接")
        val budgets = PhoneControlHost.budgets
        val snap = s.driver.capture(budgets.captureBudgetMs * 2)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(Date())
        val dir = PhonePaths.dumpDir(context)
        val json = File(dir, "$stamp.json")
        PhonePaths.writePrivate(json, SnapshotJson.toJsonString(snap, pretty = true))

        var image: File? = null
        if (withScreenshot) {
            try {
                val shot = s.driver.screenshot(budgets.observeMs)
                val bytes = shot.jpeg
                if (bytes != null && !shot.secure) {
                    val f = File(dir, "$stamp.jpg")
                    f.writeBytes(bytes)
                    PhonePaths.restrict(f)
                    image = f
                }
            } catch (_: PhoneException) {
                // API < 30 或安全窗口：只留 JSON。
            }
        }
        return Result(json, image, countNodes(snap.windows.mapNotNull { it.root }), snap.complete)
    }

    /** 已有的 dump 文件（新的在前）。 */
    fun list(context: Context): List<File> =
        PhonePaths.dumpDir(context).listFiles()?.sortedByDescending { it.name }.orEmpty()

    /** 删除全部 dump。 */
    fun clear(context: Context) {
        PhonePaths.dumpDir(context).listFiles()?.forEach { it.delete() }
    }

    private fun countNodes(roots: List<online.timefiles.vantaloom.phone.core.model.RawNode>): Int {
        var n = 0
        val stack = ArrayDeque(roots)
        while (stack.isNotEmpty()) {
            val x = stack.removeLast()
            n++
            stack.addAll(x.children)
        }
        return n
    }
}
