package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AchievementRepository
import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.AppEventListener
import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.cards.EquipmentRepository
import com.dangerfield.cards.libraries.cards.InventoryRepository
import com.dangerfield.cards.libraries.cards.ProgressionRepository
import com.dangerfield.cards.libraries.cards.SessionRepository
import com.dangerfield.cards.libraries.cards.UserRepository
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * On [AppEvent.SignedOut] every device-local user repository wipes its
 * own data. One listener per repo so:
 *
 *  - Ownership stays local — chips know how to forget themselves, the
 *    identity layer doesn't have to learn every storage shape.
 *  - The dispatcher's per-listener Catching{} wrap means a single
 *    failing clear (say, a corrupted Room row) doesn't block the
 *    others. The next session still starts clean for the categories
 *    that successfully wiped.
 *  - Adding a new storage layer = add a listener here. No edits to
 *    SupabaseIdentityRepository or the dispatcher.
 *
 * Listeners launch on [AppCoroutineScope] (suspend-side) because
 * [AppEventListener] callbacks are non-suspend by contract. They DO
 * NOT block the dispatcher — the user sees the onboarding pager
 * immediately; the wipes finish in the background and the next sign-in
 * sees a clean slate.
 *
 * Order isn't guaranteed across listeners — each repo's clear must be
 * idempotent and independent.
 */

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, multibinding = true, boundType = AppEventListener::class)
@Inject
class ChipsClearOnSignOut(
    private val repository: ChipsRepository,
    private val appScope: AppCoroutineScope,
) : AppEventListener {
    override fun onSignedOut(event: AppEvent.SignedOut) {
        appScope.launch {
            Catching { repository.deleteAll() }
                .onFailure { KLog.withTag("SignOutCleanup").w(it) { "Chips deleteAll failed" } }
        }
    }
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, multibinding = true, boundType = AppEventListener::class)
@Inject
class InventoryClearOnSignOut(
    private val repository: InventoryRepository,
    private val appScope: AppCoroutineScope,
) : AppEventListener {
    override fun onSignedOut(event: AppEvent.SignedOut) {
        appScope.launch {
            Catching { repository.deleteAll() }
                .onFailure { KLog.withTag("SignOutCleanup").w(it) { "Inventory deleteAll failed" } }
        }
    }
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, multibinding = true, boundType = AppEventListener::class)
@Inject
class EquipmentClearOnSignOut(
    private val repository: EquipmentRepository,
    private val appScope: AppCoroutineScope,
) : AppEventListener {
    override fun onSignedOut(event: AppEvent.SignedOut) {
        appScope.launch {
            Catching { repository.deleteAll() }
                .onFailure { KLog.withTag("SignOutCleanup").w(it) { "Equipment deleteAll failed" } }
        }
    }
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, multibinding = true, boundType = AppEventListener::class)
@Inject
class ProgressionClearOnSignOut(
    private val repository: ProgressionRepository,
    private val appScope: AppCoroutineScope,
) : AppEventListener {
    override fun onSignedOut(event: AppEvent.SignedOut) {
        appScope.launch {
            Catching { repository.deleteAll() }
                .onFailure { KLog.withTag("SignOutCleanup").w(it) { "Progression deleteAll failed" } }
        }
    }
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, multibinding = true, boundType = AppEventListener::class)
@Inject
class AchievementClearOnSignOut(
    private val repository: AchievementRepository,
    private val appScope: AppCoroutineScope,
) : AppEventListener {
    override fun onSignedOut(event: AppEvent.SignedOut) {
        appScope.launch {
            Catching { repository.deleteAll() }
                .onFailure { KLog.withTag("SignOutCleanup").w(it) { "Achievement deleteAll failed" } }
        }
    }
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, multibinding = true, boundType = AppEventListener::class)
@Inject
class UserClearOnSignOut(
    private val repository: UserRepository,
    private val appScope: AppCoroutineScope,
) : AppEventListener {
    override fun onSignedOut(event: AppEvent.SignedOut) {
        appScope.launch {
            Catching { repository.deleteAll() }
                .onFailure { KLog.withTag("SignOutCleanup").w(it) { "User deleteAll failed" } }
        }
    }
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, multibinding = true, boundType = AppEventListener::class)
@Inject
class SessionEndOnSignOut(
    private val repository: SessionRepository,
    private val appScope: AppCoroutineScope,
) : AppEventListener {
    override fun onSignedOut(event: AppEvent.SignedOut) {
        appScope.launch {
            // SessionRepository tracks the active *play* session; sign-out
            // is by definition the end of any session, so close it. The
            // dispatcher's wrap catches any IO failure.
            Catching { repository.endCurrentSession() }
                .onFailure { KLog.withTag("SignOutCleanup").w(it) { "Session endCurrentSession failed" } }
        }
    }
}
