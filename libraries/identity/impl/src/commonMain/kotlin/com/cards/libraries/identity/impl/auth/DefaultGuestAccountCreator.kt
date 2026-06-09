package com.dangerfield.cards.libraries.identity.impl.auth

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logOnFailure
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.identity.auth.AccountCreationState
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.GuestAccountCreator
import com.dangerfield.cards.libraries.identity.auth.PendingIdentity
import com.dangerfield.cards.libraries.identity.auth.SignInOutcome
import com.dangerfield.cards.libraries.identity.profile.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * App-scoped [GuestAccountCreator]. Runs the create job on [AppCoroutineScope]
 * so it isn't tied to the onboarding ViewModel's lifecycle — the user can page
 * through the rest of onboarding (or the VM can be recreated) while creation is
 * in flight.
 *
 * Success is defined by an anonymous session existing; the profile apply is
 * best-effort (the server seeds a usable generated name on first contact, so a
 * failed patch just leaves that default in place — not a creation failure).
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = GuestAccountCreator::class)
@Inject
class DefaultGuestAccountCreator(
    private val authRepository: AuthRepository,
    private val profileRepository: ProfileRepository,
    private val appScope: AppCoroutineScope,
) : GuestAccountCreator {

    private val logger = KLog.withTag("GuestAccountCreator")
    private val _state = MutableStateFlow<AccountCreationState>(AccountCreationState.Idle)
    override val state: StateFlow<AccountCreationState> = _state.asStateFlow()

    override fun start(identity: PendingIdentity) {
        // Don't restart a run that's in flight or already done; a Failed state
        // is restartable (that's the retry path).
        val current = _state.value
        if (current is AccountCreationState.InProgress || current is AccountCreationState.Succeeded) {
            logger.d { "start: ignored — already ${current::class.simpleName}" }
            return
        }
        logger.d { "start: launching guest-account creation" }
        _state.value = AccountCreationState.InProgress
        appScope.launch { runCreate(identity) }
    }

    override fun retry() {
        val failed = _state.value as? AccountCreationState.Failed ?: return
        logger.d { "retry: re-attempting failed guest-account creation" }
        start(failed.identity)
    }

    override suspend fun awaitTerminal(): AccountCreationState =
        state.first { it is AccountCreationState.Succeeded || it is AccountCreationState.Failed }

    private suspend fun runCreate(identity: PendingIdentity) {
        val next = when (val outcome = authRepository.createGuestSession()) {
            is SignInOutcome.Success -> {
                // Session is live → account exists. Apply the chosen identity
                // best-effort; failure here is non-fatal (server's generated
                // default stands and the user can rename later).
                Catching {
                    profileRepository.update(
                        displayName = identity.displayName,
                        avatarEmoji = identity.avatarEmoji,
                        avatarBackgroundColor = identity.avatarBackgroundColor,
                    )
                }.logOnFailure { "Guest profile apply failed (non-fatal)" }
                logger.i { "Guest account created" }
                AccountCreationState.Succeeded
            }
            else -> {
                logger.w { "Guest account creation failed: ${outcome::class.simpleName}" }
                AccountCreationState.Failed(identity, cause = outcome.causeOrNull())
            }
        }
        _state.value = next
    }

    private fun SignInOutcome.causeOrNull(): Throwable? = when (this) {
        is SignInOutcome.NetworkError -> cause
        is SignInOutcome.Unknown -> cause
        else -> null
    }
}
