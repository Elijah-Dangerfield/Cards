package com.dangerfield.cards.libraries.identity.impl.profile

import com.dangerfield.cards.libraries.cards.UserScopedClearer
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logOnFailure
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * When the active user changes, drop the persisted profile mirror + avatar-pack
 * cache so the incoming account never briefly shows the previous user's
 * name/avatar (or their unlocked packs) before its own `/v1/me` lands.
 *
 * Deliberately a standalone [UserScopedClearer] that depends only on the two
 * caches — not on `ProfileRepositoryImpl` / `AuthRepository`. Wiring the repo
 * itself in here would form a DI cycle (`AuthRepository → UserScopedDataReset →
 * this → ProfileRepository → AuthRepository`). The caches have no such
 * dependency, so this breaks the loop.
 *
 * This runs *before* the auth layer emits the new [AuthState], which is what
 * lets `ProfileRepositoryImpl` re-resolve the incoming user without racing this
 * wipe: it only wakes on the new emission, after the clear has completed.
 *
 * `ProfileCache.clear()` also resets the device-local fallback id — intentional
 * for a full reset (a fresh Fallback identity); the auth observer in the repo
 * separately re-resolves.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, multibinding = true, boundType = UserScopedClearer::class)
@Inject
class UserScopedProfileCacheCleaner(
    private val profileCache: ProfileCache,
    private val avatarPackCache: AvatarPackCache,
) : UserScopedClearer {

    override suspend fun clear(previousUserId: String) {
        Catching { profileCache.clear() }.logOnFailure { "Profile cache clear on user change failed" }
        Catching { avatarPackCache.clear() }.logOnFailure { "Avatar pack clear on user change failed" }
    }
}
