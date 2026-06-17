package com.dangerfield.cards.features.profile.impl.edit

import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.libraries.cards.Achievement
import com.dangerfield.cards.libraries.cards.AchievementRepository
import com.dangerfield.cards.libraries.cards.AllAchievementsById
import com.dangerfield.cards.libraries.cards.EquipmentRepository
import com.dangerfield.cards.libraries.cards.InventoryRepository
import com.dangerfield.cards.libraries.cards.ProgressionRepository
import com.dangerfield.cards.libraries.cards.levelProgressFor
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.identity.profile.AvatarPack
import com.dangerfield.cards.libraries.identity.profile.AvatarPackOutcome
import com.dangerfield.cards.libraries.identity.profile.DisplayNameRules
import com.dangerfield.cards.libraries.identity.profile.MAX_FEATURED_BADGES
import com.dangerfield.cards.libraries.identity.profile.Profile
import com.dangerfield.cards.libraries.identity.profile.ProfileRepository
import com.dangerfield.cards.libraries.identity.profile.UpdateProfileOutcome
import com.dangerfield.cards.libraries.ui.components.poker.badgeEmojiForProductId
import com.dangerfield.cards.libraries.ui.components.poker.titleForProductId
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
    private val equipmentRepository: EquipmentRepository,
    private val progressionRepository: ProgressionRepository,
    private val achievementRepository: AchievementRepository,
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
        // The equipped title is part of the public Player Card (the View
        // tab), so reflect it live. Resolve the equipped `title_*`
        // cosmetic to its display label via the shared catalog.
        viewModelScope.launch {
            equipmentRepository.observeEquipped().collect { entries ->
                val title = entries.firstNotNullOfOrNull { titleForProductId(it.productId) }
                val badge = entries.firstNotNullOfOrNull { badgeEmojiForProductId(it.productId) }
                takeAction(
                    EditProfileAction.EquippedCosmeticsChanged(title = title, permanentBadgeEmoji = badge),
                )
            }
        }
        // Level drives the "Lvl N" chip on the card preview.
        viewModelScope.launch {
            progressionRepository.observeProgression().collect { progression ->
                takeAction(EditProfileAction.LevelChanged(levelProgressFor(progression.totalXp).level))
            }
        }
        // Earned achievements feed the featured-badge picker. Resolve each
        // earned id to its static definition (skipping any unknown id from a
        // server ahead of this build) and order most-recently-earned first so
        // the default selection reads as "your latest wins."
        viewModelScope.launch {
            achievementRepository.observeProgress().collect { progress ->
                val earned = progress.earned.entries
                    .sortedByDescending { it.value }
                    .mapNotNull { (id, _) -> AllAchievementsById[id] }
                takeAction(EditProfileAction.EarnedBadgesChanged(earned))
            }
        }
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
                    initialFeaturedBadgeIds = action.profile.featuredBadgeIds,
                    selectedFeaturedBadgeIds = action.profile.featuredBadgeIds,
                )
            }

            is EditProfileAction.EarnedBadgesChanged -> action.updateState {
                it.copy(earnedBadges = action.badges)
            }

            is EditProfileAction.ToggleFeaturedBadge -> action.updateState { s ->
                val current = s.selectedFeaturedBadgeIds
                val next = when {
                    action.id in current -> current - action.id
                    // At the cap — ignore the add; the UI disables unselected
                    // tiles once three are chosen, this is the backstop.
                    current.size >= MAX_FEATURED_BADGES -> current
                    else -> current + action.id
                }
                s.copy(selectedFeaturedBadgeIds = next)
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

            is EditProfileAction.EquippedCosmeticsChanged -> action.updateState {
                it.copy(
                    equippedTitle = action.title,
                    permanentBadgeEmoji = action.permanentBadgeEmoji,
                )
            }

            is EditProfileAction.LevelChanged -> action.updateState {
                it.copy(level = action.level)
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
                // Send the featured selection only when it actually changed.
                // An empty list (cleared all) is a real change vs. a prior
                // non-empty selection — the server reads it as "back to default."
                val featuredBadgeIds = current.selectedFeaturedBadgeIds
                    .takeIf { it != current.initialFeaturedBadgeIds }

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
                        featuredBadgeIds = featuredBadgeIds,
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
    /** Display label of the currently-equipped title cosmetic, shown on the
     * Player Card View tab. Null when no title is equipped. */
    val equippedTitle: String? = null,
    /** Glyph of the equipped permanent badge (e.g. 🏛 Founding member). */
    val permanentBadgeEmoji: String? = null,
    /** Current level, shown as the "Lvl N" chip on the card preview. */
    val level: Int? = null,
    /**
     * Earned achievements, most-recently-earned first. Feeds the featured-
     * badge picker on the Edit tab + the default selection on the View tab.
     */
    val earnedBadges: List<Achievement> = emptyList(),
    /** Featured badge ids saved on the profile when the screen opened. */
    val initialFeaturedBadgeIds: List<String> = emptyList(),
    /**
     * Featured badge ids the user has explicitly chosen this session. Empty =
     * "no explicit choice" — the card preview falls back to [featuredBadges]'
     * most-recent default.
     */
    val selectedFeaturedBadgeIds: List<String> = emptyList(),
    val isLoadingAvatars: Boolean = false,
    val avatarLoadError: Boolean = false,
    val isSubmitting: Boolean = false,
    val displayNameError: EditProfileDisplayNameError? = null,
) {
    /**
     * The packs the user can actually pick from: the starter pack plus every
     * premium pack they own. Locked (for-sale) packs are *not* surfaced here —
     * the picker is a wardrobe, not a storefront. Discovery of unowned packs
     * lives in the Shop, reached via the [hasLockedAvatarPacks] link. Server
     * order is preserved.
     */
    val avatarPacks: List<AvatarPack>
        get() = allAvatarPacks
            .filter { it.unlockProductId == null || it.unlockProductId in ownedProductIds }

    /**
     * True when the server registry holds at least one premium pack the user
     * doesn't own yet — drives the "Get more avatar packs in the Shop" link
     * under the picker. False (link hidden) once everything is owned.
     */
    val hasLockedAvatarPacks: Boolean
        get() = allAvatarPacks.any { it.unlockProductId != null && it.unlockProductId !in ownedProductIds }

    val isNameValid: Boolean
        get() = DisplayNameRules.isValid(displayName)

    /**
     * The badges rendered on the card preview: the explicit selection when
     * the user has made one, else the most-recently-earned default (capped).
     * This is the "defaults to most-recent earned when unset" contract.
     */
    val featuredBadges: List<Achievement>
        get() {
            val byId = earnedBadges.associateBy { it.id.name }
            val ids = selectedFeaturedBadgeIds.ifEmpty {
                earnedBadges.take(MAX_FEATURED_BADGES).map { it.id.name }
            }
            return ids.mapNotNull { byId[it] }
        }

    /** True once the user has picked the maximum number of featured badges. */
    val isFeaturedSelectionFull: Boolean
        get() = selectedFeaturedBadgeIds.size >= MAX_FEATURED_BADGES

    val isDirty: Boolean
        get() = displayName.trim() != initialDisplayName?.trim() ||
            selectedAvatarEmoji != initialAvatarEmoji ||
            selectedAvatarBackgroundColor != initialAvatarBackgroundColor ||
            selectedFeaturedBadgeIds != initialFeaturedBadgeIds

    val canSubmit: Boolean
        get() = !isSubmitting && isNameValid && isDirty && selectedAvatarEmoji != null

    companion object {
        // Mirror the shared rules so the range helper text and the input cap
        // stay in lock-step with validation.
        const val MIN_NAME_LENGTH = DisplayNameRules.MIN_LENGTH
        const val MAX_NAME_LENGTH = DisplayNameRules.MAX_LENGTH
    }
}

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
    data class EarnedBadgesChanged(val badges: List<Achievement>) : EditProfileAction
    data class ToggleFeaturedBadge(val id: String) : EditProfileAction
    data object LoadAvatarPack : EditProfileAction
    data class OwnedProductsChanged(val productIds: Set<String>) : EditProfileAction
    data class EquippedCosmeticsChanged(
        val title: String?,
        val permanentBadgeEmoji: String?,
    ) : EditProfileAction
    data class LevelChanged(val level: Int) : EditProfileAction
    data class DisplayNameChanged(val value: String) : EditProfileAction
    data class AvatarSelected(val emoji: String) : EditProfileAction
    data class AvatarBackgroundColorSelected(val color: String) : EditProfileAction
    data object Submit : EditProfileAction
    data object DismissError : EditProfileAction
}
