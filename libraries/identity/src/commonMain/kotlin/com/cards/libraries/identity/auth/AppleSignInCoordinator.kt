package com.dangerfield.cards.libraries.identity.auth

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

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

/** Raised by [awaitCredential] when the native flow reports a hard failure. */
class AppleSignInException(message: String) : Throwable(message)

/**
 * Runs the platform-native "Sign in with Apple" flow.
 *
 * The iOS implementation is a Swift `ASAuthorizationController` coordinator
 * injected from `iOSApp.swift` (conforms to the SKIE-exported
 * `IdentityAppleSignInCoordinator`). Android binds a no-op
 * ([com.dangerfield.cards.libraries.identity.impl.auth.AndroidAppleSignInCoordinator])
 * because Apple sign-in is iOS-only (see `docs/decisions.md`, "Apple sign-in").
 *
 * Deliberately a **plain callback, not `suspend`**, with plain parameter types:
 * a Swift class can satisfy it as a trivial protocol conformance (exactly like
 * `NativeViewFactory`'s `onTap` callbacks), with no `suspend`/`Throwable`
 * bridging across the Kotlin/Native boundary. Call sites use [awaitCredential]
 * for a coroutine-friendly view.
 */
interface AppleSignInCoordinator {
    /**
     * Runs the native sheet and reports the result via [onComplete]:
     *  - a credential on success,
     *  - `(null, null)` if the user dismissed the sheet,
     *  - `(null, errorMessage)` on a genuine failure.
     */
    fun requestCredential(onComplete: (credential: AppleSignInCredential?, errorMessage: String?) -> Unit)
}

/**
 * Suspend view over [AppleSignInCoordinator.requestCredential]: returns the
 * credential, `null` if the user cancelled, or throws [AppleSignInException] on
 * a real failure.
 */
suspend fun AppleSignInCoordinator.awaitCredential(): AppleSignInCredential? =
    suspendCancellableCoroutine { continuation ->
        requestCredential { credential, errorMessage ->
            if (errorMessage != null) {
                continuation.resumeWithException(AppleSignInException(errorMessage))
            } else {
                continuation.resume(credential)
            }
        }
    }
