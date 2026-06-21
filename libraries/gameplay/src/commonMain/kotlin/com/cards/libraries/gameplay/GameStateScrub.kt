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
        // Stealth bots: a bot the server didn't reveal (isBot but no style key)
        // must present as a human on the wire — strip the bot marker so no
        // client can tell. Revealed bots keep isBot + their style key.
        val deBotted = if (seat.isBot && seat.botStyleKey == null) seat.copy(isBot = false) else seat
        when {
            deBotted.index == viewerSeatIndex -> deBotted
            deBotted.holeCards.isEmpty() -> deBotted
            showdownLike && deBotted.isInHand -> deBotted
            else -> deBotted.copy(holeCards = emptyList())
        }
    }
    return copy(seats = updatedSeats)
}
