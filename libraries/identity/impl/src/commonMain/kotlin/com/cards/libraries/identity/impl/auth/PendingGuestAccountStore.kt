package com.dangerfield.cards.libraries.identity.impl.auth

import com.dangerfield.cards.libraries.identity.auth.PendingIdentity
import com.dangerfield.cards.libraries.storage.Cache
import com.dangerfield.cards.libraries.storage.CacheFactory
import com.dangerfield.cards.libraries.storage.versionedJsonSerializer
import kotlinx.serialization.Serializable
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Persists the chosen identity for a guest account whose creation hasn't
 * succeeded yet (offline at onboarding finish). Survives process death so the
 * creator can re-attempt on the next launch and finish the job with the same
 * name/avatar — instead of leaving the user a permanent local-only Fallback.
 *
 * Cleared the moment creation succeeds. A non-null [read] means "we still owe
 * this device a guest account."
 */
@SingleIn(AppScope::class)
@Inject
class PendingGuestAccountStore(
    cacheFactory: CacheFactory,
) {
    private val cache: Cache<PendingRecord> = cacheFactory.persistent(
        name = "pending_guest_account",
        serializer = versionedJsonSerializer(defaultValue = { PendingRecord.EMPTY }),
    )

    /** The held identity if a guest-account creation is still pending, else null. */
    suspend fun read(): PendingIdentity? {
        val record = cache.get()
        return if (!record.pending) null
        else PendingIdentity(
            displayName = record.displayName,
            avatarEmoji = record.avatarEmoji,
            avatarBackgroundColor = record.avatarBackgroundColor,
        )
    }

    /** Mark a guest-account creation as owed, holding [identity] for the retry. */
    suspend fun set(identity: PendingIdentity) {
        cache.set(
            PendingRecord(
                pending = true,
                displayName = identity.displayName,
                avatarEmoji = identity.avatarEmoji,
                avatarBackgroundColor = identity.avatarBackgroundColor,
            ),
        )
    }

    /** Clear the owed flag — called once creation succeeds. */
    suspend fun clear() {
        cache.set(PendingRecord.EMPTY)
    }

    @Serializable
    internal data class PendingRecord(
        val pending: Boolean = false,
        val displayName: String? = null,
        val avatarEmoji: String? = null,
        val avatarBackgroundColor: String? = null,
    ) {
        companion object {
            val EMPTY = PendingRecord()
        }
    }
}
