# Vantaloom iOS 壳（仅操控端，2026-09-07）

SwiftUI + WKWebView 的薄壳，**只做操控**：登录账号 → 列出同账号机器 → 用 loomnet 的
连接方式（局域网直连 / 公网直连 / 中继 …）连到用户自己的电脑 → 前端（控制端形态）
指向那台电脑。手机上不跑 agent、不存会话、不执行工具——安卓壳「仅控制端」那一套的
原生复刻。设计文档 `docs/ios-shell-design.md`；调研与可信度分级
`docs/research/ios-controller-2026-09/`。

> **本目录里的 Swift 一行都没有编译过**——写它的机器是 Windows，没有 Xcode / swiftc。
> 公开仓 CI（`ci/build-ios.yml`）与用户的 Mac 是唯一验证。Go 侧本机验证到
> `go test ./internal/controllernode/`（全平台跑）与 `GOOS=ios GOARCH=arm64
> CGO_ENABLED=0 go build ./internal/...`（编译不链接）。

## 目录

```
apps/ios/
├─ project.yml              XcodeGen 工程描述（不要手写 .pbxproj）
├─ project-node.yml         有 xcframework 时经 include.enable 并入：依赖 + -DVANTALOOM_EMBEDDED_NODE
├─ Info.plist               手写；ATS 只开 NSAllowsLocalNetworking；「本地网络」用途说明；后台模式刻意没开
├─ Sources/
│  ├─ App/                  VantaloomApp / RootView / LaunchView（占位页 + 错误页）
│  ├─ Node/                 ControllerLauncher（契约）/ EmbeddedControllerNode（C ABI）/
│  │                        ControllerHost（生命周期 + 状态轮询 + 前后台）/ DeviceIdentity（Keychain 设备 id）
│  └─ Shell/                WebShellView（WKWebView）/ LoomBridge（__loomBridge 控制端子集）/ ConnectionOverlay（重连状态卡）
├─ node/                    xcframework 落点（gitignore）+ module.modulemap + README（C ABI 表）
├─ scripts/build-node-xcframework.sh   Mac 上：Go c-archive → VantaloomNode.xcframework
├─ ci/build-ios.yml         公开仓 workflow 草案（无签名 IPA → Release ios-build<run>-<sha>）
└─ web/                     （gitignore）CI 同步的前端编译产物
```

## 在 Mac 上跑起来

```bash
brew install xcodegen
# 1) 操控端节点（可选；没有它壳会显示「操控端节点未打包」）
apps/ios/scripts/build-node-xcframework.sh
# 2) 前端编译产物
pnpm build   # 或只构建 apps/vantaloom
rsync -a --exclude '*.map' apps/vantaloom/out/ apps/ios/web/
# 3) 工程
cd apps/ios
VANTALOOM_IOS_NODE=true xcodegen generate     # 或 false
open Vantaloom.xcodeproj
# Xcode 里：Signing & Capabilities → Automatically manage signing + 自己的 Team；真机运行。
```

机测步骤与预期见 `docs/ios-shell-design.md`「用户在 Mac 上的机测步骤」。

## 与安卓壳的对照（细表见设计文档）

| 安卓（Vantaloom-app 仓 `android/`） | iOS（本目录） |
| --- | --- |
| `Loom.kt`：gomobile AAR 里的 `mobile.Bridge`（mini Hub client，只有局域网直连） | `ControllerHost.swift`：同进程调 `VantaloomController*`（`internal/controllernode`：完整 hubconn 网关 + 全部拨号方式） |
| WebViewAssetLoader 提供页面（`http://vantaloom.localhost`）+ API 跨源 + shim 补丁 fetch/XHR/EventSource/WebSocket | 节点自己提供页面（同源）；`?vtlboot=` 一次性令牌换 HttpOnly cookie，之后零补丁 |
| `window.__loomBridge` 合同 v2（同步方法直接调 Java；secret 门） | 同一份合同的**控制端子集**：同步方法读注入常量 / 原生推的状态缓存；只接受回环源主框架的消息 |
| `deviceId()` = `android-<ssaid>` | `ios-<uuid>`（identifierForVendor 首次取到后进 Keychain，重装不变） |
| 前台服务保活；切后台节点继续跑 | 切后台约 30s 后挂起；回前台 `Resume` 重连 + 状态卡 |
| APK 自更新 / 图片文件选择 / 本地运行时 | 不实现（前端按能力探测自动隐藏） |
