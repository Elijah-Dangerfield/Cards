package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppVersion
import com.dangerfield.cards.libraries.cards.LatestReleaseVersionCode
import com.dangerfield.cards.libraries.cards.LatestReleaseVersionName
import me.tatarka.inject.annotations.Inject

/**
 * Turns the store's integer answer into the version name the prompt rule needs.
 *
 * Play only ever reports an `availableVersionCode`. `release.yml` publishes the
 * (code, name) pair of the newest release to config, so the name is trusted
 * only when the store's code is exactly the published one. Any other pairing
 * means the two sources are out of step (config not yet written, a release
 * Play hasn't propagated, two releases in quick succession) and the honest
 * answer is "unknown", which the caller reads as "don't prompt".
 */
@Inject
class PublishedReleaseVersion(
    private val latestVersionCode: LatestReleaseVersionCode,
    private val latestVersionName: LatestReleaseVersionName,
) {

    fun nameFor(storeVersionCode: Int): AppVersion? {
        val published = latestVersionCode()
        if (published <= 0 || published != storeVersionCode) return null
        return AppVersion.parseOrNull(latestVersionName())
    }
}
