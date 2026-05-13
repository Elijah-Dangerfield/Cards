package com.dangerfield.cards.libraries.gameplay

import kotlinx.serialization.Serializable

@Serializable
enum class Suit(val symbol: String) {
    Clubs("♣"),
    Diamonds("♦"),
    Hearts("♥"),
    Spades("♠");

    companion object {
        val all: List<Suit> = entries
    }
}

@Serializable
enum class Rank(val value: Int, val short: String) {
    Two(2, "2"),
    Three(3, "3"),
    Four(4, "4"),
    Five(5, "5"),
    Six(6, "6"),
    Seven(7, "7"),
    Eight(8, "8"),
    Nine(9, "9"),
    Ten(10, "T"),
    Jack(11, "J"),
    Queen(12, "Q"),
    King(13, "K"),
    Ace(14, "A");

    companion object {
        val all: List<Rank> = entries

        fun fromValue(value: Int): Rank =
            entries.first { it.value == value }
    }
}

@Serializable
data class Card(val rank: Rank, val suit: Suit) : Comparable<Card> {

    override fun compareTo(other: Card): Int =
        rank.value.compareTo(other.rank.value)

    override fun toString(): String = "${rank.short}${suit.symbol}"

    companion object {
        val fullDeck: List<Card> = Suit.all.flatMap { suit ->
            Rank.all.map { rank -> Card(rank, suit) }
        }

        fun parse(text: String): Card {
            require(text.length >= 2) { "Card text too short: '$text'" }
            val rankShort = text.substring(0, text.length - 1)
            val suitSymbol = text.substring(text.length - 1)
            val rank = Rank.entries.firstOrNull { it.short == rankShort }
                ?: error("Unknown rank '$rankShort' in '$text'")
            val suit = Suit.entries.firstOrNull { it.symbol == suitSymbol }
                ?: parseSuitLetter(suitSymbol)
                ?: error("Unknown suit '$suitSymbol' in '$text'")
            return Card(rank, suit)
        }

        private fun parseSuitLetter(letter: String): Suit? = when (letter.lowercase()) {
            "c" -> Suit.Clubs
            "d" -> Suit.Diamonds
            "h" -> Suit.Hearts
            "s" -> Suit.Spades
            else -> null
        }
    }
}
