package com.dangerfield.cards.libraries.cards

/**
 * The shipped V1 achievement library. ~20 achievements covering volume,
 * endurance, hand-strength milestones, bot-personality wins, and difficulty
 * milestones. Multiplayer-tier rewards land with Phase 3.
 *
 * Order in this list = display order in the achievements grid.
 */
val AllAchievements: List<Achievement> = listOf(
    // Volume — flow effortlessly, gentle pacing
    Achievement(
        id = AchievementId.FIRST_HAND,
        name = "Welcome to the felt",
        description = "Finish your first hand.",
        icon = "🃏",
        rarity = AchievementRarity.COMMON,
        criterion = Criterion.HandsPlayed(1),
        xpReward = AchievementRarity.COMMON.defaultXpReward,
    ),
    Achievement(
        id = AchievementId.HANDS_100,
        name = "Regular",
        description = "Play 100 hands.",
        icon = "🪙",
        rarity = AchievementRarity.RARE,
        criterion = Criterion.HandsPlayed(100),
        xpReward = AchievementRarity.RARE.defaultXpReward,
    ),
    Achievement(
        id = AchievementId.HANDS_1000,
        name = "Grinder",
        description = "Play 1,000 hands.",
        icon = "🏆",
        rarity = AchievementRarity.EPIC,
        criterion = Criterion.HandsPlayed(1_000),
        xpReward = AchievementRarity.EPIC.defaultXpReward,
        chipReward = 1_000L,
    ),

    // Endurance — keeping a stack alive
    Achievement(
        id = AchievementId.NO_BUST_50,
        name = "Bankroll discipline",
        description = "Survive 50 hands in a row without busting.",
        icon = "🧘",
        rarity = AchievementRarity.RARE,
        criterion = Criterion.Custom(key = NO_BUST_STREAK, target = 50),
        xpReward = AchievementRarity.RARE.defaultXpReward,
    ),
    Achievement(
        id = AchievementId.NO_BUST_100,
        name = "Iron stack",
        description = "Survive 100 hands in a row without busting.",
        icon = "🛡️",
        rarity = AchievementRarity.EPIC,
        criterion = Criterion.Custom(key = NO_BUST_STREAK, target = 100),
        xpReward = AchievementRarity.EPIC.defaultXpReward,
        chipReward = 500L,
    ),

    // Hand-strength milestones — show, don't win
    Achievement(
        id = AchievementId.SHOW_PAIR,
        name = "A pair at last",
        description = "Show a Pair at showdown.",
        icon = "👯",
        rarity = AchievementRarity.COMMON,
        criterion = Criterion.ShowAtLeast(HandCategoryGrade.Pair),
        xpReward = AchievementRarity.COMMON.defaultXpReward,
    ),
    Achievement(
        id = AchievementId.SHOW_TWO_PAIR,
        name = "Double dip",
        description = "Show Two Pair at showdown.",
        icon = "✌️",
        rarity = AchievementRarity.COMMON,
        criterion = Criterion.ShowAtLeast(HandCategoryGrade.TwoPair),
        xpReward = AchievementRarity.COMMON.defaultXpReward,
    ),
    Achievement(
        id = AchievementId.SHOW_THREE_OF_KIND,
        name = "Trips",
        description = "Show Three of a Kind at showdown.",
        icon = "🎯",
        rarity = AchievementRarity.COMMON,
        criterion = Criterion.ShowAtLeast(HandCategoryGrade.ThreeOfAKind),
        xpReward = AchievementRarity.COMMON.defaultXpReward,
    ),
    Achievement(
        id = AchievementId.SHOW_STRAIGHT,
        name = "In a row",
        description = "Show a Straight at showdown.",
        icon = "📏",
        rarity = AchievementRarity.RARE,
        criterion = Criterion.ShowAtLeast(HandCategoryGrade.Straight),
        xpReward = AchievementRarity.RARE.defaultXpReward,
    ),
    Achievement(
        id = AchievementId.SHOW_FLUSH,
        name = "All one suit",
        description = "Show a Flush at showdown.",
        icon = "💧",
        rarity = AchievementRarity.RARE,
        criterion = Criterion.ShowAtLeast(HandCategoryGrade.Flush),
        xpReward = AchievementRarity.RARE.defaultXpReward,
    ),
    Achievement(
        id = AchievementId.SHOW_FULL_HOUSE,
        name = "Full house",
        description = "Show a Full House at showdown.",
        icon = "🏠",
        rarity = AchievementRarity.EPIC,
        criterion = Criterion.ShowAtLeast(HandCategoryGrade.FullHouse),
        xpReward = AchievementRarity.EPIC.defaultXpReward,
        chipReward = 250L,
    ),
    Achievement(
        id = AchievementId.SHOW_FOUR_OF_KIND,
        name = "Quads",
        description = "Show Four of a Kind at showdown.",
        icon = "🍀",
        rarity = AchievementRarity.EPIC,
        criterion = Criterion.ShowAtLeast(HandCategoryGrade.FourOfAKind),
        xpReward = AchievementRarity.EPIC.defaultXpReward,
        chipReward = 500L,
    ),
    Achievement(
        id = AchievementId.SHOW_STRAIGHT_FLUSH,
        name = "Straight flush",
        description = "Show a Straight Flush at showdown.",
        icon = "🌊",
        rarity = AchievementRarity.LEGENDARY,
        criterion = Criterion.ShowAtLeast(HandCategoryGrade.StraightFlush),
        xpReward = AchievementRarity.LEGENDARY.defaultXpReward,
        chipReward = 2_000L,
    ),
    Achievement(
        id = AchievementId.SHOW_ROYAL_FLUSH,
        name = "Royal flush",
        description = "Show a Royal Flush at showdown — the rarest hand in poker.",
        icon = "👑",
        rarity = AchievementRarity.LEGENDARY,
        criterion = Criterion.ShowAtLeast(HandCategoryGrade.RoyalFlush),
        xpReward = AchievementRarity.LEGENDARY.defaultXpReward,
        chipReward = 5_000L,
    ),

    // Bot personality mastery — bot-only
    Achievement(
        id = AchievementId.BEAT_JANE_10,
        name = "Past the gatekeeper",
        description = "Win 10 hands against Jane.",
        icon = "🧐",
        rarity = AchievementRarity.RARE,
        criterion = Criterion.Custom(key = winsVsBotKey("Jane"), target = 10),
        xpReward = AchievementRarity.RARE.defaultXpReward,
        mode = AchievementMode.BOTS,
    ),
    Achievement(
        id = AchievementId.BEAT_DAVID_10,
        name = "Out-bluffed the bluffer",
        description = "Win 10 hands against David.",
        icon = "😎",
        rarity = AchievementRarity.RARE,
        criterion = Criterion.Custom(key = winsVsBotKey("David"), target = 10),
        xpReward = AchievementRarity.RARE.defaultXpReward,
        mode = AchievementMode.BOTS,
    ),
    Achievement(
        id = AchievementId.BEAT_GINA_10,
        name = "Beat the fox",
        description = "Win 10 hands against Gina.",
        icon = "🦊",
        rarity = AchievementRarity.RARE,
        criterion = Criterion.Custom(key = winsVsBotKey("Gina"), target = 10),
        xpReward = AchievementRarity.RARE.defaultXpReward,
        mode = AchievementMode.BOTS,
    ),
    Achievement(
        id = AchievementId.BEAT_STEVE_10,
        name = "Out-waited the turtle",
        description = "Win 10 hands against Steve.",
        icon = "🐢",
        rarity = AchievementRarity.RARE,
        criterion = Criterion.Custom(key = winsVsBotKey("Steve"), target = 10),
        xpReward = AchievementRarity.RARE.defaultXpReward,
        mode = AchievementMode.BOTS,
    ),
    Achievement(
        id = AchievementId.BEAT_MIKE_10,
        name = "Tamed the maniac",
        description = "Win 10 hands against Mike.",
        icon = "🤡",
        rarity = AchievementRarity.RARE,
        criterion = Criterion.Custom(key = winsVsBotKey("Mike"), target = 10),
        xpReward = AchievementRarity.RARE.defaultXpReward,
        mode = AchievementMode.BOTS,
    ),

    // Difficulty
    Achievement(
        id = AchievementId.CHALLENGING_FIRST_WIN,
        name = "Hello, Challenging",
        description = "Win a hand on the Challenging difficulty.",
        icon = "⚔️",
        rarity = AchievementRarity.RARE,
        criterion = Criterion.Custom(key = CHALLENGING_WINS, target = 1),
        xpReward = AchievementRarity.RARE.defaultXpReward,
        mode = AchievementMode.BOTS,
    ),
    Achievement(
        id = AchievementId.CHALLENGING_10_WINS,
        name = "Sharpened up",
        description = "Win 10 hands on the Challenging difficulty.",
        icon = "🗡️",
        rarity = AchievementRarity.EPIC,
        criterion = Criterion.Custom(key = CHALLENGING_WINS, target = 10),
        xpReward = AchievementRarity.EPIC.defaultXpReward,
        chipReward = 500L,
        mode = AchievementMode.BOTS,
    ),

    // Comeback
    Achievement(
        id = AchievementId.COMEBACK_FROM_5BB,
        name = "Short stack hero",
        description = "Double up from 5 big blinds or less.",
        icon = "📈",
        rarity = AchievementRarity.EPIC,
        criterion = Criterion.Custom(key = COMEBACK_5BB, target = 1),
        xpReward = AchievementRarity.EPIC.defaultXpReward,
        chipReward = 500L,
    ),
)

val AllAchievementsById: Map<AchievementId, Achievement> = AllAchievements.associateBy { it.id }

// Custom-counter keys. Keep these as constants so the engine and the
// registry stay in sync.
const val NO_BUST_STREAK: String = "no_bust_streak"
const val CHALLENGING_WINS: String = "challenging_wins"
const val COMEBACK_5BB: String = "comeback_5bb"

/** Key for "hands won against bot with [name]". */
fun winsVsBotKey(name: String): String = "wins_vs_bot_$name"
