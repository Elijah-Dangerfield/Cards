package com.dangerfield.cards.libraries.telemetry.impl

import android.os.Process
import android.os.SystemClock
import com.dangerfield.cards.libraries.core.Catching
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Process start from the platform itself, via [Process.getStartUptimeMillis].
 *
 * This is the earliest instant any code can observe — earlier than
 * `Application.onCreate`, earlier than the first line of ours to run — which is
 * the whole point. Anything we could time ourselves would already have missed
 * process fork, DEX loading and Application init.
 *
 * Both ends are read in the `SystemClock.uptimeMillis` timebase, so the
 * subtraction is well-defined. That clock pauses in deep sleep, which is the
 * right behaviour here: a device that slept between process start and first
 * frame did not spend that time launching the app.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class AndroidProcessStartTimeProvider : ProcessStartTimeProvider {

    override fun elapsedSinceProcessStartMs(): Long? =
        Catching { SystemClock.uptimeMillis() - Process.getStartUptimeMillis() }
            .getOrNull()
}
