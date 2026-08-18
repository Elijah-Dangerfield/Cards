package com.dangerfield.cards.libraries.telemetry.impl

import com.dangerfield.cards.libraries.core.Catching
import me.tatarka.inject.annotations.Inject
import platform.Foundation.NSUserDefaults
import platform.Foundation.timeIntervalSince1970
import platform.MetricKit.MXMetricManager
import platform.MetricKit.MXMetricManagerSubscriberProtocol
import platform.MetricKit.MXMetricPayload
import platform.darwin.NSObject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * iOS `previous_exit` from MetricKit. There is no per-launch exit API on iOS
 * (nothing like Android's historical exit reasons), so this is built on
 * `MXAppExitMetric`: a subscriber receives day-window exit counts up to 24h
 * late, persists the classification (`NSUserDefaults`), and the next cold
 * start's `app.launched` reports it — once ([LatestExitReport]). The value is
 * therefore a day-granular sample that lags a launch, not "how the run right
 * before this one ended"; launches with no fresh report say `unknown`, which
 * includes every launch on the simulator (MetricKit never delivers there).
 *
 * Foreground exits only: background jetsam kills are routine on iOS and
 * would read as fake OOM problems next to Android's user-perceived
 * `REASON_LOW_MEMORY`.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class IosPreviousExitProvider : PreviousExitProvider {

    private val report = LatestExitReport(
        read = { NSUserDefaults.standardUserDefaults.stringForKey(REPORT_KEY) },
        write = { value ->
            val defaults = NSUserDefaults.standardUserDefaults
            when (value) {
                null -> defaults.removeObjectForKey(REPORT_KEY)
                else -> defaults.setObject(value, forKey = REPORT_KEY)
            }
        },
    )

    private val subscriber = ExitMetricSubscriber(report)

    init {
        Catching { MXMetricManager.sharedManager.addSubscriber(subscriber) }
    }

    override fun previousExit(): PreviousExit = report.consume()

    private companion object {
        const val REPORT_KEY = "telemetry.previousExitReport"
    }
}

private class ExitMetricSubscriber(
    private val report: LatestExitReport,
) : NSObject(), MXMetricManagerSubscriberProtocol {

    override fun didReceiveMetricPayloads(payloads: List<*>) {
        Catching {
            val latest = payloads
                .filterIsInstance<MXMetricPayload>()
                .filter { it.applicationExitMetrics?.foregroundExitData != null }
                .maxByOrNull { it.timeStampEnd.timeIntervalSince1970 }
                ?.applicationExitMetrics?.foregroundExitData
                ?: return@Catching
            val counts = ForegroundExitCounts(
                normal = latest.cumulativeNormalAppExitCount.toLong(),
                abnormal = latest.cumulativeAbnormalExitCount.toLong(),
                watchdog = latest.cumulativeAppWatchdogExitCount.toLong(),
                memoryLimit = latest.cumulativeMemoryResourceLimitExitCount.toLong(),
                badAccess = latest.cumulativeBadAccessExitCount.toLong(),
                illegalInstruction = latest.cumulativeIllegalInstructionExitCount.toLong(),
            )
            report.record(counts)
            logForegroundExitMetrics(counts)
        }
    }
}
