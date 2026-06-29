package com.cards.integration.setup

import com.cards.integration.helpers.DEFAULT_TIMEOUT_MS
import com.cards.integration.helpers.IntegrationTest
import com.cards.integration.helpers.playPassivelyToCompletion
import com.cards.integration.helpers.seatTwoAndConnect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * **The between-hands leave-with-winnings window, end-to-end over the wire.** When
 * a hand finishes on a real-chip table the server holds the next deal for a short
 * beat, broadcasts the deadline so the client can render an honest countdown, and
 * then auto-deals the next hand — no client "next hand" tap. This is the server
 * half of the play-screen overhaul's north star.
 */
@OptIn(ExperimentalTime::class)
class NextHandCountdownPlayTest : IntegrationTest() {

    @Test
    fun handEnds_serverBroadcastsCountdownDeadline_thenAutoDealsTheNextHand() =
        // A roomy beat so the deadline is comfortably in the future when observed,
        // and the auto-deal is distinguishable from a same-tick re-deal.
        integration(nextHandBeatMs = 1_500L) {
            val table = seatTwoAndConnect()
            table.hostGame.startHand()

            val beforeComplete = Clock.System.now().toEpochMilliseconds()
            val hand1 = table.playPassivelyToCompletion()
            assertTrue(hand1.handNumber == 1, "hand 1 played to completion")

            // The server announces the countdown the instant the hand completes,
            // carrying a deadline in the future — the client renders "Next hand in
            // 0:0X" against it, and it's the same instant the deal fires.
            val countdown = withTimeout(DEFAULT_TIMEOUT_MS) {
                table.hostGame.nextHandCountdowns.first()
            }
            assertTrue(
                countdown.deadlineEpochMs > beforeComplete,
                "the broadcast deadline is in the future (${countdown.deadlineEpochMs} > $beforeComplete)",
            )

            // Without any client tapping "next hand", the table auto-deals hand 2
            // when the beat elapses — the universal lifecycle private tables now ride.
            val hand2 = table.hostGame.nextSnapshot { it.handNumber == 2 && it.actingSeatIndex != null }
            assertTrue(hand2.handNumber == 2, "the next hand deals automatically after the beat")
        }
}
