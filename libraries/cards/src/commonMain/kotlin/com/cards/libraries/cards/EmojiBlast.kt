package com.dangerfield.cards.libraries.cards

/**
 * One-shot "emoji blast" event a screen renders as a full-screen pop-in /
 * drift-up animation. Emitted from the poker table's emote tray, and reused
 * by the Profile cosmetics "Try it out" affordance so both surfaces share the
 * exact same blast contract.
 *
 * `emittedAtEpochMs` doubles as the identity used to clear the state without
 * racing the next blast (the renderer reports it back on completion).
 */
data class EmojiBlast(
    val emoji: String,
    val emittedAtEpochMs: Long,
)
