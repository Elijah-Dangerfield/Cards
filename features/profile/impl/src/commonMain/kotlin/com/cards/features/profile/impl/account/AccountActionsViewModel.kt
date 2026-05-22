package com.dangerfield.cards.features.profile.impl.account

import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.identity.IdentityRepository
import kotlinx.coroutines.async
import me.tatarka.inject.annotations.Inject

/**
 * The "destructive account actions" VM that sits behind the Profile screen.
 * Owns sign-out; delete-account has its own VM ([DeleteAccountViewModel])
 * because it lives on a dedicated type-to-confirm screen.
 *
 * Lives in its own file rather than on a hypothetical full `ProfileViewModel`
 * because Profile's read state is composed from observed flows directly in
 * the entry point (progression, user, AppCache). Mixing the destructive
 * write paths into the same VM would either fight the existing collect-
 * directly pattern or duplicate it. Cleaner to keep this small.
 *
 * The actual navigation back to onboarding lives in the entry point —
 * the VM emits an event when sign-out completes, the entry point watches
 * `IdentityRepository.state` for `Unknown` and routes accordingly.
 */
@Inject
class AccountActionsViewModel(
    private val identityRepository: IdentityRepository,
    private val appCache: AppCache,
    private val appScope: AppCoroutineScope,
) : SEAViewModel<AccountActionsState, AccountActionsEvent, AccountActionsAction>(
    initialStateArg = AccountActionsState(),
) {

    override suspend fun handleAction(action: AccountActionsAction) {
        when (action) {
            is AccountActionsAction.ConfirmSignOut -> action.run {
                updateState { it.copy(isSigningOut = true) }
                appScope.async {
                    identityRepository.signOut()
                    appCache.update { it.copy(hasUserOnboarded = false) }
                }.await()
                updateState { it.copy(isSigningOut = false) }
                sendEvent(AccountActionsEvent.SignedOut)
            }
        }
    }
}

data class AccountActionsState(
    val isSigningOut: Boolean = false,
)

sealed interface AccountActionsEvent {
    data object SignedOut : AccountActionsEvent
}

sealed interface AccountActionsAction {
    data object ConfirmSignOut : AccountActionsAction
}
