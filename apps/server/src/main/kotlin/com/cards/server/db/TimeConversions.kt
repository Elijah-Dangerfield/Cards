package com.dangerfield.cards.server.db

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Bridge `kotlin.time.Instant` (domain layer) ↔ `java.time.Instant` (what
 * Exposed's `timestamp` column type accepts). Done by hand instead of via
 * kotlinx-datetime so we don't pull in another dependency just for this.
 *
 * Sub-microsecond precision is preserved; Postgres TIMESTAMPTZ truncates
 * to microseconds on its own, which is fine for our purposes.
 */
@OptIn(ExperimentalTime::class)
fun Instant.toJavaInstant(): java.time.Instant =
    java.time.Instant.ofEpochSecond(epochSeconds, nanosecondsOfSecond.toLong())

@OptIn(ExperimentalTime::class)
fun java.time.Instant.toKotlinInstant(): Instant =
    Instant.fromEpochSeconds(epochSecond, nano.toLong())
