package com.dangerfield.cards.libraries.products

import kotlin.time.TimeSource

/**
 * Pairs the server's wall-clock time at catalog fetch with a local
 * **monotonic** time mark so countdowns survive device wall-clock
 * manipulation.
 *
 * The classic naive countdown does:
 *
 * ```
 * remaining = availableUntilEpochMs - Clock.System.now().toEpochMilliseconds()
 * ```
 *
 * That breaks the moment the user spins their phone's clock back —
 * a Halloween offer that ended yesterday suddenly has 364 days left.
 *
 * This class fixes it by ALWAYS computing "effective server time" as:
 *
 * ```
 * effectiveServerNow = serverNowAtFetch + monotonicElapsedSinceFetch
 * ```
 *
 * Monotonic time (`TimeSource.Monotonic`) is the OS's tick counter — it
 * only moves forward, can't be set by the user, and isn't affected by
 * timezone changes, daylight savings, or "set date" toggles. The
 * countdown advances exactly as much real time as the user actually
 * experienced since the catalog was fetched, regardless of what the
 * device wall clock says.
 *
 * Server-side validation is the ultimate authority — if a request
 * somehow makes it to the server past the window, the route rejects
 * it. This client class exists for the UX layer (countdown badges,
 * preemptive "this just expired" handling), not for security.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
class CatalogTimeAnchor private constructor(
    val serverNowEpochMs: Long,
    private val fetchedAt: TimeSource.Monotonic.ValueTimeMark,
) {

    /**
     * Effective server wall time *right now*, as ms since epoch.
     * Computed by adding the monotonic time elapsed since fetch to the
     * server's clock snapshot.
     *
     * If the user changes their device wall clock to the year 2099,
     * this still returns the correct "real" time because monotonic
     * elapsed is unaffected.
     */
    fun effectiveServerNowMs(): Long =
        serverNowEpochMs + fetchedAt.elapsedNow().inWholeMilliseconds

    /**
     * Remaining milliseconds until [availableUntilEpochMs], or 0 if the
     * offer has already expired. Null if [availableUntilEpochMs] is
     * null (no expiry).
     */
    fun remainingMsUntil(availableUntilEpochMs: Long?): Long? {
        if (availableUntilEpochMs == null) return null
        return (availableUntilEpochMs - effectiveServerNowMs()).coerceAtLeast(0L)
    }

    companion object {
        /**
         * Captures a fresh anchor. Call this exactly once per network
         * response (NOT per catalog observation) so the monotonic mark
         * tracks the fetch, not subsequent reads.
         */
        fun capture(serverNowEpochMs: Long): CatalogTimeAnchor =
            CatalogTimeAnchor(serverNowEpochMs, TimeSource.Monotonic.markNow())
    }
}
