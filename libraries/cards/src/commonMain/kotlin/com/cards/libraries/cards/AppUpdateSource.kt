package com.dangerfield.cards.libraries.cards

/**
 * Asks the platform's store what the newest installable version is.
 *
 * Deliberately not remote config. The release pipeline could publish "what we
 * shipped", but that is not the same question as "what can *this* user
 * install": a Play release rolls out to 10% first, and an App Store release is
 * phased over a week. A config-driven prompt would tell everyone to update
 * while most of them can't, which is the version of this feature that makes
 * people angry. The stores already know the right answer per user, so ask them.
 *
 * **Android** binds `PlayAppUpdateSource`, which uses Play's In-App Updates
 * API. Play reports an update only when it is actually available to that
 * install, so staged rollouts are handled for free.
 *
 * **iOS** binds `ITunesAppUpdateSource`, which reads the public iTunes lookup
 * endpoint. Apple ships no equivalent of the Play API; the lookup returns the
 * live App Store version, and phased release still allows a manual update, so
 * a prompt is never a dead end.
 *
 * Implementations must not throw. This runs on the way to a Home screen the
 * user asked for, and nothing here is worth failing that for.
 */
interface AppUpdateSource {

    /**
     * The newest version the store will currently give this user, or null when
     * that can't be determined — offline, the API failed, the store isn't
     * available (a sideload), or the check is disabled. Null always means
     * "don't prompt", never "up to date".
     */
    suspend fun latestAvailableVersion(): AppVersion?
}
