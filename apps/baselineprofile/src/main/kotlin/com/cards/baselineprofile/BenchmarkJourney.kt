package com.dangerfield.cards.baselineprofile

import android.content.Intent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

/**
 * The journey the profile generators and the R8 smoke test all drive.
 *
 * Shared because the alternative is three copies of the same UiAutomator taps
 * drifting apart, and the first symptom of that drift is a profile that quietly
 * covers less while every assertion still passes.
 */
object BenchmarkJourney {

    const val PACKAGE = "com.dangerfield.cards"

    // Mirrors BenchmarkHooks. Duplicated rather than shared because this module
    // must not depend on the app's code — it drives the installed binary, and a
    // compile dependency would be a lie about that boundary.
    const val EXTRA_SKIP_ONBOARDING = "cards.benchmark.skipOnboarding"
    const val EXTRA_EMAIL = "cards.benchmark.email"
    const val EXTRA_PASSWORD = "cards.benchmark.password"

    /**
     * Launch intent that asks the app to start on Home.
     *
     * Passing the reserved account matters more than it looks. Without a
     * session, `GuestSessionHealer` mints a fresh anonymous account on every
     * cold boot — and it does so *because* the hook set `hasUserOnboarded`. Each
     * mint then activates seven user-scoped syncers. Across iterations that is
     * what produced the 429s in dev, not the journey itself.
     */
    fun launchIntent(): Intent = Intent().apply {
        // By class name, not MAIN/LAUNCHER: once benchmark flags and extras are
        // attached, category-based intent resolution fails outright.
        setClassName(PACKAGE, "$PACKAGE.MainActivity")
        putExtra(EXTRA_SKIP_ONBOARDING, true)
        val args = InstrumentationRegistry.getArguments()
        args.getString(EXTRA_EMAIL)?.let { putExtra(EXTRA_EMAIL, it) }
        args.getString(EXTRA_PASSWORD)?.let { putExtra(EXTRA_PASSWORD, it) }
    }

    /**
     * Walks whatever onboarding is on screen until Home appears.
     *
     * Adaptive rather than a fixed tap sequence, and that is a correctness
     * choice. Onboarding is four steps today, its grant screen has separate
     * offline copy, and the hook usually skips it entirely — a hardcoded
     * sequence breaks on all three.
     */
    fun MacrobenchmarkScope.reachHome() {
        val anyOnboardingCta = By.text(
            Pattern.compile("$CONTINUE_AS_GUEST|$CONTINUE|$CLAIM_AND_PLAY|$TAKE_A_SEAT"),
        )
        repeat(ONBOARDING_MAX_STEPS) {
            if (device.hasObject(By.text(PRACTICE))) return
            device.tapMatching(anyOnboardingCta, ONBOARDING_STEP_TIMEOUT_MS)
        }
        check(device.wait(Until.hasObject(By.text(PRACTICE)), FIRST_SCREEN_TIMEOUT_MS) == true) {
            "Never reached Home.\n" + device.describeScreen()
        }
    }

    /**
     * Home into a bots table, then a few hands.
     *
     * Actions are taken as offered rather than scripted: whether the table wants
     * a check or a call depends on the deal, and a profile only records that the
     * action path executed.
     */
    fun MacrobenchmarkScope.playAPracticeTable() {
        device.tapRequired(PRACTICE)
        // Practice opens a table-setup sheet rather than dealing straight away.
        device.tapRequired(START)

        check(device.wait(Until.hasObject(By.textContains(FOLD)), TABLE_TIMEOUT_MS) == true) {
            "Reached table setup but never dealt.\n" + device.describeScreen()
        }

        // One selector for "whatever the table offers", not a chain. A chain
        // costs its full timeout per miss, and misses are the normal case while
        // bots think — that is the difference between two minutes and thirty.
        val anyAction = By.text(Pattern.compile("$CHECK|$CALL.*|$NEXT_HAND|$CONTINUE"))
        repeat(ACTIONS_TO_TAKE) {
            if (!device.tapMatching(anyAction, ACTION_TIMEOUT_MS)) device.waitForIdle()
        }
    }

    // Verbatim from libraries/resources/.../strings.xml. If one changes, these
    // tests are where it surfaces, which is the point.
    const val CONTINUE_AS_GUEST = "Continue as guest"
    const val CONTINUE = "Continue"
    const val CLAIM_AND_PLAY = "Claim & play"
    const val TAKE_A_SEAT = "Take a seat"
    const val PRACTICE = "Practice"
    const val START = "Start"
    const val CHECK = "Check"
    const val CALL = "Call"
    const val FOLD = "Fold"
    const val NEXT_HAND = "Next hand"

    const val FIRST_SCREEN_TIMEOUT_MS = 20_000L
    const val TABLE_TIMEOUT_MS = 30_000L
    const val ONBOARDING_STEP_TIMEOUT_MS = 15_000L
    const val ONBOARDING_MAX_STEPS = 8

    /** Short: a miss is the normal case, and this is paid on every idle pass. */
    const val ACTION_TIMEOUT_MS = 800L
    const val ACTIONS_TO_TAKE = 14

    private const val TAP_ATTEMPTS = 3
    private const val RETRY_FIND_MS = 500L
    private const val MAX_REPORTED_LINES = 25

    /** Taps an exact-text element, reporting whether it was there. */
    fun UiDevice.tap(text: String, timeoutMs: Long): Boolean =
        tapMatching(By.text(text), timeoutMs)

    /**
     * Finds and taps, re-finding if the node goes stale in between.
     *
     * Every transition in this app is animated, and UiAutomator hands back a
     * handle to a node Compose may replace before the click lands — which throws
     * rather than missing. Only the first look pays the caller's timeout; a
     * retry means the node was there a frame ago, and re-paying the full wait on
     * every retry is what once turned a three-minute run into twenty-five.
     */
    fun UiDevice.tapMatching(selector: BySelector, timeoutMs: Long): Boolean {
        repeat(TAP_ATTEMPTS) { attempt ->
            waitForIdle()
            val budget = if (attempt == 0) timeoutMs else RETRY_FIND_MS
            val found = wait(Until.findObject(selector), budget) ?: return false
            try {
                found.click()
                waitForIdle()
                return true
            } catch (_: StaleObjectException) {
                // Recomposed between find and click. Go round again.
            }
        }
        return false
    }

    /** Taps something that must be there, failing the run loudly if it is not. */
    fun UiDevice.tapRequired(text: String, timeoutMs: Long = 15_000L) {
        check(tap(text, timeoutMs)) {
            "Journey stalled: no \"$text\" after ${timeoutMs}ms.\n" + describeScreen()
        }
    }

    /**
     * Everything readable on screen when an assertion failed.
     *
     * Without it a failure says only "the thing I wanted was not there", which
     * cannot separate an R8 breakage from an error dialog, a spinner or a step
     * nobody knew existed. Those need different fixes, and guessing between them
     * costs a full emulator run each time.
     */
    fun UiDevice.describeScreen(): String {
        // `By.textContains("")` matches nothing, which is why this reported an
        // empty screen on every failure and told us less than no diagnostic at
        // all — it looked like evidence of a blank window. `.+` is the selector
        // that actually matches any non-empty text.
        val visible = findObjects(By.text(Pattern.compile(".+")))
            .mapNotNull { runCatchingStale { it.text?.trim() } }
            .filter { !it.isNullOrEmpty() }
            .distinct()
            .take(MAX_REPORTED_LINES)

        // Which app is actually in front. Distinguishes "our screen is wrong"
        // from "we are not even in the app any more" — a crash, a system
        // dialog, or a launch that never landed all look identical otherwise.
        val foreground = runCatchingStale { currentPackageName } ?: "unknown"

        return buildString {
            append("Foreground package: $foreground")
            if (visible.isEmpty()) {
                append("\nNo readable text on screen — blank, still loading, or crashed.")
            } else {
                append("\nOn screen:\n")
                append(visible.joinToString("\n") { "  - $it" })
            }
        }
    }

    /** UiAutomator throws if a node vanishes mid-read; a diagnostic must not. */
    private fun <T> runCatchingStale(block: () -> T): T? =
        try {
            block()
        } catch (_: StaleObjectException) {
            null
        }
}
