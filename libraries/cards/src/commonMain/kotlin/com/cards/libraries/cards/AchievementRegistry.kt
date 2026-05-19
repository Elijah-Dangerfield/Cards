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
        id = AchievementId.HANDS_10,
        name = "Warming up",
        description = "Play 10 hands.",
        icon = "🔥",
        rarity = AchievementRarity.COMMON,
        criterion = Criterion.HandsPlayed(10),
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
        id = AchievementId.HANDS_500,
        name = "Card sharp",
        description = "Play 500 hands.",
        icon = "🎴",
        rarity = AchievementRarity.RARE,
        criterion = Criterion.HandsPlayed(500),
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
        isMystery = true,
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
        isMystery = true,
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
        isMystery = true,
    ),
    Achievement(
        id = AchievementId.DONT_CALL_IT_COMEBACK,
        name = "Don't call it a comeback",
        description = "Drop below 100 chips at some point, then climb back to a full 1,000-chip stack.",
        icon = "🔥",
        rarity = AchievementRarity.EPIC,
        criterion = Criterion.Custom(key = DONT_CALL_IT_COMEBACK_COUNTER, target = 1),
        xpReward = AchievementRarity.EPIC.defaultXpReward,
        chipReward = 500L,
        isMystery = true,
    ),

    // Pot-size milestones — based on the total pot of the hand the human
    // participated in (won or lost). Tracks ambition more than profit.
    Achievement(
        id = AchievementId.POT_500,
        name = "Pot odds",
        description = "Be at the table for a pot of 500 chips or more.",
        icon = "🥉",
        rarity = AchievementRarity.COMMON,
        criterion = Criterion.Custom(key = MAX_POT_SEEN, target = 500),
        xpReward = AchievementRarity.COMMON.defaultXpReward,
    ),
    Achievement(
        id = AchievementId.POT_1000,
        name = "Four-figure pot",
        description = "Be at the table for a pot of 1,000 chips or more.",
        icon = "🥈",
        rarity = AchievementRarity.RARE,
        criterion = Criterion.Custom(key = MAX_POT_SEEN, target = 1_000),
        xpReward = AchievementRarity.RARE.defaultXpReward,
    ),
    Achievement(
        id = AchievementId.POT_5000,
        name = "Whale pot",
        description = "Be at the table for a pot of 5,000 chips or more.",
        icon = "🐋",
        rarity = AchievementRarity.EPIC,
        criterion = Criterion.Custom(key = MAX_POT_SEEN, target = 5_000),
        xpReward = AchievementRarity.EPIC.defaultXpReward,
        chipReward = 500L,
        isMystery = true,
    ),

    // Tactical wins — based on outcome shape, not just frequency.
    Achievement(
        id = AchievementId.FIRST_WIN_BY_FOLD,
        name = "Bluffer's delight",
        description = "Win a pot without reaching showdown — everyone else folded.",
        icon = "🎭",
        rarity = AchievementRarity.COMMON,
        criterion = Criterion.Custom(key = WIN_BY_FOLD, target = 1),
        xpReward = AchievementRarity.COMMON.defaultXpReward,
    ),
    Achievement(
        id = AchievementId.WIN_BY_FOLD_10,
        name = "The convincer",
        description = "Win 10 pots without reaching showdown.",
        icon = "🪄",
        rarity = AchievementRarity.RARE,
        criterion = Criterion.Custom(key = WIN_BY_FOLD, target = 10),
        xpReward = AchievementRarity.RARE.defaultXpReward,
    ),
    Achievement(
        id = AchievementId.GOOD_FOLD_FIRST,
        name = "Know when to fold 'em",
        description = "Fold a hand that, in hindsight, you would have lost.",
        icon = "🧠",
        rarity = AchievementRarity.COMMON,
        criterion = Criterion.Custom(key = GOOD_FOLD, target = 1),
        xpReward = AchievementRarity.COMMON.defaultXpReward,
        isMystery = true,
    ),
    Achievement(
        id = AchievementId.GOOD_FOLD_25,
        name = "Disciplined",
        description = "Make 25 good folds — hands you would have lost at showdown.",
        icon = "🧘",
        rarity = AchievementRarity.RARE,
        criterion = Criterion.Custom(key = GOOD_FOLD, target = 25),
        xpReward = AchievementRarity.RARE.defaultXpReward,
        isMystery = true,
    ),
    Achievement(
        id = AchievementId.FIRST_ALL_IN,
        name = "All in",
        description = "Go all in for the first time.",
        icon = "🎲",
        rarity = AchievementRarity.COMMON,
        criterion = Criterion.Custom(key = ALL_IN_HANDS, target = 1),
        xpReward = AchievementRarity.COMMON.defaultXpReward,
    ),

    // Stack swings — captures the "I doubled up!" moment.
    Achievement(
        id = AchievementId.DOUBLE_UP,
        name = "Doubled up",
        description = "Finish a hand with 2× the chips you started it with.",
        icon = "📊",
        rarity = AchievementRarity.RARE,
        criterion = Criterion.Custom(key = DOUBLED_UP, target = 1),
        xpReward = AchievementRarity.RARE.defaultXpReward,
    ),
    Achievement(
        id = AchievementId.TRIPLE_UP,
        name = "Tripled up",
        description = "Finish a hand with 3× the chips you started it with.",
        icon = "💹",
        rarity = AchievementRarity.EPIC,
        criterion = Criterion.Custom(key = TRIPLED_UP, target = 1),
        xpReward = AchievementRarity.EPIC.defaultXpReward,
        chipReward = 250L,
    ),

    // Busting opponents — bot-only for V1; the MP equivalents will land
    // as separate ids when Phase 4.2 ships ("the prestige of busting a
    // real human is materially different from busting a bot").
    Achievement(
        id = AchievementId.FIRST_BUST_DEALT,
        name = "Dealer's choice",
        description = "Knock another player out of a hand. Their chips, your win.",
        icon = "💀",
        rarity = AchievementRarity.COMMON,
        criterion = Criterion.Custom(key = BUSTS_DEALT, target = 1),
        xpReward = AchievementRarity.COMMON.defaultXpReward,
        mode = AchievementMode.BOTS,
    ),
    Achievement(
        id = AchievementId.BUST_DEALT_5,
        name = "Eliminator",
        description = "Bust five opponents.",
        icon = "🪦",
        rarity = AchievementRarity.RARE,
        criterion = Criterion.Custom(key = BUSTS_DEALT, target = 5),
        xpReward = AchievementRarity.RARE.defaultXpReward,
        mode = AchievementMode.BOTS,
    ),

    // Level milestones
    Achievement(
        id = AchievementId.REACH_LEVEL_5,
        name = "Leveled up",
        description = "Reach level 5.",
        icon = "⭐",
        rarity = AchievementRarity.COMMON,
        criterion = Criterion.Custom(key = CURRENT_LEVEL, target = 5),
        xpReward = AchievementRarity.COMMON.defaultXpReward,
    ),
    Achievement(
        id = AchievementId.REACH_LEVEL_10,
        name = "Double digits",
        description = "Reach level 10.",
        icon = "🌟",
        rarity = AchievementRarity.RARE,
        criterion = Criterion.Custom(key = CURRENT_LEVEL, target = 10),
        xpReward = AchievementRarity.RARE.defaultXpReward,
    ),
    Achievement(
        id = AchievementId.REACH_LEVEL_25,
        name = "Felt veteran",
        description = "Reach level 25.",
        icon = "💎",
        rarity = AchievementRarity.EPIC,
        criterion = Criterion.Custom(key = CURRENT_LEVEL, target = 25),
        xpReward = AchievementRarity.EPIC.defaultXpReward,
        chipReward = 1_000L,
    ),
)

val AllAchievementsById: Map<AchievementId, Achievement> = AllAchievements.associateBy { it.id }

// Custom-counter keys. Keep these as constants so the engine and the
// registry stay in sync.
const val NO_BUST_STREAK: String = "no_bust_streak"
const val CHALLENGING_WINS: String = "challenging_wins"
const val COMEBACK_5BB: String = "comeback_5bb"

/** Recovered from <=100 chips back to a full 1,000-chip stack. Armed when
 *  the stack first dips into the danger zone; triggers when the recovery
 *  happens on a later hand. */
const val DONT_CALL_IT_COMEBACK_COUNTER: String = "dont_call_it_comeback"

/** Internal flag — set to 1 once the human's stack dips to <=100. Read by
 *  the comeback check on each subsequent hand. */
const val SHORT_STACK_ARMED: String = "short_stack_armed"

/** Wins where everyone else folded (no showdown reached). */
const val WIN_BY_FOLD: String = "win_by_fold"

/** Folds where, in hindsight, the human would have lost the showdown anyway. */
const val GOOD_FOLD: String = "good_fold"

/** Hands where the human went all-in at any point. */
const val ALL_IN_HANDS: String = "all_in_hands"

/** Times the human's stack grew to ≥ 2× / 3× the starting stack within a hand. */
const val DOUBLED_UP: String = "doubled_up"
const val TRIPLED_UP: String = "tripled_up"

/**
 * Opponents the human has knocked out (`stack ≤ 0` at hand end on a hand
 * the human won the pot). Multi-bust hands count each busted opponent.
 */
const val BUSTS_DEALT: String = "busts_dealt"

/** Max pot the human has been a part of (sticky high-water mark). */
const val MAX_POT_SEEN: String = "max_pot_seen"

/** Current player level (mirrored from progression for level-threshold achievements). */
const val CURRENT_LEVEL: String = "current_level"

/** Key for "hands won against bot with [name]". */
fun winsVsBotKey(name: String): String = "wins_vs_bot_$name"
