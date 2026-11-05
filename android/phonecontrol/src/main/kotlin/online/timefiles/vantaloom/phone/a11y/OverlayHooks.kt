package online.timefiles.vantaloom.phone.a11y

/**
 * 驱动 → 浮层的窄接缝。驱动在截图、注入手势时要让浮层让开，但不应该依赖 ControlOverlay 的具体类：
 * 将来后台虚拟屏驱动根本没有浮层，测试里也想用空实现。
 *
 * 为什么不直接用 core 的 StatusSink.setStandAside：那是引擎在「动作期间」设置的状态，而驱动另有
 * 「截图那一瞬间必须完全隐藏」「手势注入那一瞬间必须不可触」的需求；两个来源叠加计数，互不覆盖。
 */
interface OverlayHooks {
    /**
     * 浮层是否可用（不变量 2）。语义：任务进行中 = 小球真的挂在窗口上；
     * 没有任务时 = 探测窗口添加成功过、且之后没有添加失败（表示任务一开始就能显示）。
     */
    fun overlayReady(): Boolean

    /** 截图前后调用：true = 全部浮层窗口不可见。实现应阻塞到主线程完成修改（有上限）。 */
    fun setCaptureHidden(hidden: Boolean)

    /** 注入手势前后调用：true = 浮层不可触并移开。实现应阻塞到主线程完成修改（有上限）。 */
    fun setGestureStandAside(active: Boolean)

    companion object {
        /** 没有浮层（测试、无头场景）。overlayReady=false，所以动作类工具会被引擎拒绝。 */
        val NONE: OverlayHooks = object : OverlayHooks {
            override fun overlayReady(): Boolean = false
            override fun setCaptureHidden(hidden: Boolean) = Unit
            override fun setGestureStandAside(active: Boolean) = Unit
        }
    }
}
