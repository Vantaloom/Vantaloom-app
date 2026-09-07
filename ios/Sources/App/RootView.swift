import SwiftUI

/// 按节点状态切换画面：启动中 / 运行中（WebView + 重连状态卡）/ 不可用（占位或错误）。
///
/// `WebShellView` 只在 `.running` 分支里出现一次：状态从 running 变回 starting
/// 再变 running 会重建 WKWebView——这是刻意的（节点重启 = 新会话密钥 = 旧页面的
/// cookie 已失效，必须重新走 vtlboot）。
struct RootView: View {
    @EnvironmentObject private var host: ControllerHost

    /// 页面推来的明暗（setChrome）；没推过就跟系统。
    private var preferredScheme: ColorScheme? {
        guard host.chromeColor != nil else { return nil }
        return host.chromeDark ? ColorScheme.dark : ColorScheme.light
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            switch host.phase {
            case .idle, .starting:
                LaunchView(message: host.progressMessage)
            case .running:
                WebShellView(
                    launchURL: host.launchURL,
                    reloadToken: host.reloadToken,
                    initialStatusJSON: host.statusJSON
                )
                .ignoresSafeArea(.keyboard, edges: .bottom)
                ConnectionOverlay()
                    .animation(.easeInOut(duration: 0.2), value: host.resumePending)
            case let .unavailable(reason):
                NodeUnavailableView(
                    title: "操控端节点未打包",
                    reason: reason,
                    detail: "这份安装包里没有 VantaloomNode.xcframework。用 apps/ios/scripts/build-node-xcframework.sh 在 Mac 上构建后重新打包，或等公开仓 CI 从 Release 资产取到节点库。",
                    retry: nil
                )
            case let .failed(message):
                NodeUnavailableView(
                    title: "操控端启动失败",
                    reason: message,
                    detail: "Xcode 控制台（stderr）里有节点的日志。",
                    retry: { Task { await host.startIfNeeded(force: true) } }
                )
            }
        }
        // 安全区边条与页面同色（native-chrome.ts 经 setChrome 推来的背景色）。
        .background((host.chromeColor ?? Color(uiColor: .systemBackground)).ignoresSafeArea())
        .preferredColorScheme(preferredScheme)
    }
}
