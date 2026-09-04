package com.dangerfield.cards.libraries.telemetry.impl

import kotlin.math.roundToInt

/**
 * What one screen visit's worth of frames adds up to.
 *
 * [worstFrameMs] is the field to reach for when chasing an ANR. A screen at 2%
 * jank whose worst frame took 900ms has a stall in it; the percentage alone
 * would call that screen healthy.
 */
data class JankSummary(
    val frames: Int,
    val jankyFrames: Int,
    val jankPercent: Int,
    val worstFrameMs: Long,
)

/**
 * Accumulates frame timings for the screen currently on show.
 *
 * Split out from [AndroidJankMonitor] so the arithmetic and the reporting
 * threshold can be tested without a `Window`, a `Looper` or a real frame. The
 * Android class keeps the parts that genuinely need Android: subscribing to
 * `JankStats` and knowing when a route changed.
 *
 * Not thread-safe on purpose. `JankStats` delivers on the main thread and
 * [record] runs on every frame, so a lock here would be a cost paid 60 times a
 * second to guard four fields that only one thread ever touches.
 */
class JankTally(private val minFramesToReport: Int = MIN_FRAMES_TO_REPORT) {

    private var frames = 0
    private var jankyFrames = 0
    private var worstFrameNanos = 0L

    fun record(isJank: Boolean, frameDurationNanos: Long) {
        frames += 1
        if (!isJank) return
        jankyFrames += 1
        if (frameDurationNanos > worstFrameNanos) worstFrameNanos = frameDurationNanos
    }

    /**
     * Returns the visit's summary and resets, or returns null and resets when
     * too few frames went by to mean anything.
     *
     * The threshold is the whole reason this returns null rather than a zeroed
     * summary: a screen passed through in three frames can report 33% jank and
     * say nothing at all. Dropping those at the source keeps every panel built
     * on this metric readable without each one having to re-derive a floor.
     */
    fun takeSummary(): JankSummary? {
        val summary = if (frames < minFramesToReport) {
            null
        } else {
            JankSummary(
                frames = frames,
                jankyFrames = jankyFrames,
                jankPercent = (jankyFrames * PERCENT / frames.toDouble()).roundToInt(),
                worstFrameMs = worstFrameNanos / NANOS_PER_MS,
            )
        }
        frames = 0
        jankyFrames = 0
        worstFrameNanos = 0L
        return summary
    }

    companion object {
        /**
         * Roughly two seconds at 60fps. Long enough for the ratio to mean
         * something, short enough that a brief visit containing a real stall
         * still gets reported.
         */
        const val MIN_FRAMES_TO_REPORT = 120

        private const val PERCENT = 100.0
        private const val NANOS_PER_MS = 1_000_000L
    }
}
