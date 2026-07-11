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
///
/// Every non-success status is logged (AUTH-19): a TestFlight upgrade lost a
/// session with zero trace, so read/write failures must land in the session
/// log that rides feedback reports. `errSecItemNotFound` on read is the one
/// expected miss (fresh install / post-sign-out) and stays quiet.
class IOSSecureSessionStorage: IdentitySecureSessionStorage {

    private let service = "com.dangerfield.cards.supabase-session"
    private let logger = KLog.shared.withTag(tag: "IOSSecureSessionStorage")

    func read(key: String) -> String? {
        var query = baseQuery(key: key)
        query[kSecReturnData as String] = kCFBooleanTrue
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let data = item as? Data else {
            if status != errSecItemNotFound {
                logger.w { "Keychain read failed (status=\(status)) — treating as no session" }
            }
            return nil
        }
        return String(data: data, encoding: .utf8)
    }

    func write(key: String, value: String) {
        let data = Data(value.utf8)
        let update = [kSecValueData as String: data]
        var status = SecItemUpdate(baseQuery(key: key) as CFDictionary, update as CFDictionary)
        if status == errSecItemNotFound {
            var add = baseQuery(key: key)
            add[kSecValueData as String] = data
            add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
            status = SecItemAdd(add as CFDictionary, nil)
        }
        if status != errSecSuccess {
            logger.e { "Keychain write failed (status=\(status)) — session NOT persisted securely" }
        }
    }

    func delete(key: String) {
        let status = SecItemDelete(baseQuery(key: key) as CFDictionary)
        if status != errSecSuccess && status != errSecItemNotFound {
            logger.w { "Keychain delete failed (status=\(status))" }
        }
    }

    private func baseQuery(key: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
    }
}
