package com.dangerfield.cards.libraries.telemetry.impl

import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * iOS has no cheap equivalent of Android's historical exit reasons: the
 * honest answer needs a MetricKit `MXAppExitMetric` subscriber plus
 * persistence across launches, which is its own project (tracked under
 * ENG-25 in `docs/todo.md`). Until that lands we report Unknown rather
 * than fake a value — dashboards segmenting `previous_exit` should treat
 * iOS as all-unknown.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class IosPreviousExitProvider : PreviousExitProvider {
    override fun previousExit(): PreviousExit = PreviousExit.Unknown
}
