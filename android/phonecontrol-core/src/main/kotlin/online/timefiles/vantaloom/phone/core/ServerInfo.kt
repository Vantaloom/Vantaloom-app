package online.timefiles.vantaloom.phone.core

/**
 * MCP 服务端身份（协议 §12）。工具 schema 任何改动都要改 [VERSION]。
 */
object ServerInfo {
    const val NAME = "vantaloom-phone"
    const val VERSION = "1.0.1"

    /** 支持的 MCP 协议版本，第一个是默认（客户端要了不认识的版本时回它）。 */
    val PROTOCOL_VERSIONS: List<String> = listOf("2025-06-18", "2025-03-26", "2024-11-05")

    /** 默认协议版本。 */
    val DEFAULT_PROTOCOL_VERSION: String get() = PROTOCOL_VERSIONS.first()

    /** initialize 的 instructions：给模型的简短用法。要短——每个会话都会带上。 */
    val INSTRUCTIONS: String = """
这些工具操作用户的安卓手机。用法：
1. 先调 screen 看当前屏幕，得到带序号的元素（ref 形如 s12:5）。
2. 按 ref 动作：tap / type / scroll。每次动作后会自动返回新屏幕，用新屏幕的 ref 继续；旧 ref 会得到 STALE_SCREEN。
3. 启动 App 用 open，定闹钟/日程/拨号草稿等用 intent，读长列表用 collect，等加载用 wait。
4. 带 ⚠ 的操作会在手机上弹确认卡，由用户亲自决定；登录、验证码、支付用 handoff 交给用户。
5. 遇到 STOPPED 立刻停手并向用户汇报；PAUSED 表示用户暂停，稍后再试。结果开头的「用户留言」来自用户本人，优先照做。
6. tap_xy / swipe 只在元素列表里找不到目标时用，坐标来自 screen(image=true) 的截图。
""".trim()
}
