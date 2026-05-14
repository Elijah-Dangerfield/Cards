package com.dangerfield.cards.libraries.appconfig

import kotlinx.coroutines.flow.StateFlow

/**
 * Provides the current [AppConfig] to the rest of the app.
 *
 * The state flow always has a non-null value:
 *   - Cold start with no cache: [AppConfig.Defaults]
 *   - Subsequent starts: the last cached value, replaced once a fresh fetch lands
 *
 * Callers should NOT block on [refresh]. Read [state] for whatever the latest
 * known config is and react via Compose. Refresh is fire-and-forget on the
 * service's own scope; the state flow will update when (and if) the fetch
 * succeeds.
 */
interface AppConfigService {

    val state: StateFlow<AppConfig>

    /**
     * Triggers a network fetch. Errors are swallowed and logged; the state
     * flow simply won't update on failure. Safe to call concurrently and
     * frequently — implementations may debounce.
     */
    suspend fun refresh()
}
