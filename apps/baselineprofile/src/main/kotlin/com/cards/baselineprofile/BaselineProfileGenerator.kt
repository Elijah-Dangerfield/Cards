package com.dangerfield.cards.baselineprofile

import android.content.Intent
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.Rule
import org.junit.Test

/**
 * Captures the Baseline Profile shipped with release builds.
 *
 * A profile lists the classes and methods to compile ahead of time so a code
 * path is not interpreted the first time a player walks it. That makes it worth
 * far more than a startup measurement suggests: the win lands on cold start and
 * first-run, which is exactly when a new player decides whether the app feels
 * good.
 *
 * ## Why this drives a real journey rather than just launching
 *
 * A launch-only profile covers the splash and Home. It does nothing for the
 * screen players spend their whole session on. This walks onboarding, reaches
 * Home, opens a practice table and plays hands, so the poker engine, the table
 * projection and the felt's animation code are all compiled ahead of time too.
 *
 * ## What it cannot do, and why that is fine
 *
 * This drives the **installed binary** through UiAutomator. It cannot inject a
 * stacked deck or a scripted bot the way `PokerScenario` does — those live in
 * `commonTest` and never reach an APK — so the hands it plays are whatever the
 * engine deals.
 *
 * That costs nothing here. A profile records *which code ran*, not what
 * happened, and playing several hands walks the win, loss and hand-end paths
 * regardless of who takes any particular pot. Forcing an outcome would add a
 * production seam for no gain.
 *
 * ## Fail loudly
 *
 * Every waypoint is asserted. A generator that silently walks half the journey
 * still produces a profile — a quietly worse one that nobody notices for
 * months. If a copy change breaks a step, this should go red, and the fix is to
 * update the string here.
 *
 * ## Running it
 *
 * ```
 * ./gradlew :apps:compose:generateBaselineProfile -Pcards.targetEnv=dev
 * ```
 *
 * Starts its own emulator, so no device is needed. The generated profile lands
 * in `apps/compose/src/androidRelease/generated/baselineProfiles/` and is
 * committed — release builds consume it from there rather than regenerating.
 *
 * **`-Pcards.targetEnv=dev` is not optional.** Profile generation runs a
 * *release* variant (`nonMinifiedRelease`), and `AppEnvironment.current` sends
 * release builds to **prod**. Without the override, walking onboarding mints a
 * real anonymous account in the production Supabase and sprays synthetic
 * telemetry across the prod dashboards, which is exactly what happened the
 * first time this was run. The flag points the run at dev instead.
 */
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = PACKAGE,
        // Onboarding only happens on a first run, so the profile has to be
        // captured against a clean install every iteration. Without this the
        // second iteration would start at Home and the onboarding classes would
        // fall out of the profile.
        includeInStartupProfile = true,
    ) {
        pressHome()
        // Ask the app to skip onboarding and land on Home. Credentials are
        // optional: without them the app makes a guest account, which is enough
        // for a profile and needs no secret on a contributor's machine.
        startActivityAndWait(
            Intent().apply {
                // The class by name, not MAIN/LAUNCHER. Resolving by category
                // fails once extras and the benchmark's own flags are attached;
                // naming the component sidesteps intent resolution entirely.
                setClassName(PACKAGE, "$PACKAGE.MainActivity")
                putExtra(EXTRA_SKIP_ONBOARDING, true)
                InstrumentationRegistry.getArguments().getString(ARG_EMAIL)
                    ?.let { putExtra(EXTRA_EMAIL, it) }
                InstrumentationRegistry.getArguments().getString(ARG_PASSWORD)
                    ?.let { putExtra(EXTRA_PASSWORD, it) }
            },
        )

        // The hook is best-effort by design: if the build ignored it (wrong
        // environment, older APK) onboarding is still on screen, and walking it
        // is the correct fallback rather than failing the run.
        completeOnboarding()
        playAPracticeTable()
    }

    /**
     * Guest onboarding, walked until Home appears.
     *
     * Adaptive rather than a fixed sequence of taps, and that is a correctness
     * choice, not laziness. Onboarding is four steps today, its grant screen
     * has a separate offline wording, and a returning install skips the whole
     * thing — a hardcoded tap-tap-tap breaks on all three and breaks again the
     * next time a step is added. This taps whichever known CTA is on screen and
     * stops when Home is reached.
     *
     * It still fails loudly: not reaching Home is the assertion, which is the
     * thing actually worth asserting. Getting there in three taps or five does
     * not matter to the profile.
     *
     * Signing in with a real provider is deliberately not attempted — it leaves
     * the app for a browser, where this would be driving Google's UI, not ours.
     */
    private fun MacrobenchmarkScopeLike.completeOnboarding() {
        val anyOnboardingCta = By.text(
            Pattern.compile("$CONTINUE_AS_GUEST|$CONTINUE|$CLAIM_AND_PLAY|$TAKE_A_SEAT"),
        )
        repeat(ONBOARDING_MAX_STEPS) {
            if (device.hasObject(By.text(PRACTICE))) return
            device.tapMatching(anyOnboardingCta, ONBOARDING_STEP_TIMEOUT_MS)
        }
        check(device.wait(Until.hasObject(By.text(PRACTICE)), FIRST_SCREEN_TIMEOUT_MS) == true) {
            "Baseline profile journey never reached Home. Onboarding copy likely " +
                "changed \u2014 update the CTA list in BaselineProfileGenerator."
        }
    }

    /**
     * Home, into a bots table, then a few hands.
     *
     * The actions are taken as they are offered rather than scripted: whether
     * the table wants a check or a call depends on the deal, and the profile
     * only cares that the action path executed.
     */
    private fun MacrobenchmarkScopeLike.playAPracticeTable() {
        device.tapRequired(PRACTICE)
        // Practice opens a table-setup sheet (seats, stakes, difficulty) rather
        // than dealing straight away. Missing this is why earlier runs produced
        // a profile with zero coverage of the felt while still passing.
        device.tapRequired(START)

        // The deal-in animation runs before any action is offered.
        device.wait(Until.hasObject(By.textContains(FOLD)), TABLE_TIMEOUT_MS)

        // One selector for "whatever the table is offering", not a chain of
        // four. A chain costs its full timeout per miss, and three misses per
        // iteration across three collect passes is the difference between two
        // minutes and half an hour.
        val anyAction = By.text(Pattern.compile("$CHECK|$CALL.*|$NEXT_HAND|$CONTINUE"))
        repeat(ACTIONS_TO_TAKE) {
            if (!device.tapMatching(anyAction, ACTION_TIMEOUT_MS)) {
                // Bots thinking or an animation mid-flight. The loop has
                // iterations left; waiting is the right move.
                device.waitForIdle()
            }
        }
    }

    private companion object {
        const val PACKAGE = "com.dangerfield.cards"

        // Mirrors BenchmarkHooks. Duplicated rather than shared because this
        // module must not depend on the app's code — it drives the installed
        // binary, and a compile dependency would be a lie about that boundary.
        const val EXTRA_SKIP_ONBOARDING = "cards.benchmark.skipOnboarding"
        const val EXTRA_EMAIL = "cards.benchmark.email"
        const val EXTRA_PASSWORD = "cards.benchmark.password"

        /** Instrumentation args, passed through from Gradle. */
        const val ARG_EMAIL = "cards.benchmark.email"
        const val ARG_PASSWORD = "cards.benchmark.password"

        // Verbatim from libraries/resources/.../strings.xml. If one of these
        // changes, this test is where it surfaces — which is the point.
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

        /** Cold start plus the first-run config fetch. */
        const val FIRST_SCREEN_TIMEOUT_MS = 20_000L
        const val TABLE_TIMEOUT_MS = 20_000L

        /**
         * Per onboarding step. Generous: the grant step waits on guest-account
         * creation, which is a real network round trip on a cold emulator.
         */
        const val ONBOARDING_STEP_TIMEOUT_MS = 15_000L

        /** Four steps today, with slack for a step being added or repeated. */
        const val ONBOARDING_MAX_STEPS = 8

        /**
         * Short on purpose: the action bar is either up or it is not, and a
         * miss is the normal case while bots think. This timeout is paid on
         * every idle iteration, so seconds here become minutes overall.
         */
        const val ACTION_TIMEOUT_MS = 800L

        /**
         * Enough taps to cover several hands including their ends, without
         * making profile generation take longer than anyone will tolerate.
         */
        const val ACTIONS_TO_TAKE = 14
    }
}

/**
 * The subset of `MacrobenchmarkScope` this generator uses.
 *
 * Declared as a type alias rather than imported directly so the journey above
 * reads as a description of what a player does, instead of as benchmark API.
 */
private typealias MacrobenchmarkScopeLike = androidx.benchmark.macro.MacrobenchmarkScope

/** Taps an exact-text element, returning whether it was there. */
private fun UiDevice.tap(text: String, timeoutMs: Long): Boolean =
    tapMatching(By.text(text), timeoutMs)

/**
 * Finds and taps, re-finding if the node goes stale between the two.
 *
 * This app animates every transition, and UiAutomator hands back a handle to an
 * accessibility node that Compose may replace before the click lands — which
 * throws `StaleObjectException` rather than missing. Re-finding is the fix;
 * sleeping is not, because the window is a frame wide and unpredictable.
 *
 * A narrow `catch` on purpose. Swallowing everything here would turn a genuine
 * journey break into a silently shorter profile, which is the failure this
 * generator is built to avoid.
 */
internal fun UiDevice.tapMatching(selector: BySelector, timeoutMs: Long): Boolean {
    repeat(TAP_ATTEMPTS) { attempt ->
        waitForIdle()
        // Only the first look pays the caller's timeout. A retry means the node
        // was on screen a frame ago, so re-finding it is near-instant — paying
        // the full timeout again on every retry is what made a three-minute
        // run take half an hour.
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

private const val TAP_ATTEMPTS = 3
private const val RETRY_FIND_MS = 500L

/**
 * Taps an element that must be there, failing the run if it is not.
 *
 * The alternative — skipping quietly — still emits a profile, just a worse one,
 * and nobody would notice for months.
 */
private fun UiDevice.tapRequired(text: String, timeoutMs: Long = 15_000L) {
    check(tap(text, timeoutMs)) {
        "Baseline profile journey stalled: no \"$text\" after ${timeoutMs}ms. " +
            "If the copy changed, update BaselineProfileGenerator to match."
    }
}
