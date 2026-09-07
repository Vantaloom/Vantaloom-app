import SwiftUI
import UIKit
import WebKit

/// 承载前端的 WKWebView。
///
/// 与安卓 MainActivity 的对应关系：
///   - 安卓：WebViewAssetLoader 从 APK 资产以 http://vantaloom.localhost 源提供页面，
///     API 在另一个源 127.0.0.1:<port>（跨源 → CORS 双头坑、shim 补丁 fetch/XHR/…）。
///   - iOS：运行时自己从 <InstallDir>/web 提供页面，页面与 API **同源**；凭据靠
///     首个导航的 ?vtlboot= 换来的 HttpOnly cookie，之后 WKWebView 自动带，零补丁。
struct WebShellView: UIViewRepresentable {
    let endpoint: RuntimeHost.Endpoint
    let reloadToken: Int
    @EnvironmentObject private var host: RuntimeHost

    func makeCoordinator() -> Coordinator { Coordinator(host: host) }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.allowsInlineMediaPlayback = true
        configuration.defaultWebpagePreferences.allowsContentJavaScript = true
        // 同一份 WKWebsiteDataStore.default()：cookie 跨冷启动持久，旧 cookie 只会换来 401，
        // 壳看到 401 就重新走 vtlboot（Coordinator.decidePolicyFor 里处理）。
        configuration.websiteDataStore = .default()

        let controller = WKUserContentController()
        controller.add(context.coordinator, contentWorld: .page, name: LoomBridge.handlerName)
        controller.addUserScript(WKUserScript(
            source: LoomBridge.shimSource(platformInfo: LoomBridge.platformInfo()),
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
        context.coordinator.load(endpoint.launchURL, token: reloadToken)
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        context.coordinator.load(endpoint.launchURL, token: reloadToken)
    }

    static func dismantleUIView(_ webView: WKWebView, coordinator: Coordinator) {
        webView.configuration.userContentController.removeScriptMessageHandler(forName: LoomBridge.handlerName, contentWorld: .page)
    }

    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler {
        weak var webView: WKWebView?
        private let host: RuntimeHost
        private var loadedToken: Int?
        private lazy var bridge = LoomBridge(host: host)

        init(host: RuntimeHost) { self.host = host }

        func load(_ url: URL, token: Int) {
            guard loadedToken != token, let webView else { return }
            loadedToken = token
            webView.load(URLRequest(url: url))
        }

        // MARK: 导航策略：只有运行时自己的源留在壳里，其余交给系统（Safari / 邮件 / 电话）。

        func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
                     decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            guard let url = navigationAction.request.url else { decisionHandler(.cancel); return }
            if Self.isRuntimeOrigin(url) {
                decisionHandler(.allow)
                return
            }
            if ["http", "https", "mailto", "tel"].contains(url.scheme?.lowercased() ?? "") {
                UIApplication.shared.open(url)
            }
            decisionHandler(.cancel)
        }

        func webView(_ webView: WKWebView, decidePolicyFor navigationResponse: WKNavigationResponse,
                     decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
            // 主文档拿到 401 = cookie 失效（运行时重启过）。换一枚启动令牌重来一次。
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
            if let url = navigationAction.request.url, !Self.isRuntimeOrigin(url) {
                UIApplication.shared.open(url)
            }
            return nil
        }

        // MARK: JS → native

        func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
            guard message.name == LoomBridge.handlerName, let webView else { return }
            bridge.handle(message.body, reply: { script in
                Task { @MainActor in
                    _ = try? await webView.evaluateJavaScript(script)
                }
            })
        }

        private static func isRuntimeOrigin(_ url: URL) -> Bool {
            guard let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "ws" else { return false }
            return url.host == "127.0.0.1"
        }
    }
}
