import Foundation

/// 壳与进程内 Go 运行时之间的契约。JSON 形状与
/// apps/api/internal/iosruntime 的 `Config` / `Status` 逐字段对应；改一边要改另一边。
struct RuntimeConfig: Codable {
    var installDir: String
    var dataDir: String = ""
    var webDir: String = ""
    var host: String = "127.0.0.1"
    var port: Int = 8780
    var version: String
    var bearerToken: String
    var capabilityToken: String
    var dnsServers: [String] = []
    var disableOverlay: Bool = true
}

struct RuntimeStatus: Codable {
    var running: Bool = false
    var port: Int = 0
    var baseUrl: String = ""
    var version: String = ""
    var installDir: String = ""
    var dataDir: String = ""
    var machineId: String = ""
    var startedAt: String? = nil
    var authEnabled: Bool = false
    var bootToken: String? = nil
    var error: String? = nil
}

enum RuntimeError: LocalizedError {
    /// 这份 .app 没链接运行时 xcframework（CI 在没有 runtime 时照样出 IPA）。
    case notBundled
    case startFailed(String)
    case invalidStatus(String)

    var errorDescription: String? {
        switch self {
        case .notBundled:
            return "此安装包未内置 Go 运行时（VANTALOOM_EMBEDDED_RUNTIME 未启用）。"
        case let .startFailed(message):
            return message
        case let .invalidStatus(raw):
            return "运行时返回了无法解析的状态：\(raw)"
        }
    }
}

/// 两种实现：`EmbeddedRuntime`（链了 xcframework）与 `MissingRuntime`（占位）。
protocol RuntimeLauncher {
    var isEmbedded: Bool { get }
    func start(config: RuntimeConfig) throws -> RuntimeStatus
    func stop(timeoutMillis: Int32) -> RuntimeStatus
    func status() -> RuntimeStatus
    func mintBootToken() -> String?
}

struct MissingRuntime: RuntimeLauncher {
    let isEmbedded = false
    func start(config: RuntimeConfig) throws -> RuntimeStatus { throw RuntimeError.notBundled }
    func stop(timeoutMillis: Int32) -> RuntimeStatus { RuntimeStatus() }
    func status() -> RuntimeStatus { RuntimeStatus() }
    func mintBootToken() -> String? { nil }
}

enum RuntimeLauncherFactory {
    static func make() -> RuntimeLauncher {
        #if VANTALOOM_EMBEDDED_RUNTIME
        return EmbeddedRuntime()
        #else
        return MissingRuntime()
        #endif
    }
}
