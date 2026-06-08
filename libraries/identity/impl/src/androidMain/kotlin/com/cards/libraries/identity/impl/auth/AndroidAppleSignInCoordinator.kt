package com.dangerfield.cards.libraries.identity.impl.auth

import com.dangerfield.cards.libraries.identity.auth.AppleSignInCoordinator
import com.dangerfield.cards.libraries.identity.auth.AppleSignInCredential
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Android binding for [AppleSignInCoordinator]. Apple sign-in is iOS-only, so
 * this never runs in practice — the onboarding/sign-in surfaces don't show the
 * Apple button on Android. It exists so the Android DI graph has a binding for
 * the type. The iOS graph gets the real Swift coordinator via `IosAppComponent`.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AndroidAppleSignInCoordinator : AppleSignInCoordinator {
    override suspend fun requestCredential(): AppleSignInCredential? =
        throw UnsupportedOperationException("Sign in with Apple is available on iOS only")
}
