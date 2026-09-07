import Foundation

#if VANTALOOM_EMBEDDED_NODE
// `VantaloomNode` 是 xcframework 里 module.modulemap 声明的 clang 模块，头文件
// libvantaloomnode.h 由 `go build -buildmode=c-archive ./cmd/vantaloom-ios` 生成
// （apps/api/cmd/vantaloom-ios/export_ios.go 里的 //export 函数）。
import VantaloomNode

/// C ABI 约定（与 export_ios.go 锁步；每个 char* 返回值用完 VantaloomFree）：
///   char* VantaloomControllerStart(char* configJSON)                      -> Status JSON
///   char* VantaloomControllerStop(void)                                   -> Status JSON
///   char* VantaloomControllerStatus(void)                                 -> Status JSON
///   char* VantaloomControllerAttach(char* hubUrl, char* token, char* id)  -> Status JSON
///   char* VantaloomControllerDetach(void)                                 -> Status JSON
///   char* VantaloomControllerSetToken(char* token)                        -> Status JSON
///   char* VantaloomControllerConnect(char* machineId)   [阻塞]            -> ConnectResult JSON
///   char* VantaloomControllerDisconnect(void)                             -> Status JSON
///   char* VantaloomControllerMachines(void)                               -> {machines,error?}
///   char* VantaloomControllerExplain(char* machineId)                     -> {methods}
///   char* VantaloomControllerResume(void)                                 -> Status JSON
///   char* VantaloomControllerBootToken(void)                              -> 一次性启动令牌（"" = 无门）
///   void  VantaloomFree(char*)
final class EmbeddedControllerNode: ControllerLauncher {
    let isEmbedded = true

    func start(config: NodeConfig) throws -> NodeStatus {
        let data = try JSONEncoder().encode(config)
        let json = String(decoding: data, as: UTF8.self)
        let status: NodeStatus = try withCString(json) { try decode(VantaloomControllerStart($0)) }
        if let error = status.error, !error.isEmpty, !status.running {
            throw NodeError.failed(error)
        }
        return status
    }

    func stop() -> NodeStatus {
        (try? decode(VantaloomControllerStop())) ?? NodeStatus()
    }

    func status() -> NodeStatus {
        (try? decode(VantaloomControllerStatus())) ?? NodeStatus()
    }

    func attach(hubUrl: String, token: String, machineId: String) throws -> NodeStatus {
        let status: NodeStatus = try withCString(hubUrl) { cHub -> NodeStatus in
            try withCString(token) { cTok -> NodeStatus in
                try withCString(machineId) { cID -> NodeStatus in
                    try decode(VantaloomControllerAttach(cHub, cTok, cID)) as NodeStatus
                }
            }
        }
        if let error = status.error, !error.isEmpty, !status.attached {
            throw NodeError.failed(error)
        }
        return status
    }

    func detach() -> NodeStatus {
        (try? decode(VantaloomControllerDetach())) ?? NodeStatus()
    }

    func setToken(_ token: String) -> NodeStatus {
        (try? withCString(token) { try decode(VantaloomControllerSetToken($0)) as NodeStatus }) ?? NodeStatus()
    }

    func connect(machineId: String) -> NodeConnectResult {
        (try? withCString(machineId) { try decode(VantaloomControllerConnect($0)) as NodeConnectResult })
            ?? NodeConnectResult(error: "节点返回了无法解析的连接结果")
    }

    func disconnect() -> NodeStatus {
        (try? decode(VantaloomControllerDisconnect())) ?? NodeStatus()
    }

    func machines() -> NodeMachinesResponse {
        (try? decode(VantaloomControllerMachines())) ?? NodeMachinesResponse(error: "节点返回了无法解析的机器列表")
    }

    func explain(machineId: String) -> [NodeMethodStatus] {
        struct Envelope: Codable { var methods: [NodeMethodStatus] = [] }
        let env: Envelope? = try? withCString(machineId) { try decode(VantaloomControllerExplain($0)) }
        return env?.methods ?? []
    }

    func resume() -> NodeStatus {
        (try? decode(VantaloomControllerResume())) ?? NodeStatus()
    }

    func bootToken() -> String? {
        guard let raw = VantaloomControllerBootToken() else { return nil }
        defer { VantaloomFree(raw) }
        let token = String(cString: raw)
        return token.isEmpty ? nil : token
    }

    // MARK: - helpers

    /// cgo 生成的签名是 char*（非 const），所以走 strdup 而不是 Swift 的 withCString。
    private func withCString<T>(_ value: String, _ body: (UnsafeMutablePointer<CChar>) throws -> T) throws -> T {
        guard let cstr = strdup(value) else { throw NodeError.failed("strdup failed") }
        defer { free(cstr) }
        return try body(cstr)
    }

    private func decode<T: Decodable>(_ raw: UnsafeMutablePointer<CChar>?) throws -> T {
        guard let raw else { throw NodeError.invalidPayload("<null>") }
        defer { VantaloomFree(raw) }
        let text = String(cString: raw)
        guard let data = text.data(using: .utf8) else { throw NodeError.invalidPayload(text) }
        do {
            return try JSONDecoder().decode(T.self, from: data)
        } catch {
            throw NodeError.invalidPayload(text)
        }
    }
}
#endif
