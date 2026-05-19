package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.AppEventListener
import com.dangerfield.cards.libraries.cards.ChipsSyncService
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.identity.IdentityRepository
import com.dangerfield.cards.libraries.identity.awaitIdentity
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Triggers [ChipsSyncService.sync] on cold boot + warm foreground.
 *
 * Cold boot: catches anything the user did in a prior session that the
 * VM-level sync hadn't yet flushed (e.g. they backgrounded mid-grant or
 * the network was offline). Empty pending list is fine — the sync is
 * also a balance-hydrate that pulls in cross-device grants.
 *
 * Foreground: catches the offline → online transition. ColdBoot fires
 * alongside OnForeground; we let the cold-boot branch own that pass and
 * only resync on warm resumes.
 *
 * Each launch suspends on [IdentityRepository.awaitIdentity] before
 * hitting the network — otherwise cold boot races onboarding and fires
 * a 401 against the wallet endpoint before anonymous sign-in resolves.
 *
 * Mirrors [InventorySyncBootstrapper] in shape and lifecycle. The sync
 * service is single-flight (Mutex), so the two bootstrappers' overlapping
 * pulses collapse to one sync at a time.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, multibinding = true, boundType = AppEventListener::class)
@Inject
class ChipsSyncBootstrapper(
    private val syncService: ChipsSyncService,
    // Lazy provider — IdentityRepository depends on AppEventBus, which feeds
    // AppEventDispatcher, which aggregates these bootstrappers. Direct
    // injection would close the cycle at compile time. Same pattern as
    // NetworkClientImpl uses for AuthTokenProvider. Both ends are SingleIn,
    // so this resolves to the same instance once the graph walks through it.
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
