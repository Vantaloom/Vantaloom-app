import Foundation
import UIKit

/// 最小 JS 桥（第一期）。
///
/// ── 与安卓桥合同（apps/vantaloom/features/mesh/loom-bridge.ts + MainActivity.BRIDGE_SHIM
///    + LoomJsBridge.kt）的关系 ──
///
/// 安卓合同 v2 的核心约定这里**照搬**：异步方法带 callbackId，原生用
/// `window.__loomResolve(id, payloadJson)` / `window.__loomReject(id, message)` 回填，
/// promise 登记簿只在 TS 侧有一份。但有两处结构性差异：
///
///   1. WKScriptMessageHandler 是**单向异步**的，安卓那些同步方法
///      （`statusJSON()` / `deviceId()` / `appVersionName()` …）在 iOS 上做不出来。
///      这里的做法：不变的信息（平台/版本/设备 id）在 document-start 注入成常量；
///      会变的（运行时状态）由原生**主动推**、JS 侧缓存，同步读取读的是缓存。
///   2. **第一期刻意不定义 `window.__loomBridge`。** 前端的 `isNativeShell()` 只看
///      它存不存在，一存在就切进安卓语义（startNode/connect/startLocalRuntime 那整套
///      控制端流程）。第一期 iOS 走的是桌面形状（同源运行时，前端零改动），所以桥挂在
///      `window.__vantaloomIOS` 下；等第二期决定接哪些安卓方法时再按合同补
///      `__loomBridge` 并在 loom-bridge.ts 里按能力探测（typeof）分支。
///
/// 方法对照（iOS 名 ← 安卓名）：
///   platformInfo()        ← isNative() + deviceId() + appVersionName()（注入常量）
///   runtimeStatus()       ← statusJSON()（改为原生推 + 缓存）
///   openExternal(url)     ← window.open 外抛（安卓 shim 里的 window.open 覆写）
///   keepAliveStatus()     ← persistenceStatus()（iOS 没有前台服务；如实报 suspendsInBackground=true）
///   appVersion()          ← appVersionName()
final class LoomBridge {
    static let handlerName = "loom"

    private let host: RuntimeHost

    init(host: RuntimeHost) { self.host = host }

    /// 注入常量：与安卓 `deviceId()` 对应的是 identifierForVendor（重装会变，
    /// 与 SSAID 语义不同——Hub 判重那一层第二期再对齐）。
    static func platformInfo() -> [String: Any] {
        let short = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.0.0"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "0"
        return [
            "platform": "ios",
            "isNative": true,
            "shell": "vantaloom-ios",
            "appVersion": short,
            "appBuild": build,
            "systemVersion": UIDevice.current.systemVersion,
            "deviceModel": UIDevice.current.model,
            "idiom": UIDevice.current.userInterfaceIdiom == .pad ? "pad" : "phone",
            "deviceId": "ios-" + (UIDevice.current.identifierForVendor?.uuidString.lowercased() ?? "unknown"),
        ]
    }

    /// document-start shim。不带任何 secret：第一期没有特权方法（安卓的 secret 门
    /// 保护的是 startNode/startLocalRuntime 这类能改机器身份的调用，这里一个都没有）。
    static func shimSource(platformInfo: [String: Any]) -> String {
        let infoJSON = (try? JSONSerialization.data(withJSONObject: platformInfo))
            .map { String(decoding: $0, as: UTF8.self) } ?? "{}"
        return #"""
        (function () {
          if (window.__vantaloomIOS) return;
          var pending = {};
          var seq = 0;
          var runtimeStatus = null;
          function call(method, args) {
            return new Promise(function (resolve, reject) {
              var id = "ios-" + (++seq) + "-" + Date.now();
              pending[id] = { resolve: resolve, reject: reject };
              setTimeout(function () {
                if (pending[id]) { delete pending[id]; reject(new Error("ios bridge timeout: " + method)); }
              }, 15000);
              try {
                window.webkit.messageHandlers.loom.postMessage({ id: id, method: String(method), args: args || [] });
              } catch (e) {
                delete pending[id];
                reject(e);
              }
            });
          }
          // 与安卓合同 v2 同名同签名的回填函数；一个登记簿。
          window.__loomResolve = function (id, json) {
            var p = pending[id]; if (!p) return; delete pending[id];
            var value = {};
            try { value = json ? JSON.parse(json) : {}; } catch (e) { value = { raw: String(json) }; }
            p.resolve(value);
          };
          window.__loomReject = function (id, message) {
            var p = pending[id]; if (!p) return; delete pending[id];
            p.reject(new Error(String(message || "ios bridge error")));
          };
          // 原生主动推的运行时状态（同步读取读的是缓存——WKWebView 没有同步桥）。
          window.__loomPushRuntimeStatus = function (json) {
            try { runtimeStatus = JSON.parse(json); } catch (e) { runtimeStatus = null; }
            try { window.dispatchEvent(new CustomEvent("vantaloom:ios-runtime-status", { detail: runtimeStatus })); } catch (e) {}
          };
          window.__vantaloomIOS = {
            platform: __PLATFORM_INFO__,
            call: call,
            runtimeStatus: function () { return runtimeStatus; },
            openExternal: function (url) { return call("openExternal", [String(url || "")]); },
            keepAliveStatus: function () { return call("keepAliveStatus", []); },
            appVersion: function () { return __PLATFORM_INFO__.appVersion; },
            refreshRuntimeStatus: function () { return call("runtimeStatus", []); }
          };
        })();
        """#.replacingOccurrences(of: "__PLATFORM_INFO__", with: infoJSON)
    }

    /// 处理一条 JS→native 消息；`reply` 收到的是要在页面里执行的一段 JS。
    func handle(_ body: Any, reply: @escaping (String) -> Void) {
        guard let dict = body as? [String: Any],
              let id = dict["id"] as? String,
              let method = dict["method"] as? String else { return }
        let args = dict["args"] as? [Any] ?? []

        switch method {
        case "openExternal":
            let raw = (args.first as? String) ?? ""
            guard let url = URL(string: raw),
                  ["http", "https", "mailto", "tel"].contains(url.scheme?.lowercased() ?? "") else {
                reply(Self.rejectScript(id, "openExternal: unsupported url"))
                return
            }
            Task { @MainActor in
                let ok = await UIApplication.shared.open(url)
                reply(Self.resolveScript(id, ["opened": ok]))
            }
        case "keepAliveStatus":
            // iOS 没有前台服务 / wake lock / 开机自启这些安卓概念；如实报出来，
            // 别让前端的「后台常驻」面板以为开关存在。
            reply(Self.resolveScript(id, [
                "platform": "ios",
                "suspendsInBackground": true,
                "backgroundGraceSeconds": 30,
                "continuedProcessingAvailable": Self.continuedProcessingAvailable(),
                "keepAlive": false,
                "wakeLock": false,
                "bootAutostart": false,
            ]))
        case "runtimeStatus":
            Task { @MainActor in
                let json = self.host.runtimeStatusJSON
                reply("window.__loomPushRuntimeStatus(\(Self.jsString(json)));" + Self.resolveScriptRaw(id, json))
            }
        default:
            reply(Self.rejectScript(id, "unknown method: \(method)"))
        }
    }

    private static func continuedProcessingAvailable() -> Bool {
        if #available(iOS 26.0, *) { return true }
        return false
    }

    // MARK: 回填脚本

    static func resolveScript(_ id: String, _ payload: [String: Any]) -> String {
        let data = (try? JSONSerialization.data(withJSONObject: payload)) ?? Data("{}".utf8)
        return resolveScriptRaw(id, String(decoding: data, as: UTF8.self))
    }

    static func resolveScriptRaw(_ id: String, _ json: String) -> String {
        "window.__loomResolve && window.__loomResolve(\(jsString(id)), \(jsString(json)));"
    }

    static func rejectScript(_ id: String, _ message: String) -> String {
        "window.__loomReject && window.__loomReject(\(jsString(id)), \(jsString(message)));"
    }

    /// 把任意字符串编码成 JS 字符串字面量（经 JSON 编码，防注入）。
    static func jsString(_ value: String) -> String {
        let data = (try? JSONSerialization.data(withJSONObject: [value])) ?? Data("[\"\"]".utf8)
        let array = String(decoding: data, as: UTF8.self)
        return String(array.dropFirst().dropLast())
    }
}
