package online.timefiles.vantaloom.phone.util

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 主线程工具。工具在执行线程上同步调用驱动与浮层，而 WindowManager / View / 部分系统服务
 * （ClipboardManager 在旧版本上需要 Looper）必须在主线程用，所以集中在这里做「切主线程 + 有限等待」。
 */
internal object MainThread {
    /** 主线程 Handler。 */
    val handler: Handler = Handler(Looper.getMainLooper())

    /** 当前是否在主线程。 */
    fun isMain(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    /** 异步在主线程执行；已在主线程则立即执行。 */
    fun post(block: () -> Unit) {
        if (isMain()) block() else handler.post(block)
    }

    /**
     * 在主线程执行并等待结果。超时返回 null（block 仍会在主线程稍后执行）。
     * 为什么有超时：主线程卡住时不能让工具线程跟着永久挂起，工具有自己的预算。
     * block 抛出的异常会在调用线程重新抛出。
     */
    fun <T> call(timeoutMs: Long, block: () -> T): T? {
        if (isMain()) return block()
        val latch = CountDownLatch(1)
        val result = AtomicReference<T?>(null)
        val error = AtomicReference<Throwable?>(null)
        handler.post {
            try {
                result.set(block())
            } catch (t: Throwable) {
                error.set(t)
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
        error.get()?.let { throw it }
        return result.get()
    }
}
