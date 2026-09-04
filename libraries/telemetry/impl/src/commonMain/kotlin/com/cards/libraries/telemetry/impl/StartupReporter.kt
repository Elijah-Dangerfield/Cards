package com.dangerfield.cards.libraries.telemetry.impl

import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.core.logging.logEvent
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * How long the app took to become usable, measured from the moment the OS
 * created the process.
 *
 * The gap this fills: `app.launched` says a cold start happened and
 * `previous_exit` says how the last one ended, but neither says how long the
 * player stared at a splash screen. Startup is the one performance number every
 * single player pays on every single cold boot, and it is the number Play
 * Console grades the app on — so it is the one worth watching continuously
 * rather than discovering from a review.
 *
 * Measured from process creation rather than from the first line of Kotlin on
 * purpose. A large share of a cold start is spent before any of our code runs
 * (process fork, DEX/dyld loading, Application init), and a timer started in our
 * own code cannot see any of it. That is exactly the part a Baseline Profile is
 * supposed to improve, so a metric that excluded it would report "no change"
 * after the change that mattered most.
 */
interface ProcessStartTimeProvider {

    /**
     * Milliseconds since the OS created this process, or null when the platform
     * cannot say.
     *
     * Deliberately "elapsed since start" rather than an absolute timestamp: the
     * two platforms measure process start against different clocks, and
     * returning a raw instant would leak that difference into common code where
     * nothing could reconcile it.
     */
    fun elapsedSinceProcessStartMs(): Long?
}

/** Platforms with no process-start clock we trust. See [IosProcessStartTimeProvider]. */
class NoOpProcessStartTimeProvider : ProcessStartTimeProvider {
    override fun elapsedSinceProcessStartMs(): Long? = null
}

/**
 * Emits `app.startup` once per process, when the app first becomes usable.
 *
 * "Usable" is the moment the splash screen comes down — `AppViewModel.isReady`,
 * the start destination resolved — and the frame behind it has actually been
 * drawn. That is the honest definition: the player is looking at real content
 * and can act on it.
 */
@SingleIn(AppScope::class)
@Inject
class StartupReporter(
    private val processStartTimeProvider: ProcessStartTimeProvider,
) {

    private var reported = false

    /**
     * The first usable frame is on screen. Safe to call more than once — only
     * the first call reports, because the interesting quantity is per process
     * and an Activity can be recreated (rotation, theme change, config change)
     * many times inside one.
     */
    fun onAppReady() {
        if (reported) return
        reported = true

        val elapsedMs = processStartTimeProvider.elapsedSinceProcessStartMs() ?: return

        // A "startup" of several minutes is not a slow startup — it is the
        // system having started our process in the background (a broadcast, a
        // job, a content provider) long before anyone tapped the icon, and the
        // user opening the app much later. Real but unrepresentative, and left
        // in it would drag every percentile somewhere meaningless. Dropped
        // rather than clamped: a clamped value is indistinguishable from a
        // genuine slow boot at the ceiling, which is the worst of both.
        if (elapsedMs < 0 || elapsedMs > IMPLAUSIBLE_STARTUP_MS) return

        KLog.logEvent("app.startup", "startup_ms" to elapsedMs)
    }

    private companion object {
        /**
         * Above this, assume a background process start rather than a launch.
         * Play Console calls a cold start "excessive" past 5s, so 30s leaves an
         * order of magnitude of headroom for genuinely awful devices while still
         * excluding the background-start case, which lands minutes or hours out.
         */
        const val IMPLAUSIBLE_STARTUP_MS = 30_000L
    }
}
