package com.dangerfield.cards.baselineprofile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.dangerfield.cards.baselineprofile.BenchmarkJourney.CALL
import com.dangerfield.cards.baselineprofile.BenchmarkJourney.CHECK
import com.dangerfield.cards.baselineprofile.BenchmarkJourney.FOLD
import com.dangerfield.cards.baselineprofile.BenchmarkJourney.PRACTICE
import com.dangerfield.cards.baselineprofile.BenchmarkJourney.START
import com.dangerfield.cards.baselineprofile.BenchmarkJourney.describeScreen
import com.dangerfield.cards.baselineprofile.BenchmarkJourney.launchIntent
import com.dangerfield.cards.baselineprofile.BenchmarkJourney.tapMatching
import com.dangerfield.cards.baselineprofile.BenchmarkJourney.tapRequired
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * Drives the **minified** app through a real journey. This is the R8 smoke test.
 *
 * ## Why it is not a BaselineProfileRule test
 *
 * `BaselineProfileRule` refuses to run against a minified variant — it needs
 * unobfuscated output to write a profile against — so pointing it at
 * `benchmarkRelease` reports SKIPPED, which reads like a pass. A plain
 * instrumented test runs where the generators cannot.
 *
 * ## Why it exists
 *
 * A release APK building cleanly says nothing about whether it works. R8 breaks
 * what is resolved *by name at runtime*, and this app has three of those: the
 * `@Serializable` models (serializers are generated and reached only through a
 * `Companion`), the `@Serializable` navigation routes (renaming one breaks
 * type-safe nav with an argument error, not a missing-class error), and the
 * generated DI graph.
 *
 * Walking launch, Home, a table setup and a dealt hand exercises all three: the
 * graph on launch, routes on every navigation, models on every server call.
 * This is also the reason the profile journey should keep hitting a real
 * backend — stub it and this test stops testing what it was written for.
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
        device.pressHome()
        InstrumentationRegistry.getInstrumentation().context.startActivity(
            launchIntent().addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK,
            ),
        )

        // Reaching Home means the DI graph built and the start destination
        // resolved — the first thing R8 could have broken.
        check(device.wait(Until.hasObject(By.text(PRACTICE)), LAUNCH_TIMEOUT_MS) == true) {
            "Never reached Home. " + device.describeScreen()
        }

        device.tapRequired(PRACTICE)
        device.tapRequired(START)

        // A dealt hand means the room route resolved, the session started and
        // the table projection rendered.
        check(device.wait(Until.hasObject(By.textContains(FOLD)), TABLE_TIMEOUT_MS) == true) {
            "Reached table setup but never dealt. " + device.describeScreen()
        }

        val anyAction = By.text(Pattern.compile("$CHECK|$CALL.*|$FOLD"))
        check(device.tapMatching(anyAction, ACTION_TIMEOUT_MS)) {
            "The table dealt but offered no action. " + device.describeScreen()
        }
    }

    private companion object {
        const val LAUNCH_TIMEOUT_MS = 30_000L
        const val TABLE_TIMEOUT_MS = 30_000L
        const val ACTION_TIMEOUT_MS = 10_000L
    }
}
