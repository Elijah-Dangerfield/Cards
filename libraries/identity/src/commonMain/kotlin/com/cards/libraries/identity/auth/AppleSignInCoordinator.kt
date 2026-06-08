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

/**
 * Runs the platform-native "Sign in with Apple" flow.
 *
 * The iOS implementation is a Swift `ASAuthorizationController` coordinator
 * injected from `iOSApp.swift` (conforms to the framework-exported
 * `ComposeAppAppleSignInCoordinator` protocol). Android binds a no-op
 * ([com.dangerfield.cards.libraries.identity.impl.auth.AndroidAppleSignInCoordinator])
 * because Apple sign-in is iOS-only (see `docs/decisions.md`, "Apple sign-in").
 *
 * Cancellation is signalled by returning `null` rather than throwing — a clean
 * value crosses the Kotlin/Native boundary far more reliably than matching an
 * exception type does, and a user dismissing the sheet isn't really an error.
 * A genuine failure (network, malformed token) still throws.
 */
interface AppleSignInCoordinator {
    /** Returns the credential, or `null` if the user dismissed the sheet. Throws on real failure. */
    suspend fun requestCredential(): AppleSignInCredential?
}
