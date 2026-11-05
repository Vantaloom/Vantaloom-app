package online.timefiles.vantaloom.phone.core.tools

import online.timefiles.vantaloom.phone.core.driver.ScreenDriver
import online.timefiles.vantaloom.phone.core.model.DisplayKind
import online.timefiles.vantaloom.phone.core.ports.Clock
import online.timefiles.vantaloom.phone.core.ports.ConfirmationPort
import online.timefiles.vantaloom.phone.core.ports.HandoffPort
import online.timefiles.vantaloom.phone.core.ports.StatusSink

/**
 * 工具执行时能拿到的一切。由 [online.timefiles.vantaloom.phone.core.PhoneEngine] 每次调用时组装。
 * 工具不直接碰 SafetyGate / TaskSession 的内部状态，只通过这里的方法。
 */
interface ToolContext {
    val clock: Clock
    val confirmation: ConfirmationPort
    val handoff: HandoffPort
    val status: StatusSink
    val budgets: Budgets

    /** 取指定 display 的驱动；没有实现时抛 UNSUPPORTED。 */
    fun driver(display: DisplayKind = DisplayKind.MAIN): ScreenDriver

    /** 当前任务 id（动作类工具调用时保证非空；观察类可能为空）。 */
    val taskId: String?

    /** 远端调用方（sharing）描述，本机为 null。来自 MCP 请求头，只用于展示。 */
    val remoteCaller: String?
}

/** 协议 §5 的预算与 §2 的稳定参数，集中在一处，测试可调。 */
data class Budgets(
    val observeMs: Long = 5_000,
    val actionMs: Long = 15_000,
    val collectMs: Long = 100_000,
    val captureBudgetMs: Long = 2_500,
    val settleQuietMs: Long = 350,
    val settleMaxMs: Long = 2_000,
    val confirmTimeoutMs: Long = 30_000,
    val handoffMaxMs: Long = 600_000,
    val taskIdleMs: Long = 180_000,
    val taskMaxActions: Int = 300,
    val tapMs: Long = 60,
    val longPressMs: Long = 800,
    val swipeMs: Long = 350,
    val waitMaxMs: Long = 15_000,
    val maxObservationChars: Int = 3_500,
    val maxLabelChars: Int = 60,
    val screenshotLongEdge: Int = 1_280,
    val screenshotQuality: Int = 80,
)
