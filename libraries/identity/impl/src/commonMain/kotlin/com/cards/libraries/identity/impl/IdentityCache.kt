package com.dangerfield.cards.libraries.identity.impl

import com.dangerfield.cards.libraries.identity.Identity
import com.dangerfield.cards.libraries.storage.Cache
import com.dangerfield.cards.libraries.storage.CacheFactory
import com.dangerfield.cards.libraries.storage.versionedJsonSerializer
import kotlinx.serialization.Serializable
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Persistent mirror of the last-known [Identity], file-backed via
 * `:libraries:storage`. Survives process death so the app can show "who
 * you are" immediately on cold start, before `/v1/me` resolves — and
 * stays usable when offline.
 *
 * Sentinel for "no identity yet" is an empty record (userId blank). The
 * cache file shape is versioned via [versionedJsonSerializer] so adding
 * fields later won't break round-trip from existing installs.
 *
 * Note: the Supabase session itself is cached by `supabase-kt` in its
 * own storage. This cache is separate — it's our app's mirror of the
 * `/v1/me` profile, NOT the JWT or session. The two work together: when
 * the app boots, supabase-kt restores the session AND we restore the
 * profile, so a feature collecting `IdentityRepository.state` sees
 * `SignedIn` immediately.
 */
@SingleIn(AppScope::class)
@Inject
class IdentityCache(
    cacheFactory: CacheFactory,
) {

    private val cache: Cache<IdentityRecord> = cacheFactory.persistent(
        name = "identity",
        serializer = versionedJsonSerializer(defaultValue = { IdentityRecord.empty() }),
    )

    suspend fun read(): Identity? {
        val record = cache.get()
        return if (record.userId.isBlank()) null
        else Identity(
            userId = record.userId,
            displayName = record.displayName,
            avatarEmoji = record.avatarEmoji,
            avatarBackgroundColor = record.avatarBackgroundColor,
            isAnonymous = record.isAnonymous,
        )
    }

    suspend fun write(identity: Identity) {
        cache.set(
            IdentityRecord(
                userId = identity.userId,
                displayName = identity.displayName,
                avatarEmoji = identity.avatarEmoji,
                avatarBackgroundColor = identity.avatarBackgroundColor,
                isAnonymous = identity.isAnonymous,
            ),
        )
    }

    suspend fun clear() {
        cache.set(IdentityRecord.empty())
    }

    @Serializable
    internal data class IdentityRecord(
        val userId: String,
        val displayName: String,
        val avatarEmoji: String,
        val avatarBackgroundColor: String? = null,
        val isAnonymous: Boolean,
    ) {
        companion object {
            fun empty() = IdentityRecord(
                userId = "",
                displayName = "",
                avatarEmoji = "",
                avatarBackgroundColor = null,
                isAnonymous = true,
            )
        }
    }
}
