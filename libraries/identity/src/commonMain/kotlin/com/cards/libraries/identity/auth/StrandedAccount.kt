package com.dangerfield.cards.libraries.identity.auth

import kotlinx.serialization.Serializable

/**
 * A real account this device still belongs to, whose session it has lost.
 *
 * Written the moment `GuestSessionHealer` declares a session unrecoverable, and
 * cleared when the user either recovers the account or deliberately walks away
 * from it.
 *
 * **Why this has to be durable.** Declaring the session unrecoverable is also
 * what destroys the evidence that the account exists: the auth state moves to
 * `SessionExpired`, and `ProfileRepositoryImpl` clears the cached profile on
 * exactly that reason, because a cached profile with no session is a ghost that
 * makes the app keep firing calls that all 401. Reasonable on its own — but the
 * cached profile is the *only* thing `GuestSessionHealer.cachedServerProfile()`
 * reads to decide "a real account lives here, do not mint over it".
 *
 * So the protection lasted exactly one process. On the next launch there was no
 * session, no cached profile, and `hasUserOnboarded` still true, which walks
 * straight into `mint()` — a second account, silently, with no screen and no
 * warning. Quieter than the path that was actually reported on 2026-10-08,
 * where the user at least saw a sign-out first (AUTH-34).
 *
 * This record outlives the process so the healer can keep refusing, and so the
 * recovery UI can name what it is offering to restore.
 *
 * **Holds no tokens.** An id, a name, and enough to render a sentence. The
 * question of persisting a refresh token for a claimed account is a different
 * one, answered deliberately in the other direction — see `SessionMirrorStore`
 * and decisions.md 2026-07-11.
 */
@Serializable
data class StrandedAccount(
    val userId: String,
    val displayName: String,
    /** Present for a claimed account; the hint the recovery screen shows. Null for anonymous. */
    val email: String? = null,
    /**
     * Anonymous accounts have no credential to sign back in with, so recovery
     * copy that leads with "sign in" is a dead end for them. Branch on this.
     */
    val isAnonymous: Boolean = false,
    val strandedAtEpochMs: Long = 0L,
)

/**
 * Durable store for [StrandedAccount]. Device-scoped: it must survive the
 * account wipe that follows the session loss, and process death.
 */
interface StrandedAccountStore {
    suspend fun read(): StrandedAccount?
    suspend fun write(account: StrandedAccount)
    suspend fun clear()
}
