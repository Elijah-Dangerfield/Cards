package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.gameplay.Deck

/**
 * Parse a whitespace-separated card spec into [Card]s, e.g. `cards("Ah Kh")`.
 * Accepts both suit symbols and letters (`Card.parse` handles both).
 */
fun cards(spec: String): List<Card> =
    spec.trim()
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .map { Card.parse(it) }

/**
 * Build a 52-card ordered [Deck] so [com.dangerfield.cards.libraries.gameplay.GameEngine.startHand]
 * deals exactly the cards you specify.
 *
 * Dealing order (verified against `GameEngine`): two hole cards per seat in
 * **seat-index order**, then community cards straight off the top — flop (3),
 * turn (1), river (1), no burns. So:
 *
 * ```
 * stackedDeck(
 *   holeBySeat = listOf(cards("Ah Kh"), cards("Qs Qd")), // seat 0, seat 1
 *   board = cards("Ah 7c 2d 9h 3s"),                      // flop, turn, river
 * )
 * ```
 *
 * [holeBySeat] must list every seat that will be dealt in (index order). The
 * remaining cards are padded from the standard deck so the 52-unique invariant
 * of [Deck.fromOrdered] holds; deal only as many [board] cards as the scenario
 * actually reaches.
 */
fun stackedDeck(
    holeBySeat: List<List<Card>>,
    board: List<Card> = emptyList(),
): Deck {
    val front = holeBySeat.flatten() + board
    require(front.size == front.toSet().size) {
        "duplicate cards in stacked-deck spec: $front"
    }
    val frontSet = front.toSet()
    val rest = Card.fullDeck.filterNot { it in frontSet }
    return Deck.fromOrdered(front + rest)
}
