package com.dangerfield.cards.libraries.networking

import kotlinx.coroutines.flow.Flow

/**
 * One-way signal from the network layer to the app layer: the server returned a
 * **`403` with the locked [Denial] envelope** — the caller is blocked
 * (banned/suspended) and any further calls will keep failing the same way.
 *
 * Deliberately a bus, not a direct call into the auth / app layers, so the
 * networking module stays narrow (it depends on nothing app-shaped) and the
 * app layer chooses how to react — today: route to a blocking access-denied
 * screen with localized copy keyed off [Denial.reason]. Sibling shape to
 * [SessionRejectionBus] so the two server-confirmed-rejection paths (401-after-refresh
 * and 403-with-envelope) are mechanically parallel.
 *
 * This is **not** the path for an *un-enveloped* 403 (a route's own permission
 * check, etc.) — only a 403 carrying the wire contract flows through here.
 */
interface AccessDeniedBus {

    /**
     * What the server told us. Mirrors the server's `AccessDeniedResponse`
     * wire contract one-to-one — copy is not on the wire, only machine-readable
     * data the client localizes.
     */
    data class Denial(
        /** Machine-readable kind, e.g. `"banned"` / `"suspended"`. */
        val reason: String,
        /** ISO-8601 timestamp when the block lifts; null = indefinite. */
        val until: String?,
        /** Support page URL; null when the server has none configured. */
        val appealUrl: String?,
    )

    /** Fire-and-forget — called from the response-validation path on a 403. */
    fun signalDenied(denial: Denial)

    /** Observed by the app layer to route to the blocking access-denied screen. */
    val denials: Flow<Denial>
}
