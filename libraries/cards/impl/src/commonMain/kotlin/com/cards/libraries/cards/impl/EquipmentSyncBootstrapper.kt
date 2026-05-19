package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.AppEventListener
import com.dangerfield.cards.libraries.cards.EquipmentSyncService
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.identity.IdentityRepository
import com.dangerfield.cards.libraries.identity.awaitIdentity
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Kicks [EquipmentSyncService.sync] on cold boot + every foreground
 * entry — parallel to [InventorySyncBootstrapper].
 *
 * Cold boot pulls the server's authoritative snapshot (the request is
 * the same shape whether or not we have pending ops, so this doubles as
 * "what's equipped right now from any device"). Warm foreground catches
 * the user who toggled offline and then came back online.
 *
 * Each launch suspends on [IdentityRepository.awaitIdentity] before
 * hitting the network — cold boot races onboarding and would otherwise
 * fire a 401 against the equipment endpoint before anonymous sign-in
 * resolves.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, multibinding = true, boundType = AppEventListener::class)
@Inject
class EquipmentSyncBootstrapper(
    private val syncService: EquipmentSyncService,
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
        if (event.isColdBoot) return
        appScope.launch {
            identityRepository.awaitIdentity()
            syncService.sync()
        }
    }
}
