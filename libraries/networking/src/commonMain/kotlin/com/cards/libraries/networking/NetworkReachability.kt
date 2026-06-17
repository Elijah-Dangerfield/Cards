package com.dangerfield.cards.libraries.networking

import kotlinx.coroutines.flow.StateFlow

/**
 * "Are we actually completing round-trips with our server?" — distinct from the
 * OS connectivity flag, which only says the device *thinks* it has a path.
 *
 * The HTTP client reports the outcome of every request:
 *  - [reportReachable] — a response came back (any HTTP status, even 4xx/5xx);
 *    the round-trip worked, so the network is usable.
 *  - [reportUnreachable] — the request failed *without* a response (timeout, DNS,
 *    connection refused, captive portal swallowing it); the round-trip didn't
 *    complete.
 *
 * [isReachable] flips false only after a few consecutive failures (a single
 * flaky request shouldn't drop the banner) and recovers on the next success.
 * `AppState.isOffline` combines this with the OS observer.
 */
interface NetworkReachability {

    /** True while round-trips are succeeding (or haven't failed enough to matter). */
    val isReachable: StateFlow<Boolean>

    /** A request reached the server (got any HTTP response). Resets the failure run. */
    fun reportReachable()

    /** A request failed to reach the server (no response — timeout / IO / connect). */
    fun reportUnreachable()
}
