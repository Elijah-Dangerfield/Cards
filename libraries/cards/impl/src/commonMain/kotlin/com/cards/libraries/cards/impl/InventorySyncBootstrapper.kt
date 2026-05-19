package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.AppEventListener
import com.dangerfield.cards.libraries.cards.InventorySyncService
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.identity.IdentityRepository
import com.dangerfield.cards.libraries.identity.awaitIdentity
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Kicks [InventorySyncService.sync] on cold boot + every foreground entry.
 *
 * Cold boot: catches anything left Pending from a prior session that the
 * VM's own init-time sync hadn't yet reconciled (e.g. the user backgrounded
 * the app mid-redeem). The VM ALSO triggers a sync — both pathways are
 * idempotent thanks to the service's mutex + the server endpoint's
 * last-write-wins semantics.
 *
 * Foreground: catches the case where the user redeemed offline, then came
 * back online. Sync without requiring them to open the shop tab again.
 *
 * Background: no work — the user's local state is already the truth on
 * device, and the server-side reconcile waits for the next foreground.
 *
 * Each launch suspends on [IdentityRepository.awaitIdentity] before
 * hitting the network — cold boot races onboarding and would otherwise
 * fire a 401 against the inventory endpoint before anonymous sign-in
 * resolves.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, multibinding = true, boundType = AppEventListener::class)
@Inject
class InventorySyncBootstrapper(
    private val syncService: InventorySyncService,
    // Lazy provider to break the DI cycle through AppEventBus —
    // see [ChipsSyncBootstrapper] for the full explanation.
    private val identityRepositoryProvider: () -> IdentityRepository,
    private val appScope: AppCoroutineScope,
) : AppEventListener {

    private val identityRepository: IdentityRepository by lazy { identityRepositoryProvider() }

    override fun onColdBoot(event: AppEvent.ColdBoot) {
        appScope.launch {
            identityRepository.awaitIdentity()
            syncService.sync()
        }
    }

    override fun onForeground(event: AppEvent.OnForeground) {
        // ColdBoot fires alongside OnForeground; let the cold-boot path
        // own that first sync. Warm-resume into foreground IS where we
        // want a fresh reconcile though, so this branch handles that.
        if (event.isColdBoot) return
        appScope.launch {
            identityRepository.awaitIdentity()
            syncService.sync()
        }
    }
}
