package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppUpdateSource
import com.dangerfield.cards.libraries.cards.AppVersion
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Placeholder [AppUpdateSource] that never reports an update, so the prompt is
 * wired end to end but silent until a real source replaces it.
 *
 * **Why there isn't a real one yet.** The two stores answer different questions:
 *
 * - **Play's In-App Updates API** answers *"is an update available to this
 *   install?"* correctly, including staged rollouts, but it reports an
 *   `availableVersionCode` — an integer — and never a version *name*. The
 *   prompt rule needs `major.minor.patch` to tell a feature release from a
 *   patch, and a version code cannot be mapped back to one on device.
 * - **Apple** ships no equivalent API at all. The public iTunes lookup endpoint
 *   does return a real version string, so iOS could be implemented today.
 *
 * The shape that satisfies both is Play for *availability* plus our own
 * `/v1/config/manifest` (which `release.yml` already publishes at release time)
 * for the *version name*. That is a server change, so it is a todo rather than
 * something smuggled in here — see ENG-52.
 *
 * Binding a no-op rather than leaving the graph unsatisfied is deliberate: the
 * rule, the arbiter wiring and the sheet are all testable now, and the day a
 * real source lands it is a one-line binding swap with no call-site changes.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class NoUpdateSource : AppUpdateSource {
    override suspend fun latestAvailableVersion(): AppVersion? = null
}
