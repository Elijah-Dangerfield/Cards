package com.dangerfield.cards.libraries.config

import kotlinx.coroutines.flow.Flow

/**
 * Repository responsible for storing and streaming QA/debug config overrides.
 */
interface ConfigOverrideRepository {
    /** Returns the currently persisted overrides, typically from disk-backed storage. */
    fun getOverrides(): List<ConfigOverride<Any>>

    /** Emits override changes so config can be re-merged without restarting the app. */
    fun getOverridesFlow(): Flow<List<ConfigOverride<Any>>>

    /** Persists or updates a single override value for the given path. */
    suspend fun addOverride(override: ConfigOverride<Any>)

    /** Wipes every override. Useful from the QA menu or a debug recovery affordance. */
    suspend fun clearAll()
}
