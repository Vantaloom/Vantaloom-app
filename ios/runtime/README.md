# Go 运行时 xcframework（iOS 进程内 vantaloom-api）

本目录放 `VantaloomRuntime.xcframework`——把 `apps/api/cmd/vantaloom-ios` 以
`go build -buildmode=c-archive` 编成的静态库，连同 cgo 生成的 `libvantaloom.h` 与
这里的 `module.modulemap` 打包而成。**只能在 macOS + Xcode 上构建**（cgo 需要
iPhoneOS SDK 的 clang；`GOOS=ios` 的链接一律要求 cgo）。

## 构建（Mac）

```bash
# 前置：Xcode（含 iOS SDK）、Go 1.26+（与 go.work 一致）
cd <仓库根>
apps/ios/scripts/build-runtime-xcframework.sh
# 产物：apps/ios/runtime/VantaloomRuntime.xcframework（gitignore）
#       apps/ios/runtime/build/ios-arm64/libvantaloom.{a,h}
```

脚本做的事：

1. `xcrun --sdk iphoneos` 取 SDK 路径与 clang；
2. `CGO_ENABLED=1 GOOS=ios GOARCH=arm64 go build -trimpath -buildmode=c-archive`
   （`-ldflags=-w` 去 DWARF；**不要 `-s`**——c-archive 的符号表是给 Xcode 链接用的）；
3. 把 `libvantaloom.h` + `module.modulemap` 放进 `include/`；
4. `xcodebuild -create-xcframework -library libvantaloom.a -headers include -output VantaloomRuntime.xcframework`。

只出 **iOS 设备（arm64）** 一个切片。模拟器切片刻意没做：Go 链接器按
`GOOS/GOARCH` 决定 Mach-O 平台，`ios/arm64` 恒标成 iOS 设备，放进模拟器构建会被
Xcode 以「linking in object file built for iOS」拒绝；`ios/amd64` 才是模拟器
（Intel），Apple Silicon 上的 arm64 模拟器需要额外处理（gomobile 的 `iossimulator`
目标走的是另一套 clang 旗标）。在真机上验证是第一期的路径。

## 用法

- 本目录存在 `VantaloomRuntime.xcframework` 时：
  `VANTALOOM_IOS_RUNTIME=true xcodegen generate`（`project-runtime.yml` 追加依赖与
  `-DVANTALOOM_EMBEDDED_RUNTIME`）。
- 不存在时：`VANTALOOM_IOS_RUNTIME=false xcodegen generate`，壳编出来显示
  「运行时未打包」占位页——CI 在没有运行时的情况下也必须能出 IPA。

## C ABI

见 `apps/api/cmd/vantaloom-ios/export_ios.go`：

| 函数 | 说明 |
| --- | --- |
| `char* VantaloomStart(char* configJSON)` | 启动；入参 = `iosruntime.Config` JSON；返回 `Status` JSON |
| `char* VantaloomStop(int timeoutMillis)` | 优雅停机 |
| `char* VantaloomStatus(void)` | 当前状态 |
| `char* VantaloomBootToken(void)` | 一次性启动令牌（换 HttpOnly cookie） |
| `void VantaloomFree(char*)` | 释放上面任一返回值 |

Swift 侧对应 `apps/ios/Sources/Runtime/EmbeddedRuntime.swift`。

## 公开仓怎么拿到它

xcframework 里是完整的专有后端，与安卓 `libvantaloom.so` 同一纪律：**只发编译产物**。
但它比 `.so` 大（静态库带符号表，估计 100MB+，撞 GitHub 单文件 100MB 硬限），不能像
`.so` 那样直接提交。约定：

1. 在 Mac 上构建后 `zip -r VantaloomRuntime.xcframework.zip VantaloomRuntime.xcframework`，
   `shasum -a 256` 记下摘要；
2. 上传到公开仓某个 Release 的资产（可以是 `ios-runtime-<版本>` 这样的独立 tag，
   `--latest=false`）；
3. 在公开仓 `ios/runtime/xcframework.lock` 写：

   ```json
   { "version": "0.16.45", "url": "https://github.com/Vantaloom/Vantaloom-app/releases/download/ios-runtime-0.16.45/VantaloomRuntime.xcframework.zip", "sha256": "<摘要>" }
   ```

4. CI（`apps/ios/ci/build-ios.yml`）见到 lock 文件就下载、校验、解压到本目录，
   然后带运行时生成工程；没有 lock 文件就出占位版 IPA。

## 尚未验证

本机（Windows）只验证了 `GOOS=ios GOARCH=arm64 CGO_ENABLED=0 go build ./internal/iosruntime`
（编译不链接）。以下全部要 Mac 才能知道：

- c-archive 是否链接成功、Xcode 是否还缺别的系统库（`-lresolv` 之外）；
- Go 在 iOS 沙箱里的 DNS（`Config.dnsServers` 是兜底开关）；
- `server.New` 内所有子系统在 iOS 上的行为（`os/exec` 一律 EPERM：shell/terminal/
  MCP stdio/NSFA/Claude Code 适配器/浏览器边车都会**响亮失败**而不是静默——第一期
  就是要看清楚哪些功能在 iOS 上结构性不存在）；
- 静态库体积与 App Store 的 200MB 蜂窝下载线。
