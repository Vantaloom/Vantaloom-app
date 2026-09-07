import Foundation

#if VANTALOOM_EMBEDDED_RUNTIME
// `VantaloomRuntime` 是 xcframework 里 module.modulemap 声明的 clang 模块，
// 头文件 libvantaloom.h 由 `go build -buildmode=c-archive ./cmd/vantaloom-ios`
// 生成（apps/api/cmd/vantaloom-ios/export_ios.go 里的 //export 函数）。
import VantaloomRuntime

/// C ABI 约定（与 export_ios.go 锁步）：
///   char* VantaloomStart(char* configJSON)   -> Status JSON，用完 VantaloomFree
///   char* VantaloomStop(int timeoutMillis)   -> Status JSON
///   char* VantaloomStatus(void)              -> Status JSON
///   char* VantaloomBootToken(void)           -> 一次性启动令牌（未启动/未鉴权时 ""）
///   void  VantaloomFree(char*)
final class EmbeddedRuntime: RuntimeLauncher {
    let isEmbedded = true

    func start(config: RuntimeConfig) throws -> RuntimeStatus {
        let data = try JSONEncoder().encode(config)
        let json = String(decoding: data, as: UTF8.self)
        // cgo 生成的签名是 char*（非 const），所以走 strdup 而不是 withCString。
        guard let cstr = strdup(json) else { throw RuntimeError.startFailed("strdup failed") }
        defer { free(cstr) }
        let status = try decode(VantaloomStart(cstr))
        if let error = status.error, !error.isEmpty, !status.running {
            throw RuntimeError.startFailed(error)
        }
        return status
    }

    func stop(timeoutMillis: Int32) -> RuntimeStatus {
        (try? decode(VantaloomStop(timeoutMillis))) ?? RuntimeStatus()
    }

    func status() -> RuntimeStatus {
        (try? decode(VantaloomStatus())) ?? RuntimeStatus()
    }

    func mintBootToken() -> String? {
        guard let raw = VantaloomBootToken() else { return nil }
        defer { VantaloomFree(raw) }
        let token = String(cString: raw)
        return token.isEmpty ? nil : token
    }

    private func decode(_ raw: UnsafeMutablePointer<CChar>?) throws -> RuntimeStatus {
        guard let raw else { throw RuntimeError.invalidStatus("<null>") }
        defer { VantaloomFree(raw) }
        let text = String(cString: raw)
        guard let data = text.data(using: .utf8) else { throw RuntimeError.invalidStatus(text) }
        do {
            return try JSONDecoder().decode(RuntimeStatus.self, from: data)
        } catch {
            throw RuntimeError.invalidStatus(text)
        }
    }
}
#endif
