package com.dangerfield.cards.libraries.gameplay

/**
 * Returns a copy of this [GameState] suitable for broadcast to a
 * single seated subscriber. Other seats' [Seat.holeCards] are emptied
 * to keep them private; the viewer's own seat is returned unchanged.
 *
 * Showdown reveal: at [BettingRound.Showdown] and [BettingRound.Complete]
 * any seat that's still [Seat.isInHand] (InHand or AllIn — i.e. went
 * to showdown) keeps its hole cards so the reveal UI works. Seats that
 * folded earlier stay scrubbed — they mucked, their cards are gone.
 *
 * Pass [viewerSeatIndex] = -1 (or any non-existent index) for a fully
 * scrubbed view. Useful for spectators / logging in future phases;
 * Phase 2 only ever calls this with a real seat.
 *
 * Pure: no side effects. Cheap enough for the socket publisher's hot
 * path — we allocate one new seat list per emit, share everything
 * else with the source state.
 */
fun GameState.scrubbedFor(viewerSeatIndex: Int): GameState {
    val showdownLike = street == BettingRound.Showdown || street == BettingRound.Complete
    val updatedSeats = seats.map { seat ->
        when {
            seat.index == viewerSeatIndex -> seat
            seat.holeCards.isEmpty() -> seat
            showdownLike && seat.isInHand -> seat
            else -> seat.copy(holeCards = emptyList())
        }
    }
    return copy(seats = updatedSeats)
}
