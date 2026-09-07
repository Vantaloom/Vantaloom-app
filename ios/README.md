# Vantaloom iOS 壳（脚手架，2026-09-07）

SwiftUI + WKWebView 的薄壳：启动**进程内**的 Go 运行时（`apps/api/cmd/vantaloom-ios`
编成的 c-archive）→ WKWebView 指到 `http://127.0.0.1:<port>/` → 一个最小 JS 桥。
设计文档 `docs/ios-shell-design.md`；调研与可信度分级
`docs/research/ios-sandbox-2026-09/`。

> **本目录里的 Swift 一行都没有编译过**——写它的机器是 Windows，没有 Xcode / swiftc。
> 公开仓 CI（`ci/build-ios.yml`）与用户的 Mac 是唯一验证。Go 侧只验证到
> `GOOS=ios GOARCH=arm64 CGO_ENABLED=0 go build ./internal/iosruntime`（编译不链接）。

## 目录

```
apps/ios/
├─ project.yml              XcodeGen 工程描述（不要手写 .pbxproj）
├─ project-runtime.yml      有 xcframework 时经 include.enable 并入：依赖 + -DVANTALOOM_EMBEDDED_RUNTIME
├─ Info.plist               手写；ATS 只开 NSAllowsLocalNetworking；后台模式刻意没开（注释说明）
├─ Sources/
│  ├─ App/                  VantaloomApp / RootView / LaunchView（占位页 + 错误页）
│  ├─ Runtime/              RuntimeLauncher（契约）/ EmbeddedRuntime（C ABI）/ RuntimeHost（生命周期）
│  └─ Web/                  WebShellView（WKWebView）/ LoomBridge（最小桥 + shim）
├─ runtime/                 xcframework 落点（gitignore）+ module.modulemap + README
├─ scripts/build-runtime-xcframework.sh   Mac 上：Go c-archive → xcframework
├─ ci/build-ios.yml         公开仓 workflow 草案（无签名 IPA → Release ios-build<run>-<sha>）
└─ web/                     （gitignore）CI 同步的前端编译产物
```

## 在 Mac 上跑起来

```bash
brew install xcodegen
# 1) 运行时（可选；没有它壳会显示「运行时未打包」）
apps/ios/scripts/build-runtime-xcframework.sh
# 2) 前端编译产物
pnpm build   # 或只构建 apps/vantaloom
rsync -a --exclude '*.map' apps/vantaloom/out/ apps/ios/web/
# 3) 工程
cd apps/ios
VANTALOOM_IOS_RUNTIME=true xcodegen generate     # 或 false
open Vantaloom.xcodeproj
# Xcode 里：Signing & Capabilities → Automatically manage signing + 自己的 Team；真机运行。
```

## 与安卓壳的对照（细表见设计文档）

| 安卓 | iOS |
| --- | --- |
| `LocalRuntime.kt`：子进程 exec `libvantaloom.so`，port-file 回传端口 | `RuntimeHost.swift`：同进程调 `VantaloomStart`，端口直接返回 |
| 凭据经 0600 一次性文件 + 环境变量 | 凭据直接进 JSON 配置（没有子进程可泄露给） |
| WebViewAssetLoader 提供页面（`http://vantaloom.localhost`）+ API 跨源 + shim 补丁 fetch/XHR/EventSource/WebSocket | 运行时自己提供页面（同源）；`?vtlboot=` 一次性令牌换 HttpOnly cookie，之后零补丁 |
| `window.__loomBridge`（合同 v2，同步方法 + secret 门） | `window.__vantaloomIOS`（第一期刻意不占 `__loomBridge`，见 LoomBridge.swift 头注释） |
| proot + Ubuntu rootfs 的 Linux 沙箱 | **没有**：iOS 不能 fork/exec；候选路线见调研 04 |
| 前台服务 / wake lock / 开机自启 | **没有**：切后台约 30s 后挂起；iOS 26 BGContinuedProcessingTask 属第二期 |
