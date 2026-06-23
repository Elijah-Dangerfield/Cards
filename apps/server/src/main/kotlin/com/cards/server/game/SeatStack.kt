package com.dangerfield.cards.server.game

import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.server.domain.UserId

/**
 * The live table stack for [userId] in this game state, or null when they
 * hold no seat. A seat's `playerId` is the stringified Supabase UUID (or a
 * `"bot-…"` string for bots), matching [UserId.value]. Read at cash-out to
 * settle what a leaving / disconnected / swept player walks away with —
 * chips already committed to the current pot are excluded from the stack,
 * so settling it forfeits them (the correct poker semantics for a
 * mid-hand departure).
 */
internal fun GameState.stackFor(userId: UserId): Long? =
    seats.firstOrNull { it.playerId == userId.value.toString() }?.stack
