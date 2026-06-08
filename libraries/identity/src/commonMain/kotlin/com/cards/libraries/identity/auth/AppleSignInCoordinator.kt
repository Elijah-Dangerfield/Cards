package com.dangerfield.cards.libraries.identity.auth

/**
 * The id token + raw nonce captured from a native "Sign in with Apple" flow,
 * handed to [AuthRepository.signInWithApple] / [AuthRepository.linkAppleIdentity]
 * to exchange for a Supabase session.
 *
 * The name fields are only populated on the user's *first* authorization with
 * the app — Apple never sends them again — so a caller that wants to keep them
 * must persist them at first sign-in.
 */
data class AppleSignInCredential(
    val identityToken: String,
    val nonce: String,
    val authorizationCode: String?,
    val givenName: String? = null,
    val familyName: String? = null,
) {
    val fullName: String? = listOfNotNull(givenName, familyName)
        .joinToString(" ")
        .trim()
        .takeIf { it.isNotEmpty() }
}

/** Thrown when the user dismisses the native Apple sheet — callers treat it as a quiet no-op. */
class AppleSignInCancelled : Throwable()

/**
 * Runs the platform-native "Sign in with Apple" flow and returns the resulting
 * credential. The iOS implementation is a Swift `ASAuthorizationController`
 * coordinator injected from `iOSApp.swift`; Android binds a no-op
 * ([com.dangerfield.cards.libraries.identity.impl.auth.AndroidAppleSignInCoordinator])
 * because Apple sign-in is iOS-only.
 *
 * Lives in `commonMain` (no `@ObjCName` — that annotation is native-only), so
 * the Swift-visible name is the framework's default export prefix. The Swift
 * coordinator conforms to that generated protocol name.
 */
interface AppleSignInCoordinator {
    /** Suspends until the user completes the sheet. Throws [AppleSignInCancelled] on dismissal. */
    suspend fun requestCredential(): AppleSignInCredential
}
