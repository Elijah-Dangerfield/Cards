package com.dangerfield.cards.libraries.telemetry.impl

import android.view.Window
import androidx.metrics.performance.JankStats
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.core.logging.logEvent
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * [JankMonitor] backed by AndroidX `JankStats`.
 *
 * `JankStats` reports every frame with a flag for whether it missed its
 * deadline, using the platform's own frame timing rather than a guess. This
 * class supplies the two things it deliberately leaves to the app: which screen
 * a frame belongs to, and how little of it to send.
 *
 * One `app.jank` event per screen visit, never one per frame — a frame callback
 * fires 60 times a second per player, and shipping that would cost more than it
 * could ever tell you. The arithmetic and the reporting threshold live in
 * [JankTally], which is testable without a `Window`.
 *
 * `JankStats` delivers on the main thread, so nothing here needs synchronising.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class AndroidJankMonitor : JankMonitor {

    private val tally = JankTally()
    private var jankStats: JankStats? = null
    private var route: String? = null

    /**
     * Starts listening on [window]. Call from the Activity that owns the app's
     * window. Calling again replaces the previous listener, so an Activity
     * recreation (rotation, theme change) can't leave two of them running.
     */
    fun attach(window: Window) {
        jankStats?.isTrackingEnabled = false
        jankStats = JankStats.createAndTrack(window) { frameData ->
            tally.record(frameData.isJank, frameData.frameDurationUiNanos)
        }
    }

    /** Stops listening, reporting whatever the current screen accumulated. */
    fun detach() {
        report()
        jankStats?.isTrackingEnabled = false
        jankStats = null
    }

    override fun onRouteChanged(route: String) {
        report()
        this.route = route
    }

    override fun onBackground() = report()

    private fun report() {
        val screen = route ?: run { tally.takeSummary(); return }
        val summary = tally.takeSummary() ?: return
        KLog.logEvent(
            "app.jank",
            "screen" to screen,
            "frames" to summary.frames,
            "janky_frames" to summary.jankyFrames,
            "jank_pct" to summary.jankPercent,
            "worst_frame_ms" to summary.worstFrameMs,
        )
    }
}
