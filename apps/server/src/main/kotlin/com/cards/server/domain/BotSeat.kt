package com.dangerfield.cards.server.domain

import com.dangerfield.cards.libraries.bots.BotDifficulty
import com.dangerfield.cards.libraries.bots.BotPersonality

/**
 * Server-side truth for a backend-driven bot occupying a room seat.
 *
 * The bot is driven entirely on the server (see `ServerBotDriver`); the client
 * only ever renders what we project onto the wire. [revealed] is the single
 * switch separating the two product modes:
 *
 *  - **revealed = true** — the host added the bot in the private lobby. The
 *    wire advertises it as a bot (🤖 avatar + BOT badge + playing-style label)
 *    so it's obvious to everyone at the table.
 *  - **revealed = false** — a future matchmaking auto-fill. The bot presents as
 *    an ordinary human member; nothing on the wire reveals it. Not produced in
 *    this slice, but every projection already honors the flag so enabling it
 *    later is a one-line change.
 *
 * Both [personality] and [difficulty] come straight from `:libraries:bots`
 * (already `@Serializable`), so this type persists in a session snapshot for
 * free and reaches the bot decision engine unchanged.
 */
data class BotSeat(
    val personality: BotPersonality,
    val difficulty: BotDifficulty,
    val revealed: Boolean,
)
