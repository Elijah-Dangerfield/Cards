import ComposeApp
import Foundation
import Security

/// Keychain-backed implementation of the Kotlin `SecureSessionStorage`
/// protocol (SKIE-exported as `IdentitySecureSessionStorage`) — the Supabase
/// session (access + refresh token) lives as a generic-password item instead
/// of plaintext NSUserDefaults (AUTH-16).
///
/// `kSecAttrAccessibleAfterFirstUnlock` so the auto-refresh that fires on a
/// background wake can still read the token after a reboot-then-unlock.
class IOSSecureSessionStorage: IdentitySecureSessionStorage {

    private let service = "com.dangerfield.cards.supabase-session"

    func read(key: String) -> String? {
        var query = baseQuery(key: key)
        query[kSecReturnData as String] = kCFBooleanTrue
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    func write(key: String, value: String) {
        let data = Data(value.utf8)
        let update = [kSecValueData as String: data]
        let status = SecItemUpdate(baseQuery(key: key) as CFDictionary, update as CFDictionary)
        if status == errSecItemNotFound {
            var add = baseQuery(key: key)
            add[kSecValueData as String] = data
            add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
            SecItemAdd(add as CFDictionary, nil)
        }
    }

    func delete(key: String) {
        SecItemDelete(baseQuery(key: key) as CFDictionary)
    }

    private func baseQuery(key: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
    }
}
