package com.dangerfield.cards.libraries.identity.impl.auth

import com.dangerfield.cards.libraries.identity.auth.AuthOutcome
import com.dangerfield.cards.libraries.identity.auth.AuthOutcomeClassifier
import com.dangerfield.cards.libraries.identity.profile.ProfileRepository
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Reads the brand-new-account discriminator off [ProfileRepository] (the
 * server's one-shot `/v1/me` `isNewAccount` latch) rather than owning it, so the
 * signal stays single-sourced and shared with the Home welcome — and the auth
 * layer keeps no dependency on the profile layer (which already depends on it).
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class DefaultAuthOutcomeClassifier(
    private val profileRepository: ProfileRepository,
) : AuthOutcomeClassifier {

    override suspend fun classify(wasLink: Boolean): AuthOutcome = when {
        wasLink -> AuthOutcome.Linked
        profileRepository.resolveIsNewAccount() -> AuthOutcome.SignedUp
        else -> AuthOutcome.SignedIn
    }
}
