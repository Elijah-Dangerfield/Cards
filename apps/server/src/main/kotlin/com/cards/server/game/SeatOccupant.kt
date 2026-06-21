package com.dangerfield.cards.server.game

import com.dangerfield.cards.server.domain.BotSeat

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
    /**
     * The player's equipped Badge-/Title-slot product ids, resolved once at
     * hand-start from their equipment. Copied onto the engine
     * [com.dangerfield.cards.libraries.gameplay.Seat] so opponents' clients can
     * render their badges. Empty for bots.
     */
    val badgeProductIds: List<String> = emptyList(),
    /**
     * Avatar emoji + background-color hex, snapshotted from the member's
     * profile at join. Copied onto the engine
     * [com.dangerfield.cards.libraries.gameplay.Seat] so opponents' clients
     * render the real avatar. Null for bots.
     */
    val avatarEmoji: String? = null,
    val avatarBackgroundColor: String? = null,
    /**
     * Lifetime XP, resolved once at hand-start from the player's server
     * progression. Copied onto the engine
     * [com.dangerfield.cards.libraries.gameplay.Seat] so opponents' clients
     * derive and render the player's level. Null for bots.
     */
    val xp: Long? = null,
    /**
     * Backend-bot truth for this seat, carried from the room member so the
     * server bot driver can read the seat's personality / difficulty without a
     * separate lookup. Null for humans. Survives across hands (the driver keeps
     * its own roster keyed by [userId]); see `ServerBotDriver`.
     */
    val bot: BotSeat? = null,
)
