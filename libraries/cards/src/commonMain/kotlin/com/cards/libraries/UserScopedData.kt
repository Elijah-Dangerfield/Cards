package com.dangerfield.cards.libraries.cards

/**
 * A device-local store whose contents belong to a single signed-in user —
 * Room tables (chips, XP, inventory, …), the profile mirror, account-scoped
 * settings. Implementations wipe the **departing** user's data whenever the
 * active user changes: an account switch (anon → existing account) or a
 * sign-out / delete.
 *
 * Contributed as an [AppScope][software.amazon.lastmile.kotlin.inject.anvil.AppScope]
 * multibinding; [UserScopedDataReset] collects the whole set and runs them
 * as one "dump." Adding a new store is a compile-time wire-up (contribute a
 * binding), not a list edit.
 *
 * ## Ordering contract
 *
 * [clear] runs *before* the auth layer emits the new [AuthState] (see
 * `SupabaseAuthRepositoryImpl.emitLocked`). That ordering is the whole point:
 * the incoming user's reactive loaders (e.g. `ProfileRepository` re-resolving
 * on the auth change) only wake on the new emission, so they cannot race the
 * wipe and lose freshly-fetched data.
 *
 * Keep implementations self-contained: a local wipe only. **No network** — we
 * may already hold the new user's session, so any call would either 401 or
 * worse, fetch the new user's data into a store we're about to clear. **No
 * calls back into the auth layer** — clears run inside an auth transition.
 */
interface UserScopedClearer {
    /** Wipe [previousUserId]'s device-local data. Idempotent; must not throw fatally. */
    suspend fun clear(previousUserId: String)
}

/**
 * Runs every [UserScopedClearer] for a departing user. The auth layer invokes
 * this at the single point where it knows the active user id changed, so no
 * individual sign-in / switch / sign-out / delete path has to remember to
 * trigger cleanup — the old "every caller dispatches `SignedOut`" footgun.
 *
 * Failures in one clearer are logged and swallowed so a single bad store can't
 * block the rest of the dump (or the auth transition that awaits it).
 */
interface UserScopedDataReset {
    suspend fun clearFor(previousUserId: String)
}
