import SwiftUI

/// 按运行时状态切换三块画面：启动中 / 运行中（WebView）/ 不可用（占位或错误）。
///
/// 注意 React 那条「并列分支子结构不同 = 整棵重挂」的坑在 SwiftUI 里同样存在：
/// `WebShellView` 只在 `.running` 分支里出现一次，状态从 running 变回 starting
/// 再变 running 会重建 WKWebView——这是刻意的（运行时重启 = 新 bearer = 旧页面
/// 的 cookie 已失效，必须重新走 vtlboot）。
struct RootView: View {
    @EnvironmentObject private var host: RuntimeHost

    var body: some View {
        ZStack {
            switch host.state {
            case .idle, .starting:
                LaunchView(message: host.progressMessage)
            case let .running(endpoint):
                WebShellView(endpoint: endpoint, reloadToken: host.reloadToken)
                    .ignoresSafeArea(.keyboard, edges: .bottom)
            case let .unavailable(reason):
                RuntimeUnavailableView(
                    title: "运行时未打包",
                    reason: reason,
                    detail: "这份安装包里没有 VantaloomRuntime.xcframework。用 apps/ios/scripts/build-runtime-xcframework.sh 在 Mac 上构建后重新打包，或等公开仓 CI 从 Release 资产取到运行时。",
                    retry: nil
                )
            case let .failed(message):
                RuntimeUnavailableView(
                    title: "运行时启动失败",
                    reason: message,
                    detail: "Xcode 控制台（stderr）里有运行时的 JSON 日志；日志文件在 Application Support/Vantaloom/install/logs/。",
                    retry: { Task { await host.startIfNeeded(force: true) } }
                )
            }
        }
        .background(Color(uiColor: .systemBackground))
    }
}
