import SwiftUI

// Vantaloom iOS 壳（SwiftUI + WKWebView）——**仅操控端**。
//
// 手机不跑 agent、不存会话、不执行工具：应用进程里只有一个 client-only 的
// loomnet 节点（Go c-archive，apps/api/internal/controllernode）和一个回环
// HTTP 服务，前者用局域网直连 / 公网直连 / 中继等方式连到用户自己的电脑，
// 后者把随包前端提供给 WKWebView 并把全部 API 调用代理到那台电脑。这是安卓
// 壳「仅控制端」那一套在 iOS 上的原生复刻；与安卓的逐项对照见
// docs/ios-shell-design.md。
//
// 本机（Windows）没有 Xcode，这些 Swift 文件**没有在任何地方编译过**；
// 公开仓 CI（apps/ios/ci/build-ios.yml）与用户的 Mac 是唯一验证。

@main
struct VantaloomApp: App {
    @StateObject private var host = ControllerHost()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(host)
                .task { await host.startIfNeeded() }
        }
        .onChange(of: scenePhase) { _, phase in
            host.handleScenePhase(phase)
        }
    }
}
