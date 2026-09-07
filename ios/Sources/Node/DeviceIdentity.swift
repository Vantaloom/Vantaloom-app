import Foundation
import Security
import UIKit

/// 设备级稳定 id：`ios-<uuid>`，对应安卓的 `android-<ssaid>`（DeviceId.kt）。
///
/// 前端把它当 Hub 注册的 hardwareId（(user, hardwareId) upsert 判重），所以它必须
/// 重装不变：identifierForVendor 在「同 vendor 的应用全部卸载」后会变，单靠它不够，
/// 因此第一次取到后写进 Keychain（kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly：
/// 不进 iCloud 钥匙串同步——同步到另一台 iPhone 就成了两台设备共用一个身份，
/// 与安卓 SSAID 全零撞行是同一类事故）。Keychain 里的值优先；取不到 IDFV
/// （极少数情况返回 nil）就用随机 UUID，同样落 Keychain。
///
/// 这不是 overlay 的 machineID——那个是 Hub 注册后分配的 machine.id，由前端经
/// startNode 传给节点。
enum DeviceIdentity {
    private static let service = "online.timefiles.vantaloom.device-id"
    private static let account = "controller"
    private static let lock = NSLock()
    private static var cached: String?

    static func id() -> String {
        lock.lock()
        defer { lock.unlock() }
        if let cached { return cached }
        if let stored = readKeychain(), isValid(stored) {
            cached = stored
            return stored
        }
        let raw = UIDevice.current.identifierForVendor?.uuidString ?? UUID().uuidString
        let id = "ios-" + raw.lowercased()
        writeKeychain(id)
        cached = id
        return id
    }

    /// 与安卓 DeviceId.isValidDeviceId 同一精神：空值 / 全零不是身份。
    static func isValid(_ id: String) -> Bool {
        guard id.hasPrefix("ios-") else { return false }
        let suffix = id.dropFirst(4)
        if suffix.isEmpty { return false }
        return suffix.contains { $0 != "0" && $0 != "-" }
    }

    private static func query() -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }

    private static func readKeychain() -> String? {
        var q = query()
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(q as CFDictionary, &item)
        guard status == errSecSuccess, let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func writeKeychain(_ value: String) {
        let data = Data(value.utf8)
        var add = query()
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(add as CFDictionary, nil)
        if status == errSecDuplicateItem {
            let update: [String: Any] = [kSecValueData as String: data]
            SecItemUpdate(query() as CFDictionary, update as CFDictionary)
        }
    }
}
