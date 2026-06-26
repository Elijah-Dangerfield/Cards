package com.dangerfield.cards.server.domain

import com.dangerfield.cards.server.http.ClientContext
import kotlinx.serialization.json.JsonObject

/**
 * Where the app-config tree comes from. Today a Postgres-backed source
 * (`PostgresAppConfigSource`); routes depend on this interface, not the
 * concrete implementation, so swapping the source is a one-line DI change.
 *
 * Reads are scoped to the requesting client via [ClientContext] and, when the
 * call presents a Supabase JWT, the resolved [userId]. That's how per-flag
 * targeting and staged rollouts resolve server-side without the client model
 * ever changing: the endpoint still returns a *resolved* override tree keyed by
 * `ConfiguredValue.path`, the client just merges it over its defaults. [userId]
 * is null for the unauthenticated first-launch fetch — targeting then falls
 * back to the client-context axes (platform / app version / country / locale)
 * and install-id rollout bucketing.
 */
fun interface AppConfigSource {
    /** Returns the resolved config tree for this caller, keyed by ConfiguredValue.path. */
    suspend fun read(context: ClientContext, userId: UserId?): JsonObject
}
