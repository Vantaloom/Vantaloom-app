package online.timefiles.vantaloom.phone.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import online.timefiles.vantaloom.phone.R
import online.timefiles.vantaloom.phone.host.PhoneControlHost
import java.util.concurrent.atomic.AtomicInteger

/**
 * 两类通知：
 *  - **任务进行中**：普通的 ongoing 通知（不是前台服务——无障碍服务本身就保活，不需要 FGS），
 *    带「停止操作」按钮。它是协议 §8 三个停止入口之一：浮层被别的东西盖住时，用户下拉通知栏也能停。
 *  - **agent 消息**：notify 工具发的提醒。
 *
 * 用 NotificationCompat 屏蔽 26 以下没有 channel 的差异。
 */
object OverlayNotification {
    private const val CHANNEL_TASK = "vantaloom_phone_task"
    private const val CHANNEL_AGENT = "vantaloom_phone_agent"
    private const val TASK_NOTIFICATION_ID = 0x5641_4E01
    private val agentIds = AtomicInteger(0x5641_4E10)

    /** 停止广播的 action。接收器 exported=false，且 PendingIntent 是显式组件，外部应用发不进来。 */
    const val ACTION_STOP = "online.timefiles.vantaloom.phone.action.STOP_TASK"

    private fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(CHANNEL_TASK) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_TASK, context.getString(R.string.vantaloom_phone_channel_task), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.vantaloom_phone_channel_task_desc)
                    setShowBadge(false)
                },
            )
        }
        if (nm.getNotificationChannel(CHANNEL_AGENT) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_AGENT, context.getString(R.string.vantaloom_phone_channel_agent), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = context.getString(R.string.vantaloom_phone_channel_agent_desc)
                },
            )
        }
    }

    /** 能否发通知（用户在系统里关了本应用通知时为 false）。 */
    fun enabled(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** 显示/更新「任务进行中」通知。line 是当前动作的一句话。 */
    fun showTask(context: Context, line: String) {
        try {
            ensureChannels(context)
            val stop = PendingIntent.getBroadcast(
                context,
                1,
                Intent(context, StopTaskReceiver::class.java).setAction(ACTION_STOP).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag(),
            )
            val n = NotificationCompat.Builder(context, CHANNEL_TASK)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle(context.getString(R.string.vantaloom_phone_task_notification_title))
                .setContentText(line)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, context.getString(R.string.vantaloom_phone_stop_action), stop)
                .build()
            safeNotify(context, TASK_NOTIFICATION_ID, n)
        } catch (_: Throwable) {
            // 通知是附加的停止入口，失败不影响浮层这个主入口。
        }
    }

    /** 收起「任务进行中」通知。 */
    fun hideTask(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(TASK_NOTIFICATION_ID)
        } catch (_: Throwable) {
        }
    }

    /** notify 工具：发一条 agent 消息。返回是否真的发出（通知被关闭时 false）。 */
    fun postAgentMessage(context: Context, title: String, text: String): Boolean = try {
        ensureChannels(context)
        if (!enabled(context)) {
            false
        } else {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val content = launch?.let {
                PendingIntent.getActivity(context, 2, it, PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag())
            }
            val n = NotificationCompat.Builder(context, CHANNEL_AGENT)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title.take(80))
                .setContentText(text.take(200))
                .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(2000)))
                .setAutoCancel(true)
                .setContentIntent(content)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            safeNotify(context, agentIds.incrementAndGet(), n)
            true
        }
    } catch (_: Throwable) {
        false
    }

    /** POST_NOTIFICATIONS 在 33+ 是运行时权限；targetSdk 28 时系统自动授予，这里只防 SecurityException。 */
    @android.annotation.SuppressLint("MissingPermission")
    private fun safeNotify(context: Context, id: Int, n: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (_: SecurityException) {
        }
    }

    private fun immutableFlag(): Int = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
}

/**
 * 通知「停止操作」按钮的接收器。exported=false：只有本应用自己的 PendingIntent 能触发。
 * 停止走 [PhoneControlHost.stopTask]，与浮层停止键、长按小球同一条路径（TaskControl.stop）。
 */
class StopTaskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != OverlayNotification.ACTION_STOP) return
        PhoneControlHost.stopTask("用户在通知上停止")
        OverlayNotification.hideTask(context)
    }
}
