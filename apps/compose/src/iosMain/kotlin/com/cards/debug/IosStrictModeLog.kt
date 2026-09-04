package com.dangerfield.cards.debug

import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * StrictMode is an Android construct — there is no iOS equivalent that flags
 * main-thread I/O at the point it happens. iOS main-thread stalls surface
 * instead through MetricKit's hang reports, which already arrive as
 * [com.dangerfield.cards.libraries.telemetry.impl.PreviousExit.Anr].
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class IosStrictModeLog : StrictModeLog by NoOpStrictModeLog()
