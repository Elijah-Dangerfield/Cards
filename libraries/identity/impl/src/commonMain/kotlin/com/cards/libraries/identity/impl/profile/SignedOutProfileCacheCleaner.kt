package com.dangerfield.cards.libraries.identity.impl.profile

import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.AppEventListener
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logOnFailure
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * On [AppEvent.SignedOut], drop the persisted profile mirror + avatar-pack
 * cache so the next account doesn't briefly show the previous user's
 * name/avatar (or their unlocked packs) before its own `/v1/me` lands.
 *
 * Deliberately a standalone listener that depends only on the two caches — not
 * on [ProfileRepositoryImpl] / `AuthRepository`. Wiring the repo itself as an
 * `AppEventListener` would form a DI cycle: `AppEventDispatcher → Set<AppEventListener>
 * → ProfileRepositoryImpl → AuthRepository → AppEventBus(AppEventDispatcher)`.
 * The caches have no such dependency, so this breaks the loop.
 *
 * `ProfileCache.clear()` also resets the device-local fallback id — intentional
 * for a full sign-out reset (a fresh Fallback identity). The auth observer in
 * the repo separately re-resolves to Fallback; this just clears the stale
 * persisted record the resolve would otherwise fall back to.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, multibinding = true, boundType = AppEventListener::class)
@Inject
class SignedOutProfileCacheCleaner(
    private val profileCache: ProfileCache,
    private val avatarPackCache: AvatarPackCache,
    private val appScope: AppCoroutineScope,
) : AppEventListener {
    private val logger = KLog.withTag("SignOutCleanup")

    override fun onSignedOut(event: AppEvent.SignedOut) {
        appScope.launch {
            Catching { profileCache.clear() }.logOnFailure { "Profile cache clear on sign-out failed" }
            Catching { avatarPackCache.clear() }.logOnFailure { "Avatar pack clear on sign-out failed" }
        }
    }
}
