package com.dangerfield.cards.libraries.bots

import kotlinx.serialization.Serializable

@Serializable
enum class BotDifficulty {
    Casual,
    Standard,
    Challenging,
}

@Serializable
data class BotPersonality(
    val name: String,
    val tightness: Double,
    val aggression: Double,
    val bluffRate: Double,
    val avatarKey: String,
) {
    init {
        require(tightness in 0.0..1.0) { "tightness must be in 0..1" }
        require(aggression in 0.0..1.0) { "aggression must be in 0..1" }
        require(bluffRate in 0.0..0.4) { "bluffRate must be in 0..0.4" }
    }

    companion object {
        val Jane: BotPersonality = BotPersonality(
            name = "Jane",
            tightness = 0.78,
            aggression = 0.25,
            bluffRate = 0.04,
            avatarKey = "avatar_jane",
        )
        val David: BotPersonality = BotPersonality(
            name = "David",
            tightness = 0.42,
            aggression = 0.72,
            bluffRate = 0.18,
            avatarKey = "avatar_david",
        )
        val Gina: BotPersonality = BotPersonality(
            name = "Gina",
            tightness = 0.62,
            aggression = 0.58,
            bluffRate = 0.10,
            avatarKey = "avatar_gina",
        )
        val Steve: BotPersonality = BotPersonality(
            name = "Steve",
            tightness = 0.38,
            aggression = 0.22,
            bluffRate = 0.05,
            avatarKey = "avatar_steve",
        )
        val Mike: BotPersonality = BotPersonality(
            name = "Mike",
            tightness = 0.20,
            aggression = 0.90,
            bluffRate = 0.30,
            avatarKey = "avatar_mike",
        )

        val Roster: List<BotPersonality> = listOf(Jane, David, Gina, Steve, Mike)

        fun forDifficulty(difficulty: BotDifficulty, count: Int): List<BotPersonality> {
            val pool = when (difficulty) {
                BotDifficulty.Casual -> listOf(Jane, Steve)
                BotDifficulty.Standard -> listOf(Jane, David, Gina, Steve, Mike)
                BotDifficulty.Challenging -> listOf(David, Gina, Mike)
            }
            return List(count) { pool[it % pool.size] }
        }
    }
}
