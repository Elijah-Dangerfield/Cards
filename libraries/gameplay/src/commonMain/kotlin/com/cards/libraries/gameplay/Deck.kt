package com.dangerfield.cards.libraries.gameplay

import kotlin.random.Random

class Deck private constructor(private val remaining: ArrayDeque<Card>) {

    val size: Int get() = remaining.size

    fun draw(): Card = remaining.removeFirst()

    fun drawN(n: Int): List<Card> = List(n) { draw() }

    fun snapshot(): List<Card> = remaining.toList()

    companion object {
        fun shuffled(random: Random = Random.Default): Deck {
            val cards = Card.fullDeck.toMutableList()
            cards.shuffle(random)
            return Deck(ArrayDeque(cards))
        }

        fun fromOrdered(cards: List<Card>): Deck {
            require(cards.size == 52) { "A full deck must contain 52 cards (got ${cards.size})" }
            require(cards.toSet().size == 52) { "Duplicate cards in deck" }
            return Deck(ArrayDeque(cards))
        }
    }
}
