package com.dangerfield.cards.libraries.telemetry.impl

import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * iOS reports no startup time, deliberately.
 *
 * Foundation has no equivalent of `Process.getStartUptimeMillis`. The only way
 * to read this process's start instant is `sysctl(KERN_PROC)`, and that is a bad
 * trade here on two counts: Kotlin/Native's `platform.posix` does not expose
 * `kinfo_proc` for Apple targets, so it would need a hand-written cinterop def;
 * and `sysctl` process/boot-time lookups are on Apple's required-reason API
 * list, so shipping one obliges us to declare it in the privacy manifest and
 * defend it in review. That is a lot of surface for one number.
 *
 * The measurable alternative is worse than nothing: a timer started at the first
 * line of our own Kotlin would miss dyld, framework loading and static
 * initialisers, which on iOS is a large share of a cold start and precisely the
 * part worth watching. Charting that next to Android's real process-start number
 * under one name would make iOS look faster than it is.
 *
 * The right source is MetricKit's `MXAppLaunchMetric.histogrammedTimeToFirstDraw`
 * — Apple's own measurement, taken from process creation, with no required-reason
 * declaration. We already ingest MetricKit payloads for exit reasons (see
 * [MetricKitExitReport]), so that is the seam to extend. It should arrive under
 * its own event name rather than as `app.startup`, because it is a histogram over
 * a day rather than a single launch.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class IosProcessStartTimeProvider :
    ProcessStartTimeProvider by NoOpProcessStartTimeProvider()
