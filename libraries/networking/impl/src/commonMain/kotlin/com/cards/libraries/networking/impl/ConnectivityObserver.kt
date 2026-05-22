package com.dangerfield.cards.libraries.networking.impl

import kotlinx.coroutines.flow.Flow

/**
 * Platform reachability source. Emits `true` when the device has a usable
 * network path, `false` when it doesn't. First emission lands as soon as
 * the platform delivers a path update — typically within a few hundred
 * milliseconds of subscription.
 *
 * "Usable" intentionally loose: Android's NET_CAPABILITY_VALIDATED and
 * iOS's NWPath.Status.satisfied both mean "the OS thinks this connection
 * works," not "we've completed a successful round-trip with our server."
 * That's the right granularity for an offline banner — captive portals
 * and DNS oddities would otherwise cause false negatives.
 */
interface ConnectivityObserver {
    fun observe(): Flow<Boolean>
}
