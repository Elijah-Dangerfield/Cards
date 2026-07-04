package com.dangerfield.cards.libraries.identity.impl.profile

import com.dangerfield.cards.libraries.cards.SessionTracker
import com.dangerfield.cards.libraries.identity.profile.AvatarPack
import com.dangerfield.cards.libraries.identity.profile.AvatarPackOutcome
import com.dangerfield.cards.libraries.storage.Cache
import com.dangerfield.cards.libraries.storage.CacheFactory
import com.dangerfield.cards.libraries.storage.versionedJsonSerializer
import kotlinx.serialization.Serializable
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Persistent snapshot of the last `/v1/avatars` response plus the
 * metadata the session-aware fetcher needs to decide when to refetch.
 *
 * Lifecycle:
 *  - Read on first [ProfileRepositoryImpl.fetchAvatarPack]
 *    call (lazy hydrate). The repo holds the result in memory after,
 *    so subsequent same-session calls don't touch disk again.
 *  - Written after every successful network fetch, paired with the
 *    current [SessionTracker] id and a wall-clock fetch timestamp so
 *    future cold-starts can short-circuit when nothing has rolled the
 *    session yet.
 *  - Cleared only when the persisted snapshot is older than the
 *    max age at read time. There is deliberately no sign-out clear:
 *    the catalog is user-agnostic, so a snapshot written under one
 *    account is just as correct for the next.
 *
 * The cache stores a single row keyed on the file name; we don't
 * shard by user id because the avatar catalog is the same for every
 * user. Switching accounts on the same device is rare and the cost
 * of a one-time refresh after a sign-in change is negligible.
 */
@SingleIn(AppScope::class)
@Inject
class AvatarPackCache(
    cacheFactory: CacheFactory,
) {

    private val cache: Cache<CachedAvatarPack> = cacheFactory.persistent(
        name = "avatars",
        serializer = versionedJsonSerializer(defaultValue = { CachedAvatarPack.EMPTY }),
        loadEagerly = true,
    )

    internal suspend fun read(): CachedAvatarPack? {
        val record = cache.get()
        return if (record.packs.isEmpty() && record.lastFetchSessionId == null) null
        else record
    }

    suspend fun write(
        outcome: AvatarPackOutcome.Success,
        sessionId: Long,
        fetchedAtEpochMs: Long,
    ) {
        cache.set(
            CachedAvatarPack(
                packs = outcome.packs.map { CachedAvatarPack.PackRecord.from(it) },
                palette = outcome.palette,
                lastFetchSessionId = sessionId,
                fetchedAtEpochMs = fetchedAtEpochMs,
            ),
        )
    }

    suspend fun clear() {
        cache.set(CachedAvatarPack.EMPTY)
    }
}

/**
 * On-disk shape. The `PackRecord` mirrors [AvatarPack] but stays in
 * this file so the serialized format isn't coupled to whatever shape
 * the public api type happens to have in the future — adding a new
 * `unlockProductId`-shaped column to the public type wouldn't break
 * snapshots written by old builds.
 */
@Serializable
internal data class CachedAvatarPack(
    val packs: List<PackRecord>,
    val palette: List<String>,
    val lastFetchSessionId: Long?,
    val fetchedAtEpochMs: Long,
) {
    @Serializable
    internal data class PackRecord(
        val id: String,
        val name: String,
        val emojis: List<String>,
        val unlockProductId: String? = null,
    ) {
        fun toDomain(): AvatarPack = AvatarPack(
            id = id,
            name = name,
            emojis = emojis,
            unlockProductId = unlockProductId,
        )

        companion object {
            fun from(pack: AvatarPack): PackRecord = PackRecord(
                id = pack.id,
                name = pack.name,
                emojis = pack.emojis,
                unlockProductId = pack.unlockProductId,
            )
        }
    }

    fun toSuccess(): AvatarPackOutcome.Success = AvatarPackOutcome.Success(
        packs = packs.map { it.toDomain() },
        palette = palette,
    )

    companion object {
        val EMPTY: CachedAvatarPack = CachedAvatarPack(
            packs = emptyList(),
            palette = emptyList(),
            lastFetchSessionId = null,
            fetchedAtEpochMs = 0L,
        )
    }
}
