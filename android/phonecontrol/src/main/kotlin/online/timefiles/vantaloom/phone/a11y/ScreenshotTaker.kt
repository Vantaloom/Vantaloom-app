package online.timefiles.vantaloom.phone.a11y

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.Display
import androidx.annotation.RequiresApi
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 无障碍截图 API 的同步封装（API 30+）。单独成类并整体标 @RequiresApi(30)：
 * TakeScreenshotCallback / ScreenshotResult 这些类型只在 30+ 存在，隔离在这里，
 * 低版本上永远不会加载这个类，也就不会触发类校验问题。
 */
@RequiresApi(30)
internal object ScreenshotTaker {

    /** 一次截图的原始结果：软件 ARGB_8888 位图，或失败码。 */
    sealed class Outcome {
        /** 成功；bitmap 归调用方所有。 */
        class Ok(val bitmap: Bitmap) : Outcome()
        /** 系统报告失败；code 是 AccessibilityService.ERROR_TAKE_SCREENSHOT_*。 */
        class Failed(val code: Int) : Outcome()
        /** 回调没在时限内回来。 */
        object TimedOut : Outcome()
    }

    /** 回调直接在 binder 线程执行：我们只做拷贝，不碰 UI，不需要切主线程。 */
    private val direct = Executor { it.run() }

    /** 整屏截图（API 30+）。截图会包含浮层，调用方负责先隐藏。 */
    fun display(service: AccessibilityService, timeoutMs: Long): Outcome =
        await(timeoutMs) { cb -> service.takeScreenshot(Display.DEFAULT_DISPLAY, direct, cb) }

    /** 单窗口截图（API 34+）：只截目标窗口，天然不含浮层。 */
    @RequiresApi(34)
    fun window(service: AccessibilityService, windowId: Int, timeoutMs: Long): Outcome =
        await(timeoutMs) { cb -> service.takeScreenshotOfWindow(windowId, direct, cb) }

    private fun await(timeoutMs: Long, start: (AccessibilityService.TakeScreenshotCallback) -> Unit): Outcome {
        val latch = CountDownLatch(1)
        val ok = AtomicReference<Bitmap?>(null)
        val fail = AtomicInteger(Int.MIN_VALUE)
        val cb = object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                try {
                    val buffer = result.hardwareBuffer
                    try {
                        // HARDWARE 位图不能 getPixels/Canvas，必须拷成软件位图；拷完立刻释放 GPU 缓冲。
                        val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                        if (soft != null) ok.set(soft) else fail.set(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR)
                    } finally {
                        buffer.close()
                    }
                } catch (_: Throwable) {
                    fail.set(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR)
                } finally {
                    latch.countDown()
                }
            }

            override fun onFailure(errorCode: Int) {
                fail.set(errorCode)
                latch.countDown()
            }
        }
        try {
            start(cb)
        } catch (_: Throwable) {
            return Outcome.Failed(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR)
        }
        if (!latch.await(timeoutMs.coerceAtLeast(1), TimeUnit.MILLISECONDS)) return Outcome.TimedOut
        ok.get()?.let { return Outcome.Ok(it) }
        return Outcome.Failed(fail.get())
    }

    /** 失败码 → 是否表示安全窗口（FLAG_SECURE）。该码 34 才有；30–33 上安全窗口返回黑图，由亮度方差兜底。
     *  常量是编译期内联的 int，低版本上引用它不会触发类加载问题。 */
    @SuppressLint("InlinedApi")
    fun isSecureError(code: Int): Boolean = code == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW

    /** 截图频率过快（系统限制约每 333ms 一次）：调用方可等一会儿重试一次。 */
    fun isIntervalTooShort(code: Int): Boolean = code == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT
}
