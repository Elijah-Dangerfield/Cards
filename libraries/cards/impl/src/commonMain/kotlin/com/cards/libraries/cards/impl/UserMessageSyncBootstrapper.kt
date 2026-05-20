package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.AppEventListener
import com.dangerfield.cards.libraries.cards.UserMessageSyncService
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.identity.IdentityRepository
import com.dangerfield.cards.libraries.identity.awaitIdentity
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Triggers [UserMessageSyncService.sync] on cold boot + warm foreground.
 *
 * Mirrors [ChipsSyncBootstrapper]: each launch suspends on
 * `IdentityRepository.awaitIdentity()` so we don't race onboarding and
 * 401 the messages endpoint before anonymous sign-in resolves.
 *
 * No work scheduled on foreground if it's the cold-boot foreground —
 * the cold-boot branch already owns that pass.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, multibinding = true, boundType = AppEventListener::class)
@Inject
class UserMessageSyncBootstrapper(
    private val syncService: UserMessageSyncService,
    // Lazy provider — same DI-cycle workaround as the chips bootstrapper.
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
