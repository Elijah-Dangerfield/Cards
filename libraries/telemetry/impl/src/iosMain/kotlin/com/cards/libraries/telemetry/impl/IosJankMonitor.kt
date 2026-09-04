package com.dangerfield.cards.libraries.telemetry.impl

import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * iOS has no equivalent of `JankStats`: UIKit exposes no per-frame timing with a
 * "did this frame miss its deadline" signal, and reconstructing one from
 * `CADisplayLink` would measure something different enough that comparing the
 * two platforms on one dashboard would mislead rather than inform.
 *
 * So iOS reports no jank at all, deliberately. iOS smoothness regressions are
 * caught through MetricKit's hang reports, which already land as
 * [PreviousExit.Anr]. If `app.jank` ever needs an iOS number, it should arrive
 * as its own metric with its own name, not as this one quietly meaning
 * something else.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class IosJankMonitor : JankMonitor by NoOpJankMonitor()
