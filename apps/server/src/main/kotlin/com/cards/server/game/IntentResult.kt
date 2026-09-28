package com.dangerfield.cards.server.game

/**
 * Outcome of a [GameSession] mutation request — startHand, applyIntent,
 * requestNextHand. The socket route translates these into
 * `RoomSocketEventDto.IntentAck(accepted, error?)` and sends them
 * back to the originating client.
 *
 * [Duplicate] is a nonce the session has already processed: the replay
 * was swallowed and nothing mutated. To a retrying client that is a
 * success (the ack says accepted), but a server-internal caller that
 * believed it was submitting fresh work must be able to tell it apart —
 * a bot driver that mistook a swallowed replay for a real action froze a
 * table forever (MP-39).
 *
 * Rejection reasons are intended to be developer-facing (short, stable,
 * loggable). Client UI doesn't render them verbatim — it shows a
 * generic "couldn't submit, try again" and relies on the server-state
 * flow to correct itself.
 */
sealed class IntentResult {
    object Accepted : IntentResult()
    data object Duplicate : IntentResult()
    data class Rejected(val reason: String) : IntentResult()
}
