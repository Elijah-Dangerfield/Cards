package com.dangerfield.cards.libraries.identity.impl.auth

import com.dangerfield.cards.libraries.identity.auth.StrandedAccount
import com.dangerfield.cards.libraries.identity.auth.StrandedAccountStore
import com.dangerfield.cards.libraries.storage.Cache
import com.dangerfield.cards.libraries.storage.CacheFactory
import com.dangerfield.cards.libraries.storage.versionedJsonSerializer
import kotlinx.serialization.Serializable
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * File-backed [StrandedAccountStore], in the app's ordinary cache directory —
 * the same storage `SessionMirrorStore` relies on precisely because it
 * demonstrably survives app upgrades, which is the event that strands an
 * account in the first place.
 *
 * Deliberately *not* in `AppData`: that is wiped per-account, and this record's
 * entire job is to outlive the account wipe that follows a session loss.
 *
 * Unlike the session mirror this holds no credential, so it carries none of
 * that store's security trade — an id, a name, and an email hint to render.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class PersistentStrandedAccountStore(
    cacheFactory: CacheFactory,
) : StrandedAccountStore {

    private val cache: Cache<StrandedRecord> = cacheFactory.persistent(
        name = "stranded_account",
        serializer = versionedJsonSerializer(defaultValue = { StrandedRecord.EMPTY }),
    )

    override suspend fun read(): StrandedAccount? = cache.get().toAccountOrNull()

    override suspend fun write(account: StrandedAccount) {
        cache.set(
            StrandedRecord(
                userId = account.userId,
                displayName = account.displayName,
                email = account.email,
                isAnonymous = account.isAnonymous,
                strandedAtEpochMs = account.strandedAtEpochMs,
            ),
        )
    }

    override suspend fun clear() {
        cache.set(StrandedRecord.EMPTY)
    }

    /**
     * Every field defaulted so a record written by an older build stays
     * readable: this is the one piece of state whose whole purpose is to be
     * read by a *later* version of the app than the one that wrote it.
     */
    @Serializable
    internal data class StrandedRecord(
        val userId: String? = null,
        val displayName: String? = null,
        val email: String? = null,
        val isAnonymous: Boolean = false,
        val strandedAtEpochMs: Long = 0L,
    ) {
        fun toAccountOrNull(): StrandedAccount? = StrandedAccount(
            userId = userId ?: return null,
            // A record missing its name is still worth honoring — the healer's
            // refusal matters more than the copy, and the UI can fall back.
            displayName = displayName.orEmpty(),
            email = email,
            isAnonymous = isAnonymous,
            strandedAtEpochMs = strandedAtEpochMs,
        )

        companion object {
            val EMPTY = StrandedRecord()
        }
    }
}
