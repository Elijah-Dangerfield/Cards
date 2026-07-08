package com.dangerfield.cards.libraries.identity.auth

/**
 * OS-encrypted key/value storage for the Supabase session JSON (AUTH-16).
 *
 * Android binds an `EncryptedSharedPreferences`-backed implementation
 * ([com.dangerfield.cards.libraries.identity.impl.auth.EncryptedSessionStorage]);
 * iOS injects a Keychain-backed Swift twin (`IOSSecureSessionStorage`, passed
 * in from `iOSApp.swift` per `docs/practices/swift-kotlin.md`).
 *
 * Deliberately a **plain synchronous contract with plain types** so a Swift
 * class satisfies it as a trivial protocol conformance. Callers that care
 * about threading (the session manager) wrap calls in an IO dispatcher.
 */
interface SecureSessionStorage {
    fun read(key: String): String?
    fun write(key: String, value: String)
    fun delete(key: String)
}
