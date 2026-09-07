import Foundation
import SwiftUI
import UIKit

/// 操控端节点的生命周期属主：起回环服务、给 WebView 入口 URL、承接桥调用
/// （startNode / connect / disconnect / stop / setToken）、轮询状态推给页面、
/// 前后台切换时重连。
///
/// 对应安卓的 `Loom.kt` + `LoomJsBridge.kt` 的控制端那一半，没有子进程、没有
/// 前台服务：iOS 切后台约 30s 后整个进程挂起，QUIC 会话与信令 WS 一并冻结；
/// 回前台唯一诚实的做法是让 Go 侧 Resume（踢信令重连、重热对端会话），并把
/// 「正在重新连接 / 连接中断」明明白白显示出来（ConnectionOverlay）。
@MainActor
final class ControllerHost: ObservableObject {
    enum Phase {
        case idle
        case starting
        case running(Endpoint)
        case unavailable(String)
        case failed(String)
    }

    struct Endpoint {
        let baseURL: URL
        let port: Int
    }

    @Published private(set) var phase: Phase = .idle
    @Published private(set) var progressMessage: String = "正在启动操控端…"
    /// 最近一次轮询到的节点状态（页面的 statusJSON() 读的是同一份的 JSON）。
    @Published private(set) var status: NodeStatus = NodeStatus()
    /// 回前台后等待重连结果时为 true：ConnectionOverlay 只在这段时间露面，
    /// 不与前端自己的「正在连接执行机…」画面打架。
    @Published private(set) var resumePending = false
    /// 每次 +1 让 WebShellView 用新的启动令牌重新加载。
    @Published private(set) var reloadToken: Int = 0
    /// WebView 的入口 URL（带一次性启动令牌）。回环起来时算一次；requestReload 换一枚。
    /// 放在 @Published 里而不是每次 body 现算：现算会在每次重渲染时白白铸一枚令牌。
    @Published private(set) var launchURL: URL? = nil
    /// 页面经 setChrome 推来的背景色（安全区边条与页面同色）。
    @Published private(set) var chromeColor: Color? = nil
    @Published private(set) var chromeDark = false

    /// WebShellView 挂上的推送口：把状态 JSON 送进页面的缓存（__loomPushStatus）。
    var statusSink: ((String) -> Void)?
    /// WebShellView 挂上的「在页面里执行 JS」口（回到选择页用）。
    var scriptRunner: ((String) -> Void)?

    let launcher: ControllerLauncher = ControllerLauncherFactory.make()
    private var startInFlight = false
    private var pollTimer: Timer?
    private var lastPushedStatusJSON = ""
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid

    // MARK: - 启动（回环服务）

    func startIfNeeded(force: Bool = false) async {
        if case .running = phase, !force { return }
        if startInFlight { return }
        startInFlight = true
        defer { startInFlight = false }

        guard launcher.isEmbedded else {
            phase = .unavailable(NodeError.notBundled.localizedDescription)
            return
        }
        phase = .starting
        progressMessage = "正在启动操控端…"

        let config: NodeConfig
        do {
            config = try makeConfig()
        } catch {
            phase = .failed("准备目录失败：\(error.localizedDescription)")
            return
        }
        let launcher = self.launcher
        let result: Result<NodeStatus, Error> = await Task.detached(priority: .userInitiated) {
            do { return .success(try launcher.start(config: config)) } catch { return .failure(error) }
        }.value
        switch result {
        case let .failure(error):
            phase = .failed(error.localizedDescription)
        case let .success(st):
            guard st.running, st.loopbackPort > 0, let base = URL(string: st.localUrl ?? "") else {
                phase = .failed(st.error ?? "节点未报告回环端口")
                return
            }
            status = st
            let endpoint = Endpoint(baseURL: base, port: st.loopbackPort)
            phase = .running(endpoint)
            launchURL = makeLaunchURL(endpoint)
            startPolling()
        }
    }

    /// 目录语义：
    ///   dataDir = <Application Support>/Vantaloom/controller（overlay 身份密钥；可写）
    ///   webDir  = <Bundle>/web（随包前端；只读，节点原地提供）
    private func makeConfig() throws -> NodeConfig {
        let fm = FileManager.default
        let support = try fm.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        let dataDir = support.appendingPathComponent("Vantaloom/controller", isDirectory: true)
        try fm.createDirectory(at: dataDir, withIntermediateDirectories: true)
        // 身份密钥是这台设备的 overlay 身份，不该跟着备份跑到另一台设备上。
        var excluded = URLResourceValues()
        excluded.isExcludedFromBackup = true
        var dataURL = dataDir
        try? dataURL.setResourceValues(excluded)

        var webDir = ""
        if let bundled = Bundle.main.resourceURL?.appendingPathComponent("web", isDirectory: true),
           fm.fileExists(atPath: bundled.appendingPathComponent("index.html").path) {
            webDir = bundled.path
        }
        let short = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.0.0"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "0"
        return NodeConfig(
            dataDir: dataDir.path,
            webDir: webDir,
            version: "\(short)+ios.\(build)",
            disableAuth: false
        )
    }

    /// 首个导航 / 重新加载用的 URL：带一次性启动令牌换 HttpOnly cookie。
    private func makeLaunchURL(_ endpoint: Endpoint) -> URL {
        guard let token = launcher.bootToken(), !token.isEmpty else { return endpoint.baseURL }
        var components = URLComponents(url: endpoint.baseURL, resolvingAgainstBaseURL: false)
        components?.path = "/"
        components?.queryItems = [URLQueryItem(name: "vtlboot", value: token)]
        return components?.url ?? endpoint.baseURL
    }

    /// 主文档 401（cookie 失效）→ 换一枚令牌重新加载。
    func requestReload() {
        guard case let .running(endpoint) = phase else { return }
        launchURL = makeLaunchURL(endpoint)
        reloadToken += 1
    }

    // MARK: - 桥调用（全部在后台线程调 Go，回主线程刷新状态）

    func attach(hubUrl: String, token: String, machineId: String) async throws {
        let launcher = self.launcher
        let st = try await Task.detached(priority: .userInitiated) {
            try launcher.attach(hubUrl: hubUrl, token: token, machineId: machineId)
        }.value
        publish(st)
    }

    func connect(machineId: String) async -> NodeConnectResult {
        let launcher = self.launcher
        let result = await Task.detached(priority: .userInitiated) {
            launcher.connect(machineId: machineId)
        }.value
        await refreshStatus()
        return result
    }

    func disconnect() async {
        let launcher = self.launcher
        let st = await Task.detached { launcher.disconnect() }.value
        resumePending = false
        publish(st)
    }

    func detach() async {
        let launcher = self.launcher
        let st = await Task.detached { launcher.detach() }.value
        resumePending = false
        publish(st)
    }

    func setToken(_ token: String) {
        let launcher = self.launcher
        Task.detached { _ = launcher.setToken(token) }
    }

    func setChrome(color: String, dark: Bool) {
        chromeDark = dark
        chromeColor = Self.parseCSSColor(color)
    }

    // MARK: - 状态轮询 → 页面缓存

    private func startPolling() {
        pollTimer?.invalidate()
        pollTimer = Timer.scheduledTimer(withTimeInterval: 1.5, repeats: true) { [weak self] _ in
            Task { @MainActor in await self?.refreshStatus() }
        }
    }

    func refreshStatus() async {
        guard case .running = phase else { return }
        let launcher = self.launcher
        let st = await Task.detached { launcher.status() }.value
        publish(st)
    }

    private func publish(_ st: NodeStatus) {
        status = st
        if resumePending, st.isConnected || !st.hasTarget {
            resumePending = false
        }
        let json = Self.encode(st)
        if json != lastPushedStatusJSON {
            lastPushedStatusJSON = json
            statusSink?(json)
        }
    }

    var statusJSON: String { Self.encode(status) }

    private static func encode(_ st: NodeStatus) -> String {
        let data = (try? JSONEncoder().encode(st)) ?? Data("{\"state\":\"idle\"}".utf8)
        return String(decoding: data, as: UTF8.self)
    }

    // MARK: - 前后台

    func handleScenePhase(_ scenePhase: ScenePhase) {
        switch scenePhase {
        case .background:
            beginBackgroundGrace()
        case .active:
            endBackgroundGrace()
            Task { await resumeAfterForeground() }
        default:
            break
        }
    }

    private func beginBackgroundGrace() {
        guard backgroundTask == .invalid else { return }
        backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "vantaloom-controller-grace") { [weak self] in
            Task { @MainActor in self?.endBackgroundGrace() }
        }
    }

    private func endBackgroundGrace() {
        guard backgroundTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(backgroundTask)
        backgroundTask = .invalid
    }

    /// 回前台：让 Go 侧 Resume（踢信令重连 + 重热对端会话）。有目标时把 overlay
    /// 亮出来直到 connected；没目标（还在登录页/选择页）什么都不显示。
    private func resumeAfterForeground() async {
        guard case .running = phase else { return }
        let launcher = self.launcher
        let st = await Task.detached { launcher.resume() }.value
        if st.attached, st.hasTarget {
            resumePending = true
        }
        publish(st)
    }

    /// overlay「重试」：对当前目标再拨一次。
    func retryConnect() {
        guard let target = status.target, !target.isEmpty else { return }
        resumePending = true
        Task {
            _ = await connect(machineId: target)
        }
    }

    /// overlay「重新选择机器」：清掉本会话的 runtimeTarget 标记并回到根页——
    /// 前端 initRuntimeTarget 看不到会话标记就回选择页（0.14.7 冷启动门的语义）。
    func backToPicker() {
        resumePending = false
        let launcher = self.launcher
        Task.detached { _ = launcher.disconnect() }
        scriptRunner?("try{sessionStorage.removeItem('vantaloom.runtimeSession')}catch(e){};location.replace('/');0")
    }

    // MARK: - helpers

    /// 解析 setChrome 推来的 "#rrggbb" / "rgb(a)(…)"（native-chrome.ts 已经用 canvas 归一化过）。
    static func parseCSSColor(_ css: String) -> Color? {
        let s = css.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if s.hasPrefix("#") {
            var hex = String(s.dropFirst())
            if hex.count == 3 { hex = hex.map { "\($0)\($0)" }.joined() }
            guard hex.count == 6 || hex.count == 8, let v = UInt64(hex, radix: 16) else { return nil }
            let shift: UInt64 = hex.count == 8 ? 8 : 0
            let r = Double((v >> (16 + shift)) & 0xff) / 255
            let g = Double((v >> (8 + shift)) & 0xff) / 255
            let b = Double((v >> shift) & 0xff) / 255
            let a = hex.count == 8 ? Double(v & 0xff) / 255 : 1
            return Color(red: r, green: g, blue: b, opacity: a)
        }
        if s.hasPrefix("rgb") {
            let inner = s.drop { $0 != "(" }.dropFirst().prefix { $0 != ")" }
            let parts = inner.split(separator: ",").map { Double($0.trimmingCharacters(in: .whitespaces)) ?? 0 }
            guard parts.count >= 3 else { return nil }
            let a = parts.count >= 4 ? parts[3] : 1
            return Color(red: parts[0] / 255, green: parts[1] / 255, blue: parts[2] / 255, opacity: a)
        }
        return nil
    }
}
