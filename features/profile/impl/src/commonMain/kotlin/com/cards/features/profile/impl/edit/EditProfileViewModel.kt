package com.dangerfield.cards.features.profile.impl.edit

import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.libraries.cards.InventoryRepository
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.identity.profile.AvatarPack
import com.dangerfield.cards.libraries.identity.profile.AvatarPackOutcome
import com.dangerfield.cards.libraries.identity.profile.Profile
import com.dangerfield.cards.libraries.identity.profile.ProfileRepository
import com.dangerfield.cards.libraries.identity.profile.UpdateProfileOutcome
import com.dangerfield.cards.libraries.ui.snackbar.showSnackBar
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject

/**
 * Edit-profile screen logic. Owns:
 *  - initial state seeded from the current authenticated profile (so the
 *    form pre-fills)
 *  - avatar pack load on entry (so the picker has emojis to show)
 *  - submit path: validate locally, call repo, surface outcome-specific
 *    errors, emit a save event on success.
 *
 * Dirty-tracking: the Save button is enabled only when something actually
 * changed AND inputs are valid. Avoids stale `PATCH /v1/me` calls with
 * zero net effect.
 *
 * Hard-gated on [Profile.Authenticated] — there's nothing to edit when
 * we're on a Fallback profile, and the screen shouldn't be reachable in
 * that state.
 */
@Inject
class EditProfileViewModel(
    private val profileRepository: ProfileRepository,
    private val inventoryRepository: InventoryRepository,
    private val appScope: AppCoroutineScope,
) : SEAViewModel<EditProfileState, EditProfileEvent, EditProfileAction>(
    initialStateArg = EditProfileState(),
) {

    init {
        viewModelScope.launch {
            // Wait for the first Authenticated profile to land. Fallback
            // is filtered out — the user shouldn't be on this screen
            // when there's no real profile to edit.
            val profile = profileRepository.observe()
                .filterIsInstance<Profile.Authenticated>()
                .first()
            takeAction(EditProfileAction.SeedFromProfile(profile))
            takeAction(EditProfileAction.LoadAvatarPack)
        }
        // Live local-inventory observation drives pack visibility. A
        // freshly-redeemed pack writes its Pending row before sync()
        // POSTs to the server, so this flow fires the moment the user
        // taps Redeem — picker re-derives its filter and the new pack
        // appears without waiting on the network. The fetched
        // `allAvatarPacks` is the server-defined registry; ownership
        // is computed locally.
        viewModelScope.launch {
            inventoryRepository.observeInventory().collect { items ->
                takeAction(
                    EditProfileAction.OwnedProductsChanged(items.map { it.productId }.toSet()),
                )
            }
        }
        // Best-effort background sync so a future Edit Profile open
        // sees up-to-date server state. Not awaited — the picker
        // doesn't need to gate on it.
        viewModelScope.launch { inventoryRepository.sync() }
    }

    override suspend fun handleAction(action: EditProfileAction) {
        when (action) {
            is EditProfileAction.SeedFromProfile -> action.updateState {
                it.copy(
                    initialDisplayName = action.profile.displayName,
                    displayName = action.profile.displayName,
                    initialAvatarEmoji = action.profile.avatarEmoji,
                    selectedAvatarEmoji = action.profile.avatarEmoji,
                    initialAvatarBackgroundColor = action.profile.avatarBackgroundColor,
                    selectedAvatarBackgroundColor = action.profile.avatarBackgroundColor,
                )
            }

            is EditProfileAction.LoadAvatarPack -> action.run {
                updateState { it.copy(isLoadingAvatars = true) }
                val outcome = profileRepository.fetchAvatarPack()
                updateState {
                    when (outcome) {
                        is AvatarPackOutcome.Success -> it.copy(
                            isLoadingAvatars = false,
                            allAvatarPacks = outcome.packs,
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
                            allAvatarPacks = listOfNotNull(it.selectedAvatarEmoji).takeIf { it.isNotEmpty() }
                                ?.let { listOf(AvatarPack("current", "Current", it)) }
                                ?: emptyList(),
                            avatarLoadError = true,
                        )
                    }
                }
            }

            is EditProfileAction.OwnedProductsChanged -> action.updateState {
                it.copy(ownedProductIds = action.productIds)
            }

            is EditProfileAction.DisplayNameChanged -> action.updateState {
                it.copy(
                    displayName = action.value.take(EditProfileState.MAX_NAME_LENGTH),
                    displayNameError = null,
                )
            }

            is EditProfileAction.AvatarSelected -> action.updateState {
                it.copy(selectedAvatarEmoji = action.emoji)
            }

            is EditProfileAction.AvatarBackgroundColorSelected -> action.updateState {
                it.copy(selectedAvatarBackgroundColor = action.color)
            }

            is EditProfileAction.DismissError -> action.updateState {
                it.copy(displayNameError = null)
            }

            is EditProfileAction.Submit -> action.run {
                val current = state
                if (!current.canSubmit) return@run

                updateState {
                    it.copy(isSubmitting = true, displayNameError = null)
                }

                val colorChanged = current.selectedAvatarBackgroundColor != current.initialAvatarBackgroundColor
                val displayName = current.displayName
                    .takeIf { it.trim() != current.initialDisplayName?.trim() }
                    ?.trim()
                val avatarEmoji = current.selectedAvatarEmoji
                    .takeIf { it != current.initialAvatarEmoji }
                val avatarBackgroundColor = current.selectedAvatarBackgroundColor
                    ?.takeIf { colorChanged }
                val clearAvatarBackgroundColor = colorChanged && current.selectedAvatarBackgroundColor == null

                // The request runs on appScope so it survives VM teardown
                // (user backs out the moment they tap Save). When the
                // displayName changed, we *await* the outcome on the VM
                // scope so DisplayNameTaken / InvalidDisplayName can
                // surface as an inline field error instead of a
                // snackbar that fires after the user has already
                // navigated away. Avatar-only edits stay optimistic —
                // they can't fail validation in a way the user needs to
                // fix before leaving the screen.
                val deferred = appScope.async {
                    profileRepository.update(
                        displayName = displayName,
                        avatarEmoji = avatarEmoji,
                        avatarBackgroundColor = avatarBackgroundColor,
                        clearAvatarBackgroundColor = clearAvatarBackgroundColor,
                    )
                }

                if (displayName != null) {
                    // Await + branch. If the VM scope is torn down
                    // mid-await (user backed out), the request still
                    // completes on appScope — we just won't react to it
                    // here, which is fine: no name was saved if it
                    // would have been taken, and a network failure is
                    // recoverable next session.
                    handleDisplayNameSubmitOutcome(deferred.await())
                } else {
                    // Optimistic: navigate immediately, surface any
                    // failure as a snackbar in the background.
                    appScope.launch {
                        deferred.await().toFailureMessage()?.let { showSnackBar(it) }
                    }
                    sendEvent(EditProfileEvent.Saved)
                }
            }
        }
    }

    private suspend fun EditProfileAction.Submit.handleDisplayNameSubmitOutcome(
        outcome: UpdateProfileOutcome,
    ) {
        when (outcome) {
            is UpdateProfileOutcome.Success -> sendEvent(EditProfileEvent.Saved)
            UpdateProfileOutcome.DisplayNameTaken -> updateState {
                it.copy(
                    isSubmitting = false,
                    displayNameError = EditProfileDisplayNameError.Taken,
                )
            }
            UpdateProfileOutcome.InvalidDisplayName -> updateState {
                it.copy(
                    isSubmitting = false,
                    displayNameError = EditProfileDisplayNameError.Invalid,
                )
            }
            // Non-validation failures (NotSignedIn, NetworkError,
            // Unknown, plus the avatar-related ones that can't happen
            // when we only sent the name) get the optimistic treatment:
            // navigate away + snackbar. The user can fix it next
            // session; they don't need to be stuck on Edit Profile.
            else -> {
                outcome.toFailureMessage()?.let { showSnackBar(it) }
                sendEvent(EditProfileEvent.Saved)
            }
        }
    }

    private fun UpdateProfileOutcome.toFailureMessage(): String? = when (this) {
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
}

data class EditProfileState(
    val initialDisplayName: String? = null,
    val displayName: String = "",
    val initialAvatarEmoji: String? = null,
    val selectedAvatarEmoji: String? = null,
    val initialAvatarBackgroundColor: String? = null,
    val selectedAvatarBackgroundColor: String? = null,
    /**
     * Full server-defined pack registry (Starter + every premium pack).
     * The picker filters this against [ownedProductIds] — see
     * [avatarPacks]. Premium packs the user doesn't own are present
     * here but filtered out of the rendered list.
     */
    val allAvatarPacks: List<AvatarPack> = emptyList(),
    /**
     * Live snapshot of product ids in the local inventory (Pending +
     * Confirmed). Drives optimistic pack visibility — a freshly-
     * redeemed pack flips its row into this set before the inventory
     * sync POST lands, so the picker shows the new pack immediately.
     */
    val ownedProductIds: Set<String> = emptySet(),
    val backgroundPalette: List<String> = emptyList(),
    val isLoadingAvatars: Boolean = false,
    val avatarLoadError: Boolean = false,
    val isSubmitting: Boolean = false,
    val displayNameError: EditProfileDisplayNameError? = null,
) {
    /**
     * Display projection: every server pack, paired with whether the
     * user owns it. Locked packs render dimmed with a 🔒 overlay and
     * a "Get in shop" CTA so the user sees the breadth of what's
     * available (incentive to buy) instead of an artificially short
     * grid. The picker disables tap on locked tiles — the CTA is the
     * only path forward.
     *
     * **Order:** unlocked packs first (starter + every owned premium),
     * then locked packs. Server order is preserved within each group
     * via stable sort — what the user *can* actually pick from sits
     * at the top of the picker; the locked aspirational rows sit
     * below as the upsell shelf.
     */
    val avatarPacks: List<AvatarPackDisplay>
        get() = allAvatarPacks
            .map { pack ->
                AvatarPackDisplay(
                    pack = pack,
                    isLocked = pack.unlockProductId != null && pack.unlockProductId !in ownedProductIds,
                )
            }
            .sortedBy { it.isLocked }

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
        const val MAX_NAME_LENGTH = 16
    }
}

/**
 * One row in the avatar picker. Wraps the server [AvatarPack] with the
 * locally-derived ownership state so the screen layer doesn't have to
 * cross-reference inventory itself.
 */
data class AvatarPackDisplay(
    val pack: AvatarPack,
    val isLocked: Boolean,
)

sealed interface EditProfileEvent {
    data object Saved : EditProfileEvent
}

/**
 * Inline error surfaced under the display-name field. Typed so the VM
 * doesn't hold raw user-facing copy — `EditProfileScreen.kt` resolves
 * each variant through Compose Multiplatform resources at render time.
 */
sealed interface EditProfileDisplayNameError {
    data object Taken : EditProfileDisplayNameError
    data object Invalid : EditProfileDisplayNameError
}

sealed interface EditProfileAction {
    data class SeedFromProfile(val profile: Profile.Authenticated) : EditProfileAction
    data object LoadAvatarPack : EditProfileAction
    data class OwnedProductsChanged(val productIds: Set<String>) : EditProfileAction
    data class DisplayNameChanged(val value: String) : EditProfileAction
    data class AvatarSelected(val emoji: String) : EditProfileAction
    data class AvatarBackgroundColorSelected(val color: String) : EditProfileAction
    data object Submit : EditProfileAction
    data object DismissError : EditProfileAction
}
