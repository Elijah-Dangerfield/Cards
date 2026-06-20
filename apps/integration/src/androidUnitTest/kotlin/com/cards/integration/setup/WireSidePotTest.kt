package com.cards.integration.setup

import com.cards.integration.helpers.IntegrationTest
import com.cards.integration.helpers.seatThreeAndConnect
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Multi-tier side-pot settlement over the REAL wire. `WireAllInTest` shoves
 * heads-up with equal stacks — winner-take-all, a single pot. A genuine side
 * pot needs three seats with unequal stacks: a short all-in plus larger callers,
 * so the short seat is eligible only for the main pot. The engine's side-pot
 * math is property-tested ([GameEnginePropertyTest]); the gap this closes is the
 * end-to-end *wire* path — that the per-tier eligibility survives the socket.
 *
 * The integration server's deck is non-deterministic (`InProcessServer` uses
 * `Random.Default`), so every assertion here is card-independent: we engineer the
 * unequal stacks via fold-outs (controllable, no showdown), then assert chip
 * conservation and the short seat's eligibility *bound* — it can never win more
 * than the main pot it sits in, regardless of who shows down best.
 *
 * Each per-hand driver reads-and-acts in one loop that breaks only on `Complete`,
 * so the forward snapshot cursor is never advanced past an actionable snapshot
 * without acting on it (that deadlocks the wire — the next snapshot only exists
 * once someone acts).
 */
class WireSidePotTest : IntegrationTest() {

    @Test
    fun threeWayAllIn_unequalStacks_settlesMainAndSidePots_overTheWire() = integration {
        val trio = seatThreeAndConnect()
        val obs = trio.observer

        // --- Hand 1: bleed one seat short via a fold-out (no showdown). ---
        // Roles bind by action order, not seat number, so this is robust to
        // position: the first actor is the aggressor (wins), the next distinct
        // actor commits then folds (the short seat), the last folds cheaply.
        obs.startHand()
        var aggressor = -1
        var shortSeat = -1
        var shortPlayerId: String? = null
        var raiseTo = 0L
        var hand1Number = -1
        while (true) {
            val s = obs.nextSnapshot { it.street == BettingRound.Complete || it.actingSeatIndex != null }
            if (s.street == BettingRound.Complete) break
            val seat = s.actingSeatIndex!!
            if (aggressor == -1) {
                aggressor = seat
                raiseTo = s.settings.startingStack * 3 / 10
                hand1Number = s.handNumber
            }
            val toCall = s.currentBetThisStreet - s.seatAt(seat).contributedThisStreet
            val intent = when {
                s.street == BettingRound.Preflop && seat == aggressor ->
                    if (s.currentBetThisStreet < raiseTo) {
                        PlayerIntent.Raise(seat, totalAmountThisStreet = raiseTo)
                    } else {
                        PlayerIntent.Call(seat)
                    }
                s.street == BettingRound.Preflop && shortSeat == -1 -> {
                    shortSeat = seat
                    shortPlayerId = s.seatAt(seat).playerId
                    PlayerIntent.Call(seat)
                }
                s.street == BettingRound.Preflop -> PlayerIntent.Fold(seat)
                toCall > 0 -> PlayerIntent.Fold(seat)
                seat == aggressor -> PlayerIntent.Bet(seat, amount = raiseTo / 3)
                else -> PlayerIntent.Check(seat)
            }
            val ack = trio.gameForSeat(s, seat).submit(intent)
            check(ack.accepted) { "${s.street} action $intent rejected: ${ack.error}" }
        }
        checkNotNull(shortPlayerId) { "hand 1 never seated a short player" }

        // --- Hand 2: everyone shoves. Unequal stacks → a layered all-in. ---
        obs.requestNextHand()
        var shortEntering = -1L
        var end: GameState? = null
        while (true) {
            val s = obs.nextSnapshot {
                it.handNumber > hand1Number && (it.street == BettingRound.Complete || it.actingSeatIndex != null)
            }
            if (shortEntering == -1L) {
                // First hand-2 snapshot: verify the bleed actually left one seat
                // strictly short, so we're testing a real side pot — not three
                // equal shoves (which would settle as a single pot).
                val entering = { seatIndex: Int ->
                    val seat = s.seatAt(seatIndex)
                    seat.stack + seat.contributedThisHand
                }
                val shortIdx = s.seats.first { it.playerId == shortPlayerId }.index
                shortEntering = entering(shortIdx)
                s.seats.filter { it.index != shortIdx }.forEach { other ->
                    assertTrue(
                        entering(other.index) > shortEntering,
                        "the bleed must leave seat $shortIdx ($shortEntering) strictly shortest; " +
                            "seat ${other.index} entered with ${entering(other.index)}",
                    )
                }
            }
            if (s.street == BettingRound.Complete) {
                end = s
                break
            }
            val seat = s.actingSeatIndex!!
            val ack = trio.gameForSeat(s, seat).submit(PlayerIntent.AllIn(seat))
            check(ack.accepted) { "all-in at seat $seat rejected: ${ack.error}" }
        }
        val settled = checkNotNull(end)

        // The other clients see the same settled hand over the wire.
        trio.gameForSeat(settled, (settled.seats.first { it.playerId == shortPlayerId }.index + 1) % settled.seats.size)
            .nextSnapshot { it.street == BettingRound.Complete && it.handNumber == settled.handNumber }

        val total = settled.settings.startingStack * settled.seats.size
        assertEquals(
            total,
            settled.seats.sumOf { it.stack },
            "chips conserve through a three-way layered all-in over the wire",
        )
        // The short seat sits only in the main pot (its entering stack from every
        // player). It can never collect a side pot — the wire must honour that.
        val mainPotCap = shortEntering * settled.seats.size
        val shortFinal = settled.seats.first { it.playerId == shortPlayerId }.stack
        assertTrue(
            shortFinal <= mainPotCap,
            "the short all-in seat is ineligible for side pots: final $shortFinal must be <= main-pot cap $mainPotCap",
        )
        assertEquals(BettingRound.Complete, settled.street)
        assertEquals(5, settled.community.size, "a called all-in runs the board to a five-card showdown")
    }
}
