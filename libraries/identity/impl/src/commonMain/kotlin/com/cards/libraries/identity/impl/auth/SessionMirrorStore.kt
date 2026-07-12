package com.dangerfield.cards.libraries.identity.impl.auth

import com.dangerfield.cards.libraries.storage.Cache
import com.dangerfield.cards.libraries.storage.CacheFactory
import com.dangerfield.cards.libraries.storage.versionedJsonSerializer
import kotlinx.serialization.Serializable
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Last-resort durable copy of an **anonymous** session, keyed by the same
 * per-project storage key as the OS-encrypted store (AUTH-19).
 *
 * The owner's TestFlight upgrade proved the Keychain copy of the session can
 * vanish across an app update while the rest of the app's files survive — which
 * for an anonymous account (no credential to sign back in with) permanently
 * strands every chip and XP on the server. This mirror lives in the app's
 * ordinary file-backed cache, which demonstrably survives upgrades, and is only
 * consulted when the secure store comes up empty.
 *
 * Deliberate security trade (see decisions.md 2026-07-11): the mirror holds the
 * session JSON (refresh token included) in the app sandbox without OS-keystore
 * encryption, but **only for anonymous sessions** — an anonymous account's sole
 * credential *is* device possession, so the mirror adds no attack surface an
 * app-sandbox compromise didn't already have. The moment the account is claimed
 * (a real credential exists to recover with), the mirror is cleared and the
 * session is Keychain/EncryptedSharedPreferences-only again.
 */
interface SessionMirror {
    suspend fun read(key: String): String?
    suspend fun write(key: String, sessionJson: String)
    suspend fun clear()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class SessionMirrorStore(
    cacheFactory: CacheFactory,
) : SessionMirror {

    private val cache: Cache<MirrorRecord> = cacheFactory.persistent(
        name = "supabase_session_mirror",
        serializer = versionedJsonSerializer(defaultValue = { MirrorRecord.EMPTY }),
    )

    override suspend fun read(key: String): String? =
        cache.get().takeIf { it.key == key }?.sessionJson

    override suspend fun write(key: String, sessionJson: String) {
        cache.set(MirrorRecord(key = key, sessionJson = sessionJson))
    }

    override suspend fun clear() {
        cache.set(MirrorRecord.EMPTY)
    }

    @Serializable
    internal data class MirrorRecord(
        val key: String? = null,
        val sessionJson: String? = null,
    ) {
        companion object {
            val EMPTY = MirrorRecord()
        }
    }
}
