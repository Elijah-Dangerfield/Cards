package com.dangerfield.cards.baselineprofile

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * Drives the **minified** app through a real journey. This is the R8 smoke test.
 *
 * ## Why a separate test from the profile generator
 *
 * `BaselineProfileRule` refuses to run against a minified variant — it needs
 * unobfuscated output to write a profile against, so pointing it at
 * `benchmarkRelease` reports SKIPPED rather than failing. That is easy to
 * mistake for a pass. This uses a plain instrumented test instead, so it
 * actually runs where the generator cannot.
 *
 * ## Why it is needed at all
 *
 * R8 breaks what is resolved *by name at runtime*, and a release APK building
 * cleanly says nothing about whether it works. The three things at risk here
 * are the `@Serializable` models (their serializers are generated and reached
 * only through a `Companion`), the `@Serializable` navigation routes (renaming
 * one breaks type-safe nav with an argument error, not a missing-class error),
 * and the generated DI graph. All three have keep rules. This is what proves
 * those rules are right.
 *
 * Walking onboarding, Home, a table setup and a dealt hand exercises all three:
 * routes on every navigation, models on every server call, the graph on launch.
 *
 * ```
 * ./gradlew :apps:baselineprofile:pixel6Api34BenchmarkReleaseAndroidTest \
 *   -Pcards.targetEnv=dev
 * ```
 */
@RunWith(AndroidJUnit4::class)
class MinifiedReleaseSmokeTest {

    private val device: UiDevice =
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun theMinifiedAppReachesATableAndDealsAHand() {
        launchSkippingOnboarding()

        // Reaching Home at all means the DI graph built and the start
        // destination resolved — the first thing R8 could have broken.
        check(device.wait(Until.hasObject(By.text(PRACTICE)), LAUNCH_TIMEOUT_MS) == true) {
            "Minified app never reached Home. Check the keep rules for the DI " +
                "entry point and the navigation routes."
        }

        device.tapRequired(PRACTICE)
        device.tapRequired(START)

        // A dealt hand means the room route resolved, the session started and
        // the table projection rendered. That is navigation, serialization and
        // the engine all surviving obfuscation.
        check(device.wait(Until.hasObject(By.textContains(FOLD)), TABLE_TIMEOUT_MS) == true) {
            "Minified app reached table setup but never dealt. Most likely a " +
                "@Serializable model or route was renamed — check mapping.txt."
        }

        val anyAction = By.text(Pattern.compile("$CHECK|$CALL.*|$FOLD"))
        check(device.tapMatching(anyAction, ACTION_TIMEOUT_MS)) {
            "The table dealt but offered no action. The LegalActions projection " +
                "likely did not survive minification."
        }
    }

    private fun launchSkippingOnboarding() {
        device.pressHome()
        val context = InstrumentationRegistry.getInstrumentation().context
        context.startActivity(
            Intent().apply {
                setClassName(PACKAGE, "$PACKAGE.MainActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(EXTRA_SKIP_ONBOARDING, true)
                InstrumentationRegistry.getArguments().getString(ARG_EMAIL)
                    ?.let { putExtra(EXTRA_EMAIL, it) }
                InstrumentationRegistry.getArguments().getString(ARG_PASSWORD)
                    ?.let { putExtra(EXTRA_PASSWORD, it) }
            },
        )
        device.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), LAUNCH_TIMEOUT_MS)
    }

    private companion object {
        const val PACKAGE = "com.dangerfield.cards"
        const val EXTRA_SKIP_ONBOARDING = "cards.benchmark.skipOnboarding"
        const val EXTRA_EMAIL = "cards.benchmark.email"
        const val EXTRA_PASSWORD = "cards.benchmark.password"
        const val ARG_EMAIL = "cards.benchmark.email"
        const val ARG_PASSWORD = "cards.benchmark.password"

        const val PRACTICE = "Practice"
        const val START = "Start"
        const val CHECK = "Check"
        const val CALL = "Call"
        const val FOLD = "Fold"

        const val LAUNCH_TIMEOUT_MS = 30_000L
        const val TABLE_TIMEOUT_MS = 30_000L
        const val ACTION_TIMEOUT_MS = 10_000L
    }
}
