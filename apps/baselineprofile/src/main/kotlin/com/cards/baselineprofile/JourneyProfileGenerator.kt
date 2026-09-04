package com.dangerfield.cards.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import com.dangerfield.cards.baselineprofile.BenchmarkJourney.PACKAGE
import com.dangerfield.cards.baselineprofile.BenchmarkJourney.launchIntent
import com.dangerfield.cards.baselineprofile.BenchmarkJourney.playAPracticeTable
import com.dangerfield.cards.baselineprofile.BenchmarkJourney.reachHome
import org.junit.Rule
import org.junit.Test

/**
 * The **baseline** profile: Home, into a practice table, through several hands.
 *
 * `includeInStartupProfile = false` on purpose — see [StartupProfileGenerator]
 * for why putting the poker table in the startup profile made cold start worse.
 * This one covers the first run of the felt, the engine and the animations,
 * which is where a poker session actually spends its time.
 *
 * ## Iterations
 *
 * Capped well below the framework default of 15. Each iteration relaunches
 * cold, and every cold boot fans out into a config fetch, a `/v1/me` call and
 * seven user-scoped syncers. Fifteen of those is what put HTTP 429s into the
 * dev backend; it was never the journey itself.
 *
 * Signing in as the reserved account matters for the same reason: without a
 * session, `GuestSessionHealer` mints a fresh anonymous account on every cold
 * boot, precisely because the benchmark hook set `hasUserOnboarded`.
 */
class JourneyProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = PACKAGE,
        includeInStartupProfile = false,
        maxIterations = 6,
        stableIterations = 3,
    ) {
        pressHome()
        startActivityAndWait(launchIntent())
        reachHome()
        playAPracticeTable()
    }
}
