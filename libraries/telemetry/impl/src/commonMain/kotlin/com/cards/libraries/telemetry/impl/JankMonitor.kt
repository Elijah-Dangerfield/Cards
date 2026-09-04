package com.dangerfield.cards.libraries.telemetry.impl

/**
 * Collects how smoothly each screen actually rendered for real players, and
 * reports it as `app.jank`.
 *
 * The gap this fills: `PreviousExit.Anr` tells you a session died, and tells you
 * nothing about what was slow in the minutes before it did. Four production ANRs
 * were diagnosed by asking a user to capture a Perfetto trace on their own
 * device, which does not scale and only works for a bug someone already
 * reported. Frame data answers the question the traces answered, continuously,
 * for everyone, and it is the only way "did the RenderThread fix hold" (ENG-49)
 * gets an answer better than "the ANR count hasn't moved."
 *
 * **Aggregated, never per-frame.** A frame callback fires 60 times a second per
 * player; logging that would cost more than it tells you and would swamp the
 * pipe. Implementations tally frames against the current route and emit one
 * summary per screen visit.
 *
 * Android-only in practice. There is no equivalent frame-timing API on iOS, so
 * the iOS binding is a no-op rather than a half-measure — better an obvious
 * absence than a metric that silently means something different per platform.
 */
interface JankMonitor {

    /**
     * The player navigated. Closes out the previous screen's tally, emits it,
     * and starts counting against [route].
     *
     * Called from the same place as `Telemetry.setCurrentRoute`, so jank
     * attribution and crash attribution always name the same screen.
     */
    fun onRouteChanged(route: String)

    /**
     * The app went to background. Flushes whatever has accumulated so a session
     * that never comes back still reports its last screen — which is exactly the
     * screen worth knowing about when the process was killed.
     */
    fun onBackground()
}

/** iOS and tests. Frame timing has no cross-platform equivalent worth faking. */
class NoOpJankMonitor : JankMonitor {
    override fun onRouteChanged(route: String) = Unit
    override fun onBackground() = Unit
}
