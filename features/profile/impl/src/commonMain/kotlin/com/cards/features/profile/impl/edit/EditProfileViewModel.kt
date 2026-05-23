package com.dangerfield.cards.features.profile.impl.edit

import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.identity.AvatarPack
import com.dangerfield.cards.libraries.identity.AvatarPackOutcome
import com.dangerfield.cards.libraries.identity.Identity
import com.dangerfield.cards.libraries.identity.IdentityRepository
import com.dangerfield.cards.libraries.identity.UpdateProfileOutcome
import com.dangerfield.cards.libraries.identity.awaitIdentity
import com.dangerfield.cards.libraries.ui.snackbar.showSnackBar
import androidx.lifecycle.viewModelScope
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
    private val appScope: AppCoroutineScope,
) : SEAViewModel<EditProfileState, EditProfileEvent, EditProfileAction>(
    initialStateArg = EditProfileState(),
) {

    init {
        viewModelScope.launch {
            // awaitIdentity returns immediately when bootstrap is done
            // (the common path post-onboarding) and waits for the brief
            // cache-hydrate window otherwise. See KDoc — `.first()` on a
            // StateFlow is the right primitive here.
            val identity = identityRepository.awaitIdentity()
            takeAction(EditProfileAction.SeedFromIdentity(identity))
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
                    initialAvatarBackgroundColor = action.identity.avatarBackgroundColor,
                    selectedAvatarBackgroundColor = action.identity.avatarBackgroundColor,
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
                            backgroundPalette = outcome.palette,
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

            is EditProfileAction.AvatarBackgroundColorSelected -> action.updateState {
                it.copy(selectedAvatarBackgroundColor = action.color, error = null)
            }

            is EditProfileAction.DismissError -> action.updateState { it.copy(error = null) }

            is EditProfileAction.Submit -> action.run {
                val current = state
                if (!current.canSubmit) return@run

                updateState { it.copy(isSubmitting = true, error = null) }

                val colorChanged = current.selectedAvatarBackgroundColor != current.initialAvatarBackgroundColor
                val displayName = current.displayName
                    .takeIf { it.trim() != current.initialDisplayName?.trim() }
                    ?.trim()
                val avatarEmoji = current.selectedAvatarEmoji
                    .takeIf { it != current.initialAvatarEmoji }
                val avatarBackgroundColor = current.selectedAvatarBackgroundColor
                    ?.takeIf { colorChanged }
                val clearAvatarBackgroundColor = colorChanged && current.selectedAvatarBackgroundColor == null

                appScope.launch {
                    val outcome = identityRepository.updateProfile(
                        displayName = displayName,
                        avatarEmoji = avatarEmoji,
                        avatarBackgroundColor = avatarBackgroundColor,
                        clearAvatarBackgroundColor = clearAvatarBackgroundColor,
                    )
                    val errorMessage = when (outcome) {
                        is UpdateProfileOutcome.Success -> null
                        is UpdateProfileOutcome.DisplayNameTaken ->
                            "Couldn't save — that name is already taken."
                        is UpdateProfileOutcome.InvalidDisplayName ->
                            "Couldn't save — that name isn't allowed."
                        is UpdateProfileOutcome.InvalidAvatarEmoji ->
                            "Couldn't save — that avatar isn't available."
                        is UpdateProfileOutcome.InvalidAvatarBackgroundColor ->
                            "Couldn't save — that color isn't available."
                        is UpdateProfileOutcome.NotSignedIn ->
                            "Couldn't save — sign in first."
                        is UpdateProfileOutcome.NetworkError ->
                            "Couldn't save changes — check your connection."
                        is UpdateProfileOutcome.Unknown ->
                            "Couldn't save changes. Try again."
                    }
                    if (errorMessage != null) showSnackBar(message = errorMessage)
                }

                sendEvent(EditProfileEvent.Saved)
            }
        }
    }
}

data class EditProfileState(
    val initialDisplayName: String? = null,
    val displayName: String = "",
    val initialAvatarEmoji: String? = null,
    val selectedAvatarEmoji: String? = null,
    val initialAvatarBackgroundColor: String? = null,
    val selectedAvatarBackgroundColor: String? = null,
    val avatarPacks: List<AvatarPack> = emptyList(),
    val backgroundPalette: List<String> = emptyList(),
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
            selectedAvatarEmoji != initialAvatarEmoji ||
            selectedAvatarBackgroundColor != initialAvatarBackgroundColor

    val canSubmit: Boolean
        get() = !isSubmitting && isNameValid && isDirty && selectedAvatarEmoji != null

    companion object {
        const val MIN_NAME_LENGTH = 1
        const val MAX_NAME_LENGTH = 32
    }
}

sealed interface EditProfileEvent {
    /**
     * Save was dispatched. Caller navigates back immediately — the network
     * call is optimistic-with-rollback inside [IdentityRepository.updateProfile],
     * so the user sees the change before the server confirms. Any failure
     * rolls the local Identity back and surfaces a global snackbar.
     */
    data object Saved : EditProfileEvent
}

sealed interface EditProfileAction {
    data class SeedFromIdentity(val identity: Identity) : EditProfileAction
    data object LoadAvatarPack : EditProfileAction
    data class DisplayNameChanged(val value: String) : EditProfileAction
    data class AvatarSelected(val emoji: String) : EditProfileAction
    /** Null = pick "default" (clear back to theme color). */
    data class AvatarBackgroundColorSelected(val color: String?) : EditProfileAction
    data object Submit : EditProfileAction
    data object DismissError : EditProfileAction
}
