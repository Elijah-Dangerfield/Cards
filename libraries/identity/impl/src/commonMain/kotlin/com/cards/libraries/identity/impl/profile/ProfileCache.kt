package com.dangerfield.cards.libraries.identity.impl.profile

import com.dangerfield.cards.libraries.identity.profile.Profile
import com.dangerfield.cards.libraries.storage.Cache
import com.dangerfield.cards.libraries.storage.CacheFactory
import com.dangerfield.cards.libraries.storage.versionedJsonSerializer
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Persistent mirror of the last-known [Profile], file-backed via
 * `:libraries:storage`. Two roles:
 *
 *  1. **Cache the real `/v1/me` profile** so a failed resolve can
 *     fall back to "what we had last time we successfully fetched."
 *     Survives process death.
 *
 *  2. **Persist the fallback localId** so the offline-no-cache case
 *     produces a stable Profile.Fallback id across launches. The
 *     localId is generated once (the first time we need a Fallback)
 *     and reused thereafter — any local-only state keyed off it
 *     remains valid across kills.
 *
 * Note: the Supabase session itself is cached by `supabase-kt`. This
 * cache is separate — it's our app's mirror of the profile, not the
 * JWT.
 */
@SingleIn(AppScope::class)
@Inject
class ProfileCache(
    cacheFactory: CacheFactory,
) {

    private val cache: Cache<ProfileRecord> = cacheFactory.persistent(
        name = "profile",
        serializer = versionedJsonSerializer(defaultValue = { ProfileRecord.empty() }),
    )

    /**
     * The most recently fetched real profile, or null if we've never
     * landed one on this device. Distinct from the Fallback case.
     */
    suspend fun readAuthenticated(): Profile.Authenticated? {
        val record = cache.get()
        return if (record.userId.isBlank()) null
        else Profile.Authenticated(
            id = record.userId,
            displayName = record.displayName,
            avatarEmoji = record.avatarEmoji,
            avatarBackgroundColor = record.avatarBackgroundColor,
            email = record.email,
            isAnonymous = record.isAnonymous,
            // JSON-on-disk is Long epoch ms; domain type is Instant.
            // The serialized format stays Long for compactness +
            // forward-compat with cache records written by earlier
            // versions; conversion happens here at the boundary.
            createdAt = Instant.fromEpochMilliseconds(record.createdAtEpochMs),
        )
    }

    suspend fun writeAuthenticated(profile: Profile.Authenticated) {
        val existing = cache.get()
        cache.set(
            existing.copy(
                userId = profile.id,
                displayName = profile.displayName,
                avatarEmoji = profile.avatarEmoji,
                avatarBackgroundColor = profile.avatarBackgroundColor,
                isAnonymous = profile.isAnonymous,
                email = profile.email,
                createdAtEpochMs = profile.createdAt.toEpochMilliseconds(),
            ),
        )
    }

    /**
     * Cached fallback UUID — null if we've never needed one. The
     * [ProfileRepositoryImpl] generates one on first need and
     * persists it so subsequent Fallback emissions key off the same id.
     */
    suspend fun readLocalId(): String? = cache.get().localId

    suspend fun writeLocalId(localId: String?) {
        val existing = cache.get()
        cache.set(existing.copy(localId = localId))
    }

    /** Wipe everything — used by sign-out + "Fresh Start" debug. */
    suspend fun clear() {
        cache.set(ProfileRecord.empty())
    }

    @Serializable
    internal data class ProfileRecord(
        val userId: String,
        val displayName: String,
        val avatarEmoji: String,
        val avatarBackgroundColor: String? = null,
        val isAnonymous: Boolean,
        val email: String? = null,
        val localId: String? = null,
        // Defaulted so an existing on-disk cache from before this field
        // was added still deserializes — old records just lose precise
        // "member since" info until the next /v1/me sync rewrites the
        // record with the server-issued value.
        val createdAtEpochMs: Long = 0L,
    ) {
        companion object {
            fun empty() = ProfileRecord(
                userId = "",
                displayName = "",
                avatarEmoji = "",
                avatarBackgroundColor = null,
                isAnonymous = true,
                email = null,
                localId = null,
                createdAtEpochMs = 0L,
            )
        }
    }
}
