package com.dangerfield.cards.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import com.dangerfield.cards.baselineprofile.BenchmarkJourney.PACKAGE
import com.dangerfield.cards.baselineprofile.BenchmarkJourney.launchIntent
import com.dangerfield.cards.baselineprofile.BenchmarkJourney.reachHome
import org.junit.Rule
import org.junit.Test

/**
 * The **startup** profile: launch to Home, and stop.
 *
 * ## Why this is separate from the journey profile
 *
 * A startup profile is not just "the profile, but smaller". It is placed
 * differently in the DEX layout, and Android's guidance is explicit that an
 * oversized one "overflows into subsequent DEX files… and slows down startup"
 * — the exact opposite of the point.
 *
 * Both files were previously generated from one deep journey with
 * `includeInStartupProfile = true`, which made `startup-prof.txt` and
 * `baseline-prof.txt` byte-for-byte identical at 46,451 rules. The startup
 * profile was carrying the whole poker table, and was actively working against
 * cold start. Splitting them is the fix, and mirrors what Now in Android does.
 *
 * Deliberately does NOT stub the network. Every real cold start runs Ktor,
 * session restore, deserialization and Sentry init — roughly a fifth of the
 * captured rules — and that code is on the one path 100% of users take. A
 * benchmark mode that faked it away would delete AOT coverage from precisely
 * the launch this profile exists to speed up.
 */
class StartupProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = PACKAGE,
        includeInStartupProfile = true,
        // Startup is short and highly repeatable, so the profile stabilises
        // fast. The default cap of 15 buys nothing here and costs a launch and
        // a cold-boot network fan-out per extra iteration.
        maxIterations = 8,
        stableIterations = 3,
    ) {
        pressHome()
        startActivityAndWait(launchIntent())
        reachHome()
    }
}
