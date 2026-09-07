import Foundation
import Security
import SwiftUI
import UIKit

/// 运行时的生命周期属主：起、探活、前后台切换、给 WebView 提供入口 URL。
///
/// 对应安卓的 `LocalRuntime.kt`，但没有「子进程」这个概念：
///   - 安卓：ProcessBuilder 起 libvantaloom.so → 读 port-file → 探 /v1/hub/status；
///   - iOS：同进程调 VantaloomStart → 直接拿到端口 → 探 /healthz。
/// 凭据不经环境变量/一次性文件（没有子进程可泄露给），直接进 JSON 配置。
@MainActor
final class RuntimeHost: ObservableObject {
    enum State {
        case idle
        case starting
        case running(Endpoint)
        case unavailable(String)
        case failed(String)
    }

    struct Endpoint {
        let baseURL: URL
        let port: Int
        let bearerToken: String
        let authEnabled: Bool
        /// 一次性启动令牌：首个导航带上它换 HttpOnly cookie（见 iosruntime/bootcookie.go）。
        var bootToken: String?

        var launchURL: URL {
            guard authEnabled, let bootToken, !bootToken.isEmpty else { return baseURL }
            var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false)!
            components.path = "/"
            components.queryItems = [URLQueryItem(name: "vtlboot", value: bootToken)]
            return components.url ?? baseURL
        }
    }

    @Published private(set) var state: State = .idle
    @Published private(set) var progressMessage: String = "正在启动本地运行时…"
    /// 每次 +1 让 WebShellView 用新的 launchURL 重新加载（运行时重启 / cookie 失效）。
    @Published private(set) var reloadToken: Int = 0

    private let launcher: RuntimeLauncher = RuntimeLauncherFactory.make()
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid
    private var startInFlight = false

    // MARK: - 启动

    func startIfNeeded(force: Bool = false) async {
        if case .running = state, !force { return }
        if startInFlight { return }
        startInFlight = true
        defer { startInFlight = false }

        guard launcher.isEmbedded else {
            state = .unavailable(RuntimeError.notBundled.localizedDescription)
            return
        }
        state = .starting
        progressMessage = "正在启动本地运行时…"

        let config: RuntimeConfig
        do {
            config = try makeConfig()
        } catch {
            state = .failed("准备目录失败：\(error.localizedDescription)")
            return
        }

        let launcher = self.launcher
        let result: Result<RuntimeStatus, Error> = await Task.detached(priority: .userInitiated) {
            do { return .success(try launcher.start(config: config)) } catch { return .failure(error) }
        }.value

        switch result {
        case let .failure(error):
            state = .failed(error.localizedDescription)
        case let .success(status):
            guard status.running, status.port > 0, let baseURL = URL(string: status.baseUrl) else {
                state = .failed(status.error ?? "运行时未报告监听端口")
                return
            }
            let endpoint = Endpoint(
                baseURL: baseURL,
                port: status.port,
                bearerToken: config.bearerToken,
                authEnabled: status.authEnabled,
                bootToken: status.bootToken
            )
            progressMessage = "等待运行时就绪（端口 \(status.port)）…"
            let healthy = await probeHealth(endpoint, attempts: 50, intervalMillis: 200)
            guard healthy else {
                state = .failed("运行时已启动但 /healthz 在 10 秒内没有就绪（端口 \(status.port)）")
                return
            }
            state = .running(endpoint)
        }
    }

    /// 与 main.go / 安卓一致的目录语义：
    ///   installDir = <Application Support>/Vantaloom/install   （可写；logs/、config/、data/ 在下面）
    ///   webDir     = <Bundle>/web                               （只读；运行时在 installDir/web 放符号链接指向它）
    private func makeConfig() throws -> RuntimeConfig {
        let fm = FileManager.default
        let support = try fm.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        let installDir = support.appendingPathComponent("Vantaloom/install", isDirectory: true)
        try fm.createDirectory(at: installDir, withIntermediateDirectories: true)
        // 会话/工程等用户数据要进 iCloud 备份，logs 不必：数据目录不动，logs 排除。
        let logs = installDir.appendingPathComponent("logs", isDirectory: true)
        try fm.createDirectory(at: logs, withIntermediateDirectories: true)
        var excluded = URLResourceValues()
        excluded.isExcludedFromBackup = true
        var logsURL = logs
        try? logsURL.setResourceValues(excluded)

        var webDir = ""
        if let bundled = Bundle.main.resourceURL?.appendingPathComponent("web", isDirectory: true),
           fm.fileExists(atPath: bundled.appendingPathComponent("index.html").path) {
            webDir = bundled.path
        }

        let bearer = try Self.randomHexToken()
        let capability = try Self.randomHexToken()
        guard bearer != capability else { throw RuntimeError.startFailed("token generation collided") }

        let short = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.0.0"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "0"

        return RuntimeConfig(
            installDir: installDir.path,
            dataDir: "",
            webDir: webDir,
            host: "127.0.0.1",
            port: 8780,
            version: "\(short)+ios.\(build)",
            bearerToken: bearer,
            capabilityToken: capability,
            dnsServers: [],
            // 第一期关掉覆盖网：离开回环的 LAN 流量会弹「本地网络」权限，且应用挂起时
            // UDP 套接字会被回收。先把单机跑通，再按裁决决定第二期怎么做联机。
            disableOverlay: true
        )
    }

    /// 与 Kotlin LoopbackAuth.newToken 同口径：32 字节随机 → 64 位十六进制。
    private static func randomHexToken() throws -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        let rc = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        guard rc == errSecSuccess else { throw RuntimeError.startFailed("SecRandomCopyBytes failed: \(rc)") }
        return bytes.map { String(format: "%02x", $0) }.joined()
    }

    // MARK: - 探活

    /// GET /healthz 带 Bearer；200 即就绪。用 ephemeral session 且不走代理
    /// （回环探测被系统代理劫持是桌面 ctl 踩过的坑）。
    private func probeHealth(_ endpoint: Endpoint, attempts: Int, intervalMillis: UInt64) async -> Bool {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 1.5
        config.connectionProxyDictionary = [:]
        let session = URLSession(configuration: config)
        var request = URLRequest(url: endpoint.baseURL.appendingPathComponent("healthz"))
        if endpoint.authEnabled {
            request.setValue("Bearer \(endpoint.bearerToken)", forHTTPHeaderField: "Authorization")
        }
        for _ in 0..<attempts {
            if let (_, response) = try? await session.data(for: request),
               let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) {
                return true
            }
            try? await Task.sleep(nanoseconds: intervalMillis * 1_000_000)
        }
        return false
    }

    // MARK: - 前后台

    /// iOS 切后台约 30s 后挂起整个进程（含 Go 运行时）。这里只做两件诚实的事：
    ///   - 进后台：申请一段 background task，让在途的模型请求 / 工具调用有机会收尾；
    ///   - 回前台：重新探活。运行时还活着就什么都不做（页面的 cookie 仍有效）；
    ///     若监听套接字被系统回收（TN2277 写明会发生），标 failed 让用户重试。
    /// 真正的「后台继续跑 agent」只有 iOS 26 BGContinuedProcessingTask 一条合法路，
    /// 属第二期（见 docs/ios-shell-design.md §分期）。
    func handleScenePhase(_ phase: ScenePhase) {
        switch phase {
        case .background:
            beginBackgroundGrace()
        case .active:
            endBackgroundGrace()
            Task { await reconcileAfterForeground() }
        default:
            break
        }
    }

    private func beginBackgroundGrace() {
        guard backgroundTask == .invalid else { return }
        backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "vantaloom-runtime-grace") { [weak self] in
            Task { @MainActor in self?.endBackgroundGrace() }
        }
    }

    private func endBackgroundGrace() {
        guard backgroundTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(backgroundTask)
        backgroundTask = .invalid
    }

    private func reconcileAfterForeground() async {
        guard case let .running(endpoint) = state else { return }
        let healthy = await probeHealth(endpoint, attempts: 5, intervalMillis: 300)
        if healthy { return }
        let status = launcher.status()
        if status.running {
            // 进程内服务还在但探不到——多半是监听套接字被回收。不自作聪明地重启
            // （server.New 是进程级单例，Stop→Start 是未验证路径），如实报出来。
            state = .failed("回前台后运行时无响应（可能是系统回收了监听套接字）。请重开应用。")
        } else {
            state = .failed(status.error ?? "运行时已停止")
        }
    }

    /// 让 WebView 用新的一次性令牌重新导航（页面拿到 401 时调用）。
    func requestReload() {
        guard case .running(var endpoint) = state else { return }
        endpoint.bootToken = launcher.mintBootToken()
        state = .running(endpoint)
        reloadToken += 1
    }

    // MARK: - 给桥用的只读状态

    var runtimeStatusJSON: String {
        let status = launcher.status()
        let data = (try? JSONEncoder().encode(status)) ?? Data("{}".utf8)
        return String(decoding: data, as: UTF8.self)
    }
}
