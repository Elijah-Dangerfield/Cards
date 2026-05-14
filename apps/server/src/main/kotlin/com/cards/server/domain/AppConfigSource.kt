package com.dangerfield.cards.server.domain

import kotlinx.serialization.json.JsonObject

/**
 * Where the app-config tree comes from. Today an in-memory hardcoded source;
 * later a Postgres-backed source. Routes depend on this interface, not on
 * the concrete implementation, so swapping the source is a one-line DI change.
 */
fun interface AppConfigSource {
    /** Returns the current config tree, keyed by ConfiguredValue.path. */
    suspend fun read(): JsonObject
}
