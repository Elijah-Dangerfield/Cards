package com.dangerfield.cards.server.game

/**
 * Server-side handle for a single seat at the start of (or about to
 * start) a hand. Carries the userId — which can be a real Supabase
 * UUID for humans or a synthetic `"bot-..."` for backend-driven bots
 * — plus the display name and seat assignment.
 *
 * `GameSession.startHand` consumes a list of these to construct the
 * initial [com.dangerfield.cards.libraries.gameplay.Seat]s. Bots and
 * humans share this type by design (Phase 3 — both flow through the
 * same engine path; only the orchestration around them differs).
 */
data class SeatOccupant(
    val seatIndex: Int,
    val userId: String,
    val displayName: String,
    val isBot: Boolean,
)
