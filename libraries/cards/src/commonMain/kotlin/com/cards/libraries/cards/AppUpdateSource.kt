package com.dangerfield.cards.libraries.cards

/**
 * Asks the platform's store what the newest installable version is.
 *
 * Deliberately not remote config on its own. The release pipeline publishes
 * "what we shipped" ([LatestReleaseVersionName]), but that is not the same
 * question as "what can *this* user install". Play rolls out to 100% now, so
 * the old objection (a 10% staged rollout prompting the other 90%) is gone,
 * but a smaller gap remains: `release.yml` writes the config the moment the
 * upload is accepted, and Play then processes and sometimes reviews the build
 * for hours before it is installable. A prompt in that window points at an
 * update the store doesn't have yet, which is the version of this feature
 * that makes people angry. The stores know the right answer per user, so ask
 * them and let config supply only what they can't.
 *
 * A purely local cache can't do this job either: on device the only version
 * we ever know is the running app's own, so "is the cached version newer than
 * mine" can never be true. Some external source of truth is unavoidable.
 *
 * **Android** binds `PlayAppUpdateSource`, which uses Play's In-App Updates
 * API for availability. Play reports an update only when it is actually
 * available to that install, but it reports an `availableVersionCode`
 * integer, never a version name, and the prompt rule needs `major.minor.patch`
 * to tell a feature release from a patch. Our `versionCode` is the commit
 * count at the release tag, so `release.yml` publishes the (code, name) pair
 * to config and the source uses the name only when the codes match. A stale
 * or racing config therefore degrades to "no prompt", never to a wrong label.
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
