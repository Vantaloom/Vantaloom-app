# 操控端节点 xcframework（iOS 进程内 loomnet 客户端节点）

本目录放 `VantaloomNode.xcframework`——把 `apps/api/cmd/vantaloom-ios` 以
`go build -buildmode=c-archive` 编成的静态库，连同 cgo 生成的 `libvantaloomnode.h` 与
这里的 `module.modulemap` 打包而成。**只能在 macOS + Xcode 上构建**（cgo 需要
iPhoneOS SDK 的 clang；`GOOS=ios` 的链接一律要求 cgo）。

它**不是**运行时（不跑 agent、不存会话、不执行工具），只是
`apps/api/internal/controllernode`：client-only 的 loomnet 节点 + hubconn 网关 +
127.0.0.1 回环服务（提供随包前端、把 API 代理到选定的电脑）。它的 Go 依赖图不含
`internal/server`，静态库比「整个运行时」小得多（具体体积要 Mac 上量）。

## 构建（Mac）

```bash
# 前置：Xcode（含 iOS SDK）、Go 1.26+（与 go.work 一致）
cd <仓库根>
apps/ios/scripts/build-node-xcframework.sh
# 产物：apps/ios/node/VantaloomNode.xcframework（gitignore）
#       apps/ios/node/build/ios-arm64/libvantaloomnode.{a,h}
```

脚本做的事：

1. `xcrun --sdk iphoneos` 取 SDK 路径与 clang；
2. `CGO_ENABLED=1 GOOS=ios GOARCH=arm64 go build -trimpath -buildmode=c-archive`
   （`-ldflags=-w` 去 DWARF；**不要 `-s`**——c-archive 的符号表是给 Xcode 链接用的）；
3. 把 `libvantaloomnode.h` + `module.modulemap` 放进 `include/`；
4. `xcodebuild -create-xcframework -library libvantaloomnode.a -headers include -output VantaloomNode.xcframework`。

只出 **iOS 设备（arm64）** 一个切片。模拟器切片刻意没做：Go 链接器按
`GOOS/GOARCH` 决定 Mach-O 平台，`ios/arm64` 恒标成 iOS 设备，放进模拟器构建会被
Xcode 以「linking in object file built for iOS」拒绝。在真机上验证是唯一路径
（操控端本来就要真机才有意义：局域网、蜂窝、前后台切换）。

## 用法

- 本目录存在 `VantaloomNode.xcframework` 时：
  `VANTALOOM_IOS_NODE=true xcodegen generate`（`project-node.yml` 追加依赖与
  `-DVANTALOOM_EMBEDDED_NODE`）。
- 不存在时：`VANTALOOM_IOS_NODE=false xcodegen generate`，壳编出来显示
  「操控端节点未打包」占位页——CI 在没有节点库的情况下也必须能出 IPA。

## C ABI

见 `apps/api/cmd/vantaloom-ios/export_ios.go`（每个 `char*` 返回值用完 `VantaloomFree`）：

| 函数 | 说明 |
| --- | --- |
| `char* VantaloomControllerStart(char* configJSON)` | 起回环服务（提供随包前端；API 在连入前回 503）。入参 = `controllernode.Config` JSON；返回 `Status` JSON |
| `char* VantaloomControllerAttach(char* hubUrl, char* token, char* machineId)` | = `__loomBridge.startNode`：建 client-only overlay 节点 + Hub 客户端（同参幂等） |
| `char* VantaloomControllerDetach(void)` | = `__loomBridge.stop`：拆节点与 Hub 客户端，回环照常提供页面 |
| `char* VantaloomControllerConnect(char* machineId)` | = `__loomBridge.connect`：回环指向该机器 + 预热拨号梯队（**阻塞**，最坏约 20s）；返回 `ConnectResult`（失败带逐方式原因） |
| `char* VantaloomControllerDisconnect(void)` | 清回环目标 |
| `char* VantaloomControllerSetToken(char* token)` | Hub JWT 轮换（变了才重建 Hub 客户端） |
| `char* VantaloomControllerStatus(void)` | 状态快照（前四个字段 = 安卓 statusJSON 形状 + link/relay/methods…） |
| `char* VantaloomControllerMachines(void)` | 同账号机器列表（Hub 快照 + 在线位） |
| `char* VantaloomControllerExplain(char* machineId)` | 对某机器的逐方式可达性解释 |
| `char* VantaloomControllerResume(void)` | 回前台：踢信令重连、刷目录、后台重热当前对端 |
| `char* VantaloomControllerBootToken(void)` | 一次性启动令牌（换 HttpOnly 会话 cookie） |
| `char* VantaloomControllerStop(void)` | 全部停掉 |
| `void VantaloomFree(char*)` | 释放上面任一返回值 |

Swift 侧对应 `apps/ios/Sources/Node/EmbeddedControllerNode.swift`。

## 公开仓怎么拿到它

xcframework 里是 loomnet/hubconn 这些**已经随安卓 mobile-src 公开过的**网络层代码加
`controllernode`，不含 agent/运行时。它比 `.so` 大（静态库带符号表），是否撞 GitHub
单文件 100MB 硬限要 Mac 上量过才知道。约定与之前一致：

1. 在 Mac 上构建后 `zip -r VantaloomNode.xcframework.zip VantaloomNode.xcframework`，
   `shasum -a 256` 记下摘要；
2. 上传到公开仓某个 Release 的资产（`ios-node-<版本>` 这样的独立 tag，`--latest=false`）；
3. 在公开仓 `ios/node/xcframework.lock` 写：

   ```json
   { "version": "0.16.45", "url": "https://github.com/Vantaloom/Vantaloom-app/releases/download/ios-node-0.16.45/VantaloomNode.xcframework.zip", "sha256": "<摘要>" }
   ```

4. CI（`apps/ios/ci/build-ios.yml`）见到 lock 文件就下载、校验、解压到本目录，
   然后带节点库生成工程；没有 lock 文件就出占位版 IPA（文件名带 `nonode`）。

## 尚未验证

本机（Windows）只验证了：`go test ./internal/controllernode/`（无构建标签，全平台跑）、
`GOOS=ios GOARCH=arm64 CGO_ENABLED=0 go build ./internal/...`（编译不链接；本目录的
cgo 文件在这条命令下被排除，只做了 `gofmt -e` 语法检查）。以下全部要 Mac 才能知道：

- c-archive 是否链接成功、Xcode 是否还缺别的系统库（`-lresolv` 之外）；
- Go 在 iOS 沙箱里的 DNS（controllernode 信任 darwin/ios 经 libSystem 的系统解析，
  没有兜底开关——若真机实测 Hub 解析失败，再照 0.14.29 安卓的 `VANTALOOM_DNS` 加）；
- QUIC/UDP 出站在 Wi-Fi / 蜂窝 / 「本地网络」权限被拒各情形下的行为；
- 静态库体积。
