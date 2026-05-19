package com.dangerfield.cards.features.profile.impl.edit

import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.identity.AvatarPack
import com.dangerfield.cards.libraries.identity.AvatarPackOutcome
import com.dangerfield.cards.libraries.identity.Identity
import com.dangerfield.cards.libraries.identity.IdentityRepository
import com.dangerfield.cards.libraries.identity.IdentityState
import com.dangerfield.cards.libraries.identity.UpdateProfileOutcome
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject

/**
 * Edit-profile screen logic. Owns:
 *  - initial state seeded from the current [Identity] (so the form pre-fills)
 *  - avatar pack load on entry (so the picker has emojis to show)
 *  - submit path: validate locally, call repo, surface outcome-specific
 *    errors, emit a save event on success.
 *
 * Dirty-tracking: the Save button is enabled only when something actually
 * changed AND inputs are valid. Avoids stale `PATCH /v1/me` calls with
 * zero net effect.
 */
@Inject
class EditProfileViewModel(
    private val identityRepository: IdentityRepository,
) : SEAViewModel<EditProfileState, EditProfileEvent, EditProfileAction>(
    initialStateArg = EditProfileState(),
) {

    init {
        // Seed from the current identity. If state is Unknown (rare —
        // user opened this before identity bootstrap), wait for it.
        viewModelScope.launch {
            val current = identityRepository.state
                .filterIsInstance<IdentityState.SignedIn>()
                .first()
            takeAction(EditProfileAction.SeedFromIdentity(current.identity))
            takeAction(EditProfileAction.LoadAvatarPack)
        }
    }

    override suspend fun handleAction(action: EditProfileAction) {
        when (action) {
            is EditProfileAction.SeedFromIdentity -> action.updateState {
                it.copy(
                    initialDisplayName = action.identity.displayName,
                    displayName = action.identity.displayName,
                    initialAvatarEmoji = action.identity.avatarEmoji,
                    selectedAvatarEmoji = action.identity.avatarEmoji,
                )
            }

            is EditProfileAction.LoadAvatarPack -> action.run {
                updateState { it.copy(isLoadingAvatars = true) }
                val outcome = identityRepository.fetchAvatarPack()
                updateState {
                    when (outcome) {
                        is AvatarPackOutcome.Success -> it.copy(
                            isLoadingAvatars = false,
                            avatarPacks = outcome.packs,
                        )
                        is AvatarPackOutcome.NetworkError,
                        is AvatarPackOutcome.Unknown,
                            -> it.copy(
                            isLoadingAvatars = false,
                            // If the pack didn't load, fall back to letting
                            // the user see at least their current emoji and
                            // keep it. Wrap it in a synthetic "current"
                            // pack so the picker still renders something.
                            avatarPacks = listOfNotNull(it.selectedAvatarEmoji).takeIf { it.isNotEmpty() }
                                ?.let { listOf(AvatarPack("current", "Current", it)) }
                                ?: emptyList(),
                            avatarLoadError = true,
                        )
                    }
                }
            }

            is EditProfileAction.DisplayNameChanged -> action.updateState {
                it.copy(displayName = action.value, error = null)
            }

            is EditProfileAction.AvatarSelected -> action.updateState {
                it.copy(selectedAvatarEmoji = action.emoji, error = null)
            }

            is EditProfileAction.DismissError -> action.updateState { it.copy(error = null) }

            is EditProfileAction.Submit -> action.run {
                val current = state
                if (!current.canSubmit) return@run

                updateState { it.copy(isSubmitting = true, error = null) }

                val outcome = identityRepository.updateProfile(
                    displayName = current.displayName
                        .takeIf { it.trim() != current.initialDisplayName?.trim() }
                        ?.trim(),
                    avatarEmoji = current.selectedAvatarEmoji
                        .takeIf { it != current.initialAvatarEmoji },
                )

                when (outcome) {
                    is UpdateProfileOutcome.Success -> {
                        updateState { it.copy(isSubmitting = false) }
                        sendEvent(EditProfileEvent.Saved)
                    }
                    is UpdateProfileOutcome.DisplayNameTaken -> updateState {
                        it.copy(isSubmitting = false, error = "That name is already taken. Try another.")
                    }
                    is UpdateProfileOutcome.InvalidDisplayName -> updateState {
                        it.copy(isSubmitting = false, error = "That name isn't allowed. Pick something 1-32 characters.")
                    }
                    is UpdateProfileOutcome.InvalidAvatarEmoji -> updateState {
                        it.copy(isSubmitting = false, error = "That avatar isn't in the starter pack. Pick another.")
                    }
                    is UpdateProfileOutcome.NotSignedIn -> updateState {
                        it.copy(isSubmitting = false, error = "Sign in first to edit your profile.")
                    }
                    is UpdateProfileOutcome.NetworkError -> updateState {
                        it.copy(isSubmitting = false, error = "Couldn't reach the server. Check your connection.")
                    }
                    is UpdateProfileOutcome.Unknown -> updateState {
                        it.copy(isSubmitting = false, error = "Couldn't save changes. Try again.")
                    }
                }
            }
        }
    }
}

data class EditProfileState(
    val initialDisplayName: String? = null,
    val displayName: String = "",
    val initialAvatarEmoji: String? = null,
    val selectedAvatarEmoji: String? = null,
    val avatarPacks: List<AvatarPack> = emptyList(),
    val isLoadingAvatars: Boolean = false,
    val avatarLoadError: Boolean = false,
    val isSubmitting: Boolean = false,
    val error: String? = null,
) {
    val isNameValid: Boolean
        get() {
            val trimmed = displayName.trim()
            return trimmed.length in MIN_NAME_LENGTH..MAX_NAME_LENGTH
        }

    val isDirty: Boolean
        get() = displayName.trim() != initialDisplayName?.trim() ||
            selectedAvatarEmoji != initialAvatarEmoji

    val canSubmit: Boolean
        get() = !isSubmitting && isNameValid && isDirty && selectedAvatarEmoji != null

    companion object {
        const val MIN_NAME_LENGTH = 1
        const val MAX_NAME_LENGTH = 32
    }
}

sealed interface EditProfileEvent {
    /** Save succeeded — caller can navigate back. */
    data object Saved : EditProfileEvent
}

sealed interface EditProfileAction {
    data class SeedFromIdentity(val identity: Identity) : EditProfileAction
    data object LoadAvatarPack : EditProfileAction
    data class DisplayNameChanged(val value: String) : EditProfileAction
    data class AvatarSelected(val emoji: String) : EditProfileAction
    data object Submit : EditProfileAction
    data object DismissError : EditProfileAction
}
