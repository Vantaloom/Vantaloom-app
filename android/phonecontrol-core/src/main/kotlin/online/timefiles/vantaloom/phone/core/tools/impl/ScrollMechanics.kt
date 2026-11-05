package online.timefiles.vantaloom.phone.core.tools.impl

import online.timefiles.vantaloom.phone.core.driver.ScreenDriver
import online.timefiles.vantaloom.phone.core.model.NodeAction
import online.timefiles.vantaloom.phone.core.model.Rect
import online.timefiles.vantaloom.phone.core.observe.ObservedElement
import online.timefiles.vantaloom.phone.core.tools.Budgets

/**
 * scroll 与 collect 共用的滚动机制。
 *
 * 方向语义：`direction` 是「想看哪个方向的内容」。down = 看下面更多（手指从下往上滑），
 * 与无障碍 ACTION_SCROLL_DOWN / SCROLL_FORWARD 的含义一致。模型最容易搞反的就是这一点，
 * 所以 schema 描述里也写明。
 */
object ScrollMechanics {
    /** 各方向的首选节点动作与退路（老系统只有 FORWARD/BACKWARD）。 */
    fun nodeActions(direction: String): List<NodeAction> = when (direction) {
        "down" -> listOf(NodeAction.SCROLL_DOWN, NodeAction.SCROLL_FORWARD)
        "up" -> listOf(NodeAction.SCROLL_UP, NodeAction.SCROLL_BACKWARD)
        "right" -> listOf(NodeAction.SCROLL_RIGHT, NodeAction.SCROLL_FORWARD)
        "left" -> listOf(NodeAction.SCROLL_LEFT, NodeAction.SCROLL_BACKWARD)
        else -> emptyList()
    }

    /**
     * 滚动一次。先试节点滚动动作（只在整页时——节点滚动的幅度由 App 决定，通常是一页，
     * half 时用它会滚过头），再退到手势：在元素可见区域（或整屏）中心滑动 70%（half 35%）。
     * 手势居中而不是贴边起手：从屏幕边缘起手会触发系统返回/回桌面手势。
     *
     * @return "semantic" / "gesture"；完全无法滚动（区域在屏外、系统拒绝手势）时 null。
     */
    fun scroll(
        driver: ScreenDriver,
        element: ObservedElement?,
        direction: String,
        half: Boolean,
        screenW: Int,
        screenH: Int,
        budgets: Budgets,
    ): String? {
        if (element != null && !half) {
            for (a in nodeActions(direction)) {
                if (a in element.actions && driver.performNode(element.captureId, element.nodeId, a)) return "semantic"
            }
        }
        val area: Rect = if (element != null) {
            visiblePart(element.bounds, screenW, screenH) ?: return null
        } else {
            // 不给 ref：滚屏幕中部。去掉上下各 10%，避开状态栏下拉和底部导航手势区。
            if (screenW <= 0 || screenH <= 0) return null
            Rect(0, screenH / 10, screenW, screenH - screenH / 10)
        }
        val (from, to) = gestureLine(area, direction, if (half) 0.35 else 0.70) ?: return null
        return if (driver.swipe(from.first, from.second, to.first, to.second, budgets.swipeMs)) "gesture" else null
    }

    /** 在区域中心沿方向取一条长度为 fraction 的线段；区域太小（< 10 px）时 null。 */
    fun gestureLine(area: Rect, direction: String, fraction: Double): Pair<Pair<Int, Int>, Pair<Int, Int>>? {
        val vertical = direction == "up" || direction == "down"
        val len = ((if (vertical) area.height else area.width) * fraction).toInt()
        if (len < 10) return null
        val cx = area.centerX
        val cy = area.centerY
        val h = len / 2
        return when (direction) {
            "down" -> (cx to cy + h) to (cx to cy - h)
            "up" -> (cx to cy - h) to (cx to cy + h)
            "right" -> (cx + h to cy) to (cx - h to cy)
            "left" -> (cx - h to cy) to (cx + h to cy)
            else -> null
        }
    }
}
