import SwiftUI

// Vantaloom iOS 壳（SwiftUI + WKWebView）。
//
// 形状与 apps/desktop（Wails）一样是「薄壳」：启动进程内的 Go 运行时 →
// 把 WKWebView 指到 http://127.0.0.1:<port>/ → 一个最小 JS 桥。与安卓壳
// （Kotlin，子进程 + WebView 资产源 + 跨源 CORS）的逐项对照见
// docs/ios-shell-design.md。
//
// 本机（Windows）没有 Xcode，这些 Swift 文件**没有在任何地方编译过**；
// 公开仓 CI（apps/ios/ci/build-ios.yml）是唯一验证。

@main
struct VantaloomApp: App {
    @StateObject private var host = RuntimeHost()
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
