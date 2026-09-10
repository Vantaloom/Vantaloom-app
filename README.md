# Vantaloom App（手机端）

Vantaloom 的移动端公开仓：**安卓原生壳 + iOS 操控端壳 + 编译产物**。

专有的后端与前端源码**不在这里**，只有编译好的产物（`web/`、`libvantaloom.so`）。

## 目录

| 目录 | 内容 |
|---|---|
| `android/` | 原生 Kotlin 壳（WebView + loomnet 桥 + 本机运行时托管） |
| `ios/` | SwiftUI 操控端壳（**只操控**：登录 → 选电脑 → 连过去；手机上不跑 agent） |
| `mobile-src/` | 公开的移动端 Go 源码（loomnet / mobile，经 `sync-mobile-src` 整包同步） |
| `web/` | 编译后的前端静态产物（安卓本机模式用） |
| `signing/` | APK 签名 keystore——所有构建共用，装新版可覆盖安装，不必卸载 |

## 构建

推 `main` 即触发，两条流水线都按路径过滤：

| Workflow | 触发路径 | 产物 |
|---|---|---|
| `build-apk.yml` | `android/` `mobile-src/` `web/` | 签名 APK → Release `apk-build<run>-<sha>`，标为 **Latest** |
| `build-ios.yml` | `ios/` `web/` | 无签名 IPA → Release `ios-build<run>-<sha>`，`--latest=false` |

> ⚠️ **`apk-build*` 必须始终是本仓的 Latest。** 安卓 APK 自更新硬编码读
> `api.github.com/repos/Vantaloom/Vantaloom-app/releases/latest`
> （见 `android/.../AppUpdate.kt`）。任何新加的 workflow 发 Release 都必须带
> `--latest=false`，否则会把自更新指到一个不是 APK 的产物上。

推送只是**发起**构建，不等于发布完成：要等对应 SHA 的 workflow 跑成 success、
且 Release 里真的挂上了 APK 资产，才算数。

## 版本

安卓**没有** npm 包，整包走 GitHub Releases 自更新，
`versionCode` = CI run 号 = Release tag 里的 `build<run>`。
任何按 npm registry 判「是不是最新」的界面在安卓上都会恒真地说「已是最新」。

## 分仓（2026-09-11）

本仓原先还装着桌面壳、PC 被控端与 Docker 部署，现已拆分：

- **桌面壳** → [`Vantaloom/Vantaloom-desktop`](https://github.com/Vantaloom/Vantaloom-desktop)
- **PC 被控端（`node/`）** — 实验性功能，证实不好用，整套删除
- **Docker 部署（`docker/`）** — 实验性功能，过于粗糙，整套删除
