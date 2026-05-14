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
    val emoji: String,
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
            emoji = "🧐",
        )
        val David: BotPersonality = BotPersonality(
            name = "David",
            tightness = 0.42,
            aggression = 0.72,
            bluffRate = 0.18,
            avatarKey = "avatar_david",
            emoji = "😎",
        )
        val Gina: BotPersonality = BotPersonality(
            name = "Gina",
            tightness = 0.62,
            aggression = 0.58,
            bluffRate = 0.10,
            avatarKey = "avatar_gina",
            emoji = "🦊",
        )
        val Steve: BotPersonality = BotPersonality(
            name = "Steve",
            tightness = 0.38,
            aggression = 0.22,
            bluffRate = 0.05,
            avatarKey = "avatar_steve",
            emoji = "🐢",
        )
        val Mike: BotPersonality = BotPersonality(
            name = "Mike",
            tightness = 0.20,
            aggression = 0.90,
            bluffRate = 0.30,
            avatarKey = "avatar_mike",
            emoji = "🤡",
        )

        val Roster: List<BotPersonality> = listOf(Jane, David, Gina, Steve, Mike)

        fun forDifficulty(difficulty: BotDifficulty, count: Int): List<BotPersonality> {
            // Ordering matters: with few bots we want the first N to span
            // distinct archetypes so the table doesn't feel like clones.
            // First slot LAG (action), then tight (foil), then maniac, then
            // calling station, then balanced TAG.
            val pool = when (difficulty) {
                BotDifficulty.Casual -> listOf(Steve, Jane)
                BotDifficulty.Standard -> listOf(David, Jane, Mike, Steve, Gina)
                BotDifficulty.Challenging -> listOf(David, Mike, Gina)
            }
            return List(count) { pool[it % pool.size] }
        }
    }
}
