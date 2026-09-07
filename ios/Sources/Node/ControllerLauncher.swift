import Foundation

// 壳与进程内 Go 操控端节点（apps/api/internal/controllernode，经
// apps/api/cmd/vantaloom-ios 的 C ABI）之间的契约。JSON 形状与 Go 侧的
// Config / Status / ConnectResult / Machine / loomnet.MethodStatus 逐字段对应；
// 改一边要改另一边。所有字段都给默认值：Go 侧新增字段不会让旧壳解码失败。

/// 传给 VantaloomControllerStart 的配置（controllernode.Config）。
struct NodeConfig: Codable {
    var dataDir: String
    var webDir: String = ""
    var version: String = ""
    var disableAuth: Bool = false
    // 三者都给才在 Start 时顺带连入组网；正常流程留空，由前端登录注册后经
    // startNode（VantaloomControllerAttach）连入。
    var hubUrl: String = ""
    var hubToken: String = ""
    var machineId: String = ""
}

/// Hub 链路状态（controllernode.LinkStatus）。
struct NodeLinkStatus: Codable {
    var connected: Bool = false
    var since: String? = nil
    var lastError: String? = nil
    var lastHeartbeatAt: String? = nil
    var lastHeartbeatOk: Bool = false
    var lastHeartbeatError: String? = nil
}

/// 中继/打洞坐标状态（controllernode.RelayStatus）。
struct NodeRelayStatus: Codable {
    var configured: Bool = false
    var hasCoordinate: Bool = false
    var quicAddr: String? = nil
    var wssUrl: String? = nil
    var stunAddrs: Int = 0
}

/// 节点状态快照（controllernode.Status）。前四个字段就是安卓桥 statusJSON 的形状。
struct NodeStatus: Codable {
    var state: String = "idle"          // idle | connecting | connected | error
    var path: String? = nil
    var error: String? = nil
    var lanIp: String? = nil

    var running: Bool = false
    var attached: Bool = false
    var loopbackPort: Int = 0
    var localUrl: String? = nil
    var target: String? = nil
    var machineId: String? = nil
    var fingerprint: String? = nil
    var hubUrl: String? = nil
    var link: NodeLinkStatus? = nil
    var relay: NodeRelayStatus? = nil
    var methods: [String] = []
    var authEnabled: Bool = false
    var startedAt: String? = nil
    var version: String? = nil

    var isConnected: Bool { state == "connected" }
    var isConnecting: Bool { state == "connecting" }
    var isError: Bool { state == "error" }
    var hasTarget: Bool { !(target ?? "").isEmpty }
}

/// 逐方式可达性解释（loomnet.MethodStatus）。
struct NodeMethodStatus: Codable {
    var name: String = ""
    var label: String = ""
    var available: Bool = false
    var active: Bool = false
    var detail: String? = nil
}

/// Connect 的返回（controllernode.ConnectResult）。
struct NodeConnectResult: Codable {
    var ok: Bool = false
    var localUrl: String? = nil
    var path: String? = nil
    var error: String? = nil
    var methods: [NodeMethodStatus] = []

    /// 给用户看的失败文案：逐方式原因原样附上（禁静默兜底）。
    var failureText: String {
        var lines: [String] = [error ?? "连接失败"]
        for m in methods where !m.active {
            let mark = m.available ? "可用但未成功" : "不可用"
            lines.append("· \(m.label)（\(m.name)）：\(mark)。\(m.detail ?? "")")
        }
        return lines.joined(separator: "\n")
    }
}

/// 同账号机器（controllernode.Machine）。
struct NodeMachine: Codable {
    var id: String = ""
    var name: String = ""
    var platform: String = ""
    var arch: String = ""
    var status: String = ""
    var role: String? = nil
    var dialable: Bool = false
    var lanCount: Int = 0
    var publicAddr: String? = nil
}

struct NodeMachinesResponse: Codable {
    var machines: [NodeMachine] = []
    var error: String? = nil
}

enum NodeError: LocalizedError {
    /// 这份 .app 没链接操控端节点 xcframework（CI 在没有它时照样出 IPA）。
    case notBundled
    case failed(String)
    case invalidPayload(String)

    var errorDescription: String? {
        switch self {
        case .notBundled:
            return "此安装包未内置操控端节点（VANTALOOM_EMBEDDED_NODE 未启用）。"
        case let .failed(message):
            return message
        case let .invalidPayload(raw):
            return "节点返回了无法解析的数据：\(raw)"
        }
    }
}

/// 两种实现：`EmbeddedControllerNode`（链了 xcframework）与 `MissingControllerNode`（占位）。
/// 所有方法都是同步的、可能阻塞（connect 最坏约 20s），调用方在后台线程调。
protocol ControllerLauncher {
    var isEmbedded: Bool { get }
    func start(config: NodeConfig) throws -> NodeStatus
    func stop() -> NodeStatus
    func status() -> NodeStatus
    func attach(hubUrl: String, token: String, machineId: String) throws -> NodeStatus
    func detach() -> NodeStatus
    func setToken(_ token: String) -> NodeStatus
    func connect(machineId: String) -> NodeConnectResult
    func disconnect() -> NodeStatus
    func machines() -> NodeMachinesResponse
    func explain(machineId: String) -> [NodeMethodStatus]
    func resume() -> NodeStatus
    func bootToken() -> String?
}

struct MissingControllerNode: ControllerLauncher {
    let isEmbedded = false
    func start(config: NodeConfig) throws -> NodeStatus { throw NodeError.notBundled }
    func stop() -> NodeStatus { NodeStatus() }
    func status() -> NodeStatus { NodeStatus() }
    func attach(hubUrl: String, token: String, machineId: String) throws -> NodeStatus { throw NodeError.notBundled }
    func detach() -> NodeStatus { NodeStatus() }
    func setToken(_ token: String) -> NodeStatus { NodeStatus() }
    func connect(machineId: String) -> NodeConnectResult { NodeConnectResult(error: NodeError.notBundled.localizedDescription) }
    func disconnect() -> NodeStatus { NodeStatus() }
    func machines() -> NodeMachinesResponse { NodeMachinesResponse(error: NodeError.notBundled.localizedDescription) }
    func explain(machineId: String) -> [NodeMethodStatus] { [] }
    func resume() -> NodeStatus { NodeStatus() }
    func bootToken() -> String? { nil }
}

enum ControllerLauncherFactory {
    static func make() -> ControllerLauncher {
        #if VANTALOOM_EMBEDDED_NODE
        return EmbeddedControllerNode()
        #else
        return MissingControllerNode()
        #endif
    }
}
