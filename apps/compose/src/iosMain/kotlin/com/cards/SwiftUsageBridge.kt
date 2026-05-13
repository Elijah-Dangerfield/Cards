package com.dangerfield.cards

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val NANOS_IN_SECOND = 1_000_000_000.0

/**
 * Helper for Swift callers that still need to construct kotlin.time.Duration values.
 */
fun durationFromSeconds(seconds: Double): Duration = seconds.seconds

/** Converts a Kotlin [Duration] to seconds so Swift can safely consume it. */
fun durationInSeconds(duration: Duration): Double = duration.inWholeNanoseconds.toDouble() / NANOS_IN_SECOND

/** Nullable-friendly wrapper used when Swift callers read optional durations. */
fun durationInSecondsOrNull(duration: Duration?): Double? = duration?.let(::durationInSeconds)
