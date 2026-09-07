import SwiftUI
import UIKit
import WebKit

/// 承载前端的 WKWebView。
///
/// 与安卓 MainActivity 的对应关系：
///   - 安卓：WebViewAssetLoader 从 APK 资产以 http://vantaloom.localhost 源提供页面，
///     API 在另一个源 127.0.0.1:<port>（跨源 → CORS 双头坑、shim 补丁 fetch/XHR/…）。
///   - iOS：操控端节点自己从 <Bundle>/web 提供页面，页面与 API **同源**；凭据靠
///     首个导航的 ?vtlboot= 换来的 HttpOnly cookie，之后 WKWebView 自动带，零补丁。
struct WebShellView: UIViewRepresentable {
    /// 入口 URL（带一次性启动令牌）与初始状态 JSON 由 RootView（MainActor 的 body）
    /// 从 host 读好传进来——makeUIView/updateUIView 里不直接碰 @MainActor 的 host 成员。
    let launchURL: URL?
    let reloadToken: Int
    let initialStatusJSON: String
    @EnvironmentObject private var host: ControllerHost

    func makeCoordinator() -> Coordinator { Coordinator(host: host) }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.allowsInlineMediaPlayback = true
        configuration.defaultWebpagePreferences.allowsContentJavaScript = true
        // 同一份 WKWebsiteDataStore.default()：localStorage（Hub 登录态、上次连接
        // 的机器）跨冷启动持久；会话 cookie 随进程轮换，旧的只会换来 401，壳看到
        // 主文档 401 就重新走 vtlboot（Coordinator.decidePolicyFor 里处理）。
        configuration.websiteDataStore = .default()

        let controller = WKUserContentController()
        controller.add(context.coordinator, contentWorld: .page, name: LoomBridge.handlerName)
        controller.addUserScript(WKUserScript(
            source: LoomBridge.shimSource(constants: LoomBridge.constants(), initialStatusJSON: initialStatusJSON),
            injectionTime: .atDocumentStart,
            forMainFrameOnly: true
        ))
        configuration.userContentController = controller

        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = context.coordinator
        webView.uiDelegate = context.coordinator
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        webView.allowsBackForwardNavigationGestures = false
        webView.backgroundColor = .systemBackground
        webView.isOpaque = false
        #if DEBUG
        if #available(iOS 16.4, *) { webView.isInspectable = true }
        #endif
        context.coordinator.webView = webView
        context.coordinator.installSinks()
        if let launchURL {
            context.coordinator.load(launchURL, token: reloadToken)
        }
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        if let launchURL {
            context.coordinator.load(launchURL, token: reloadToken)
        }
    }

    static func dismantleUIView(_ webView: WKWebView, coordinator: Coordinator) {
        webView.configuration.userContentController.removeScriptMessageHandler(forName: LoomBridge.handlerName, contentWorld: .page)
        coordinator.removeSinks()
    }

    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler {
        weak var webView: WKWebView?
        private let host: ControllerHost
        private var loadedToken: Int?
        private lazy var bridge = LoomBridge(host: host)

        init(host: ControllerHost) { self.host = host }

        func load(_ url: URL, token: Int) {
            guard loadedToken != token, let webView else { return }
            loadedToken = token
            webView.load(URLRequest(url: url))
        }

        /// 把 host 的状态推送与脚本执行口接到这个 WebView 上。makeUIView /
        /// dismantleUIView 都在主线程被调；host 是 @MainActor，这里用
        /// assumeIsolated 而不是 Task 跳一拍（要在首个导航之前就装好）。
        func installSinks() {
            let host = self.host
            MainActor.assumeIsolated {
                host.statusSink = { [weak self] json in
                    self?.run(LoomBridge.pushStatusScript(json) + "0;")
                }
                host.scriptRunner = { [weak self] script in
                    self?.run(script)
                }
            }
        }

        func removeSinks() {
            let host = self.host
            MainActor.assumeIsolated {
                host.statusSink = nil
                host.scriptRunner = nil
            }
        }

        /// 在页面里执行一段 JS。用带 completionHandler 的重载：Swift 并发版的
        /// evaluateJavaScript 在 JS 返回 undefined 时会崩（WebKit 已知问题），
        /// 这里所有脚本也都以 `0;` 收尾。
        private func run(_ script: String) {
            guard let webView else { return }
            if Thread.isMainThread {
                webView.evaluateJavaScript(script, completionHandler: nil)
            } else {
                DispatchQueue.main.async { webView.evaluateJavaScript(script, completionHandler: nil) }
            }
        }

        // MARK: 导航策略：只有回环源留在壳里，其余交给系统（Safari / 邮件 / 电话）。

        func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
                     decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            guard let url = navigationAction.request.url else { decisionHandler(.cancel); return }
            if Self.isLoopbackOrigin(url) || url.absoluteString == "about:blank" {
                decisionHandler(.allow)
                return
            }
            // 子框架（设计预览 iframe 之类）里的回环/私网 http(s) 文档允许就地加载，
            // 与安卓 ShellSecurity.isPreviewableSubframeUrl 同一口径。
            if let target = navigationAction.targetFrame, !target.isMainFrame, Self.isPreviewableSubframe(url) {
                decisionHandler(.allow)
                return
            }
            if ["http", "https", "mailto", "tel"].contains(url.scheme?.lowercased() ?? "") {
                UIApplication.shared.open(url, options: [:], completionHandler: nil)
            }
            decisionHandler(.cancel)
        }

        func webView(_ webView: WKWebView, decidePolicyFor navigationResponse: WKNavigationResponse,
                     decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
            // 主文档拿到 401 = 回环会话 cookie 失效（WebContent 进程重建过 / 节点重启过）。
            // 换一枚启动令牌重来一次。
            if navigationResponse.isForMainFrame,
               let http = navigationResponse.response as? HTTPURLResponse, http.statusCode == 401 {
                decisionHandler(.cancel)
                Task { @MainActor in self.host.requestReload() }
                return
            }
            decisionHandler(.allow)
        }

        // window.open / target=_blank：不造第二个 WebView，一律外抛。
        func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                     for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
            if let url = navigationAction.request.url, !Self.isLoopbackOrigin(url) {
                UIApplication.shared.open(url, options: [:], completionHandler: nil)
            }
            return nil
        }

        // MARK: JS → native

        func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
            guard message.name == LoomBridge.handlerName else { return }
            // 只接受回环源**主框架**的消息：预览 iframe 里的第三方文档（同一个 WKWebView）
            // 够不到桥。这是安卓 secret 门在 iOS 上的等价物。
            let frame = message.frameInfo
            guard frame.isMainFrame else { return }
            let origin = frame.securityOrigin
            guard origin.protocol == "http", origin.host == "127.0.0.1" else { return }
            bridge.handle(message.body, reply: { [weak self] script in
                self?.run(script)
            })
        }

        // MARK: helpers

        private static func isLoopbackOrigin(_ url: URL) -> Bool {
            guard let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "ws" else { return false }
            return url.host == "127.0.0.1"
        }

        private static func isPreviewableSubframe(_ url: URL) -> Bool {
            guard let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https",
                  let host = url.host?.lowercased() else { return false }
            if host == "localhost" || host == "::1" || host == "[::1]" { return true }
            let octets = host.split(separator: ".").compactMap { Int($0) }
            guard octets.count == 4 else { return false }
            if octets[0] == 127 || octets[0] == 10 { return true }
            if octets[0] == 192, octets[1] == 168 { return true }
            if octets[0] == 172, (16...31).contains(octets[1]) { return true }
            return false
        }
    }
}
