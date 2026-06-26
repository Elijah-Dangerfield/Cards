package com.cards.integration.helpers

import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.gameplay.Deck

/**
 * Deck-scripting DSL for the integration harness (MP-18). Lets a test spell out
 * the exact cards a hand is dealt — `server.scriptDeck(code, stackedDeck(...))` —
 * so a chosen outcome (a bust, a chop, a side pot) happens deterministically
 * instead of riding on a shuffle. Mirrors the client-side `ScenarioDecks` helper
 * that `FakeRoomServer` already uses; unify into a shared gameplay-testing module
 * if a third consumer appears.
 */

/** Parse a whitespace-separated card spec into [Card]s, e.g. `cards("Ah Kh")`. */
fun cards(spec: String): List<Card> =
    spec.trim()
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .map { Card.parse(it) }

/**
 * Build a 52-card ordered [Deck] so the engine deals exactly the cards you spell
 * out. Dealing order (verified against `GameEngine`): two hole cards per seat in
 * **seat-index order**, then community cards straight off the top — flop (3),
 * turn (1), river (1), no burns. So:
 *
 * ```
 * stackedDeck(
 *   holeBySeat = listOf(cards("2c 7d"), cards("As Ad")), // seat 0, seat 1
 *   board = cards("Ah 7c 2d 9h 3s"),                      // flop, turn, river
 * )
 * ```
 *
 * [holeBySeat] lists every seat that will be dealt in (index order). The rest of
 * the deck is padded from the standard 52 so [Deck.fromOrdered]'s unique-52
 * invariant holds; spell out only as many [board] cards as the scenario reaches.
 */
fun stackedDeck(
    holeBySeat: List<List<Card>>,
    board: List<Card> = emptyList(),
): Deck {
    val front = holeBySeat.flatten() + board
    require(front.size == front.toSet().size) { "duplicate cards in stacked-deck spec: $front" }
    val frontSet = front.toSet()
    val rest = Card.fullDeck.filterNot { it in frontSet }
    return Deck.fromOrdered(front + rest)
}
