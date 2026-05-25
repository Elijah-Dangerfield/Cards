package com.dangerfield.cards.server.game

/**
 * Outcome of a [GameSession] mutation request — startHand, applyIntent,
 * requestNextHand. The socket route translates these into
 * `RoomSocketEventDto.IntentAck(accepted, error?)` and sends them
 * back to the originating client.
 *
 * Rejection reasons are intended to be developer-facing (short, stable,
 * loggable). Client UI doesn't render them verbatim — it shows a
 * generic "couldn't submit, try again" and relies on the server-state
 * flow to correct itself.
 */
sealed class IntentResult {
    object Accepted : IntentResult()
    data class Rejected(val reason: String) : IntentResult()
}
