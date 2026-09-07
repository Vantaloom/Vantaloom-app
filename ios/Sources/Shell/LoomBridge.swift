import Foundation
import UIKit

/// `window.__loomBridge` 的 iOS 实现（合同 v2 的**控制端子集**）。
///
/// ── 与安卓桥合同（apps/vantaloom/features/mesh/loom-bridge.ts + MainActivity.BRIDGE_SHIM
///    + LoomJsBridge.kt）的关系 ──
///
/// 合同 v2 的核心约定这里**照搬**：异步方法带 callbackId，原生用
/// `window.__loomResolve(id, payloadJson)` / `window.__loomReject(id, message)` 回填，
/// promise 登记簿只在 TS 侧有一份（这里一份都没有）。两处结构性差异：
///
///   1. WKScriptMessageHandler 是**单向异步**的，安卓那些同步方法在 iOS 上做不出来。
///      做法：不变的信息（platform / deviceId / appVersionName）在 document-start 注入
///      成常量；会变的（statusJSON）由原生**主动推**（`window.__loomPushStatus`），
///      JS 侧缓存，同步读取读的是缓存。ControllerHost 每 1.5s 轮询 Go 节点，变了才推。
///   2. 只实现控制端需要的方法：startNode / connect / disconnect / stop / statusJSON /
///      setToken / deviceId / platform / setChrome / appVersionName。安卓独有的
///      startLocalRuntime / pickImages / pickFiles / shareFile / APK 更新 / 后台常驻 /
///      通知目标一个都**不定义**——loom-bridge.ts 对它们全是 `typeof === "function"`
///      能力探测，缺席即「不支持」而不是 undefined 崩溃。
///
/// 安卓 shim 里的 secret 门在这里不需要：shim 只注入主框架（forMainFrameOnly），
/// 且 handle() 只接受来自回环源主框架的消息（WebShellView.Coordinator 校验
/// frameInfo）。
final class LoomBridge {
    static let handlerName = "loom"

    private let host: ControllerHost

    init(host: ControllerHost) { self.host = host }

    /// 注入常量。deviceId = `ios-<uuid>`（Keychain 持久，重装不变）。
    static func constants() -> [String: Any] {
        let short = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.0.0"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "0"
        return [
            "platform": "ios",
            "shell": "vantaloom-ios",
            "appVersion": short,
            "appBuild": build,
            "appVersionName": "\(short) (\(build))",
            "systemVersion": UIDevice.current.systemVersion,
            "deviceModel": UIDevice.current.model,
            "idiom": UIDevice.current.userInterfaceIdiom == .pad ? "pad" : "phone",
            "deviceId": DeviceIdentity.id(),
        ]
    }

    /// document-start shim：定义 window.__loomBridge（控制端子集）+ 状态缓存推送口。
    /// 与安卓 BRIDGE_SHIM 一样是**纯参数序适配、零状态**——唯一的状态是状态缓存。
    static func shimSource(constants: [String: Any], initialStatusJSON: String) -> String {
        let constJSON = (try? JSONSerialization.data(withJSONObject: constants))
            .map { String(decoding: $0, as: UTF8.self) } ?? "{}"
        return #"""
        (function () {
          if (window.__loomBridge) return;
          var C = __CONSTANTS__;
          var statusJSON = __INITIAL_STATUS__;
          function post(method, args, callbackId) {
            try {
              window.webkit.messageHandlers.loom.postMessage({ method: String(method), args: args || [], callbackId: callbackId || "" });
            } catch (e) {
              if (callbackId && window.__loomReject) window.__loomReject(callbackId, "ios bridge unavailable: " + e);
            }
          }
          // 原生主动推的节点状态；statusJSON() 读缓存（WKWebView 没有同步桥）。
          Object.defineProperty(window, "__loomPushStatus", {
            configurable: false, enumerable: false, writable: false,
            value: function (json) {
              statusJSON = String(json || "");
              try { window.dispatchEvent(new CustomEvent("vantaloom:native-status", { detail: statusJSON })); } catch (e) {}
            }
          });
          window.__loomBridge = {
            isNative: function () { return true; },
            platform: function () { return "ios"; },
            deviceId: function () { return C.deviceId; },
            appVersionName: function () { return C.appVersionName; },
            statusJSON: function () { return statusJSON || '{"state":"idle"}'; },
            setToken: function (t) { post("setToken", [String(t || "")]); },
            setChrome: function (c, d) { post("setChrome", [String(c || ""), !!d]); },
            startNode: function (hubBaseUrl, hubToken, machineId, callbackId) {
              post("startNode", [String(hubBaseUrl || ""), String(hubToken || ""), String(machineId || "")], callbackId);
            },
            connect: function (machineId, callbackId) { post("connect", [String(machineId || "")], callbackId); },
            disconnect: function (callbackId) { post("disconnect", [], callbackId); },
            stop: function (callbackId) { post("stop", [], callbackId); }
          };
        })();
        """#
        .replacingOccurrences(of: "__CONSTANTS__", with: constJSON)
        .replacingOccurrences(of: "__INITIAL_STATUS__", with: jsString(initialStatusJSON))
    }

    /// 处理一条 JS→native 消息；`reply` 收到的是要在页面里执行的一段 JS。
    func handle(_ body: Any, reply: @escaping (String) -> Void) {
        guard let dict = body as? [String: Any],
              let method = dict["method"] as? String else { return }
        let args = dict["args"] as? [Any] ?? []
        let callbackId = (dict["callbackId"] as? String) ?? ""
        func str(_ i: Int) -> String { (args.count > i ? args[i] as? String : nil) ?? "" }
        func bool(_ i: Int) -> Bool { (args.count > i ? args[i] as? Bool : nil) ?? false }
        let host = self.host

        switch method {
        case "startNode":
            let hubUrl = str(0), token = str(1), machineId = str(2)
            Task { @MainActor in
                do {
                    try await host.attach(hubUrl: hubUrl, token: token, machineId: machineId)
                    reply(Self.pushStatusScript(host.statusJSON) + Self.resolveScript(callbackId, [:]))
                } catch {
                    reply(Self.pushStatusScript(host.statusJSON) + Self.rejectScript(callbackId, error.localizedDescription))
                }
            }
        case "connect":
            let machineId = str(0)
            Task { @MainActor in
                let result = await host.connect(machineId: machineId)
                if result.ok, let localUrl = result.localUrl, !localUrl.isEmpty {
                    reply(Self.pushStatusScript(host.statusJSON) + Self.resolveScript(callbackId, ["localUrl": localUrl, "path": result.path ?? ""]))
                } else {
                    reply(Self.pushStatusScript(host.statusJSON) + Self.rejectScript(callbackId, result.failureText))
                }
            }
        case "disconnect":
            Task { @MainActor in
                await host.disconnect()
                reply(Self.pushStatusScript(host.statusJSON) + Self.resolveScript(callbackId, [:]))
            }
        case "stop":
            Task { @MainActor in
                await host.detach()
                reply(Self.pushStatusScript(host.statusJSON) + Self.resolveScript(callbackId, [:]))
            }
        case "setToken":
            let token = str(0)
            Task { @MainActor in host.setToken(token) }
        case "setChrome":
            let color = str(0), dark = bool(1)
            Task { @MainActor in host.setChrome(color: color, dark: dark) }
        default:
            if !callbackId.isEmpty {
                reply(Self.rejectScript(callbackId, "unknown method: \(method)"))
            }
        }
    }

    // MARK: 回填脚本

    static func pushStatusScript(_ json: String) -> String {
        "window.__loomPushStatus && window.__loomPushStatus(\(jsString(json)));"
    }

    static func resolveScript(_ id: String, _ payload: [String: Any]) -> String {
        guard !id.isEmpty else { return "0;" }
        let data = (try? JSONSerialization.data(withJSONObject: payload)) ?? Data("{}".utf8)
        let json = String(decoding: data, as: UTF8.self)
        return "window.__loomResolve && window.__loomResolve(\(jsString(id)), \(jsString(json)));0;"
    }

    static func rejectScript(_ id: String, _ message: String) -> String {
        guard !id.isEmpty else { return "0;" }
        return "window.__loomReject && window.__loomReject(\(jsString(id)), \(jsString(message)));0;"
    }

    /// 把任意字符串编码成 JS 字符串字面量（经 JSON 编码，防注入）。
    static func jsString(_ value: String) -> String {
        let data = (try? JSONSerialization.data(withJSONObject: [value])) ?? Data("[\"\"]".utf8)
        let array = String(decoding: data, as: UTF8.self)
        return String(array.dropFirst().dropLast())
    }
}
