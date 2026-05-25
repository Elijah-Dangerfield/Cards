package com.dangerfield.cards.features.room.impl.tutorial

import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.gameplay.Rank
import com.dangerfield.cards.libraries.gameplay.Suit

/**
 * The full scripted walkthrough. Three hands, each built around a single
 * decision the user should learn: **raise** when strong, **call/check**
 * when the price is right, **fold** when beat.
 *
 * Each hand follows the same shape: orient → set up the scenario → prompt
 * the correct action → explain *why* it was right after the action lands.
 * Steps advance when the user takes the [TutorialStep.expected] action.
 */
internal object TutorialScript {

    // `by lazy` because the per-hand builders below reference the
    // private opponent vals (ada/ben/cleo) declared later in this object.
    // Eager init would dereference them before they're populated.
    val steps: List<TutorialStep> by lazy {
        buildList {
            addAll(handOne())
            addAll(handTwo())
            addAll(handThree())
        }
    }

    // -- Constants -----------------------------------------------------

    private const val STARTING_STACK = 10_000L
    private const val SB = 10L
    private const val BB = 20L
    private const val TOTAL_HANDS = 3

    private val ada = TutorialOpponent(
        name = "Ada",
        emoji = "🦊",
        backgroundColorHex = "#E48A58",
        stack = STARTING_STACK,
    )
    private val ben = TutorialOpponent(
        name = "Ben",
        emoji = "💀",
        backgroundColorHex = "#9E9E9E",
        stack = STARTING_STACK,
    )
    private val cleo = TutorialOpponent(
        name = "Cleo",
        emoji = "🐉",
        backgroundColorHex = "#5DA75A",
        stack = STARTING_STACK,
    )

    private const val HERO_NAME = "You"
    private const val HERO_EMOJI = "🐱"
    private const val HERO_BG = "#E4B458"

    // ------------------------------------------------------------------
    // Hand 1 — Raise when you're strong (AA preflop)
    // ------------------------------------------------------------------
    private fun handOne(): List<TutorialStep> {
        val title = "Raise when you're strong"
        val handNumber = 1

        val holeCards = listOf(card(Rank.Ace, Suit.Spades), card(Rank.Ace, Suit.Hearts))

        fun baseTable(
            community: List<Card> = emptyList(),
            pot: Long = SB + BB,
            opponents: List<TutorialOpponent> = listOf(
                ada.copy(role = BlindRole.SmallBlind, stack = STARTING_STACK - SB),
                ben.copy(role = BlindRole.BigBlind, stack = STARTING_STACK - BB),
                cleo.copy(role = BlindRole.Button),
            ),
            heroStack: Long = STARTING_STACK,
            heroLastAction: String? = null,
            heroLabel: String? = "Pocket aces",
            actions: TutorialLegalActions = TutorialLegalActions(),
        ) = TutorialTable(
            handTitle = title,
            handNumber = handNumber,
            totalHands = TOTAL_HANDS,
            opponents = opponents,
            community = community,
            pot = pot,
            heroName = HERO_NAME,
            heroEmoji = HERO_EMOJI,
            heroBackgroundColorHex = HERO_BG,
            heroHoleCards = holeCards,
            heroStack = heroStack,
            heroRole = null,
            heroLastAction = heroLastAction,
            heroHandLabel = heroLabel,
            legalActions = actions,
        )

        return listOf(
            // 1 — Orient
            TutorialStep(
                table = baseTable(),
                coach = CoachMark(
                    title = "Welcome",
                    body = "You're heads-up against three bots. No real chips at stake — we'll walk through three hands.",
                    anchor = TutorialAnchor.Opponents,
                    ctaLabel = "Got it",
                ),
                expected = TutorialAction.Continue,
            ),
            // 2 — Blinds
            TutorialStep(
                table = baseTable(),
                coach = CoachMark(
                    title = "Small Blind & Big Blind",
                    body = "Every hand starts with two forced bets. They build the pot before anyone acts, so there's always something to fight for.",
                    anchor = TutorialAnchor.Opponents,
                    ctaLabel = "Next",
                ),
                expected = TutorialAction.Continue,
            ),
            // 3 — The pot
            TutorialStep(
                table = baseTable(),
                coach = CoachMark(
                    title = "The pot",
                    body = "Chips go here as the hand plays out. Whoever wins the hand takes the pot.",
                    anchor = TutorialAnchor.Pot,
                    ctaLabel = "Next",
                ),
                expected = TutorialAction.Continue,
            ),
            // 4 — Your hole cards
            TutorialStep(
                table = baseTable(),
                coach = CoachMark(
                    title = "Your hand",
                    body = "Pocket aces — the strongest starting hand in poker. You're a clear favorite to win this pot.",
                    anchor = TutorialAnchor.HoleCards,
                    ctaLabel = "Nice",
                ),
                expected = TutorialAction.Continue,
            ),
            // 5 — Prompt to raise
            TutorialStep(
                table = baseTable(
                    actions = TutorialLegalActions(
                        showFold = true,
                        showCall = true,
                        callAmount = BB,
                        showRaise = true,
                        raiseAmount = 60L,
                        enabled = TutorialAction.Raise,
                    ),
                ),
                coach = CoachMark(
                    title = "Raise",
                    body = "When you have the best of it, raise to build the pot you're likely to win. Tap Raise 60.",
                    anchor = TutorialAnchor.ActionBar,
                ),
                expected = TutorialAction.Raise,
            ),
            // 6 — After-raise explanation + showdown auto-resolve
            TutorialStep(
                table = baseTable(
                    pot = SB + BB + 60L + 60L + 60L,
                    opponents = listOf(
                        ada.copy(role = BlindRole.SmallBlind, stack = STARTING_STACK - 60L, lastAction = "Called 50"),
                        ben.copy(role = BlindRole.BigBlind, stack = STARTING_STACK - 60L, lastAction = "Called 40"),
                        cleo.copy(role = BlindRole.Button, stack = STARTING_STACK - 60L, lastAction = "Called 60"),
                    ),
                    heroStack = STARTING_STACK - 60L,
                    heroLastAction = "Raised 60",
                ),
                coach = CoachMark(
                    title = "Now there's more to play for",
                    body = "Raising with strong hands is how you make money over the long run. The bots called — now it's a real pot.",
                    anchor = TutorialAnchor.Pot,
                    ctaLabel = "Deal the flop",
                ),
                expected = TutorialAction.Continue,
            ),
            // 7 — Showdown
            TutorialStep(
                table = baseTable(
                    community = listOf(
                        card(Rank.Ace, Suit.Clubs),
                        card(Rank.Seven, Suit.Diamonds),
                        card(Rank.Two, Suit.Hearts),
                        card(Rank.Nine, Suit.Spades),
                        card(Rank.King, Suit.Diamonds),
                    ),
                    pot = 240L,
                    opponents = listOf(
                        ada.copy(role = BlindRole.SmallBlind, stack = STARTING_STACK - 60L, lastAction = "Folded", folded = true),
                        ben.copy(role = BlindRole.BigBlind, stack = STARTING_STACK - 60L, lastAction = "Folded", folded = true),
                        cleo.copy(role = BlindRole.Button, stack = STARTING_STACK - 60L, lastAction = "Folded", folded = true),
                    ),
                    heroStack = STARTING_STACK - 60L + 240L,
                    heroLabel = "Three of a kind, aces",
                ),
                coach = CoachMark(
                    title = "You win",
                    body = "Trips — three aces. The board didn't help anyone else and the bots folded. The pot's yours.",
                    anchor = TutorialAnchor.Pot,
                    ctaLabel = "Next hand",
                ),
                expected = TutorialAction.Continue,
            ),
        )
    }

    // ------------------------------------------------------------------
    // Hand 2 — Call when the price is right (KQ suited)
    // ------------------------------------------------------------------
    private fun handTwo(): List<TutorialStep> {
        val title = "Call when the price is right"
        val handNumber = 2

        val holeCards = listOf(card(Rank.King, Suit.Hearts), card(Rank.Queen, Suit.Hearts))

        fun baseTable(
            community: List<Card> = emptyList(),
            pot: Long = SB + BB,
            opponents: List<TutorialOpponent> = listOf(
                ada.copy(role = BlindRole.Button),
                ben.copy(role = BlindRole.SmallBlind, stack = STARTING_STACK - SB),
                cleo.copy(role = BlindRole.BigBlind, stack = STARTING_STACK - BB),
            ),
            heroStack: Long = STARTING_STACK,
            heroLastAction: String? = null,
            heroLabel: String? = "King-Queen suited",
            actions: TutorialLegalActions = TutorialLegalActions(),
        ) = TutorialTable(
            handTitle = title,
            handNumber = handNumber,
            totalHands = TOTAL_HANDS,
            opponents = opponents,
            community = community,
            pot = pot,
            heroName = HERO_NAME,
            heroEmoji = HERO_EMOJI,
            heroBackgroundColorHex = HERO_BG,
            heroHoleCards = holeCards,
            heroStack = heroStack,
            heroRole = null,
            heroLastAction = heroLastAction,
            heroHandLabel = heroLabel,
            legalActions = actions,
        )

        return listOf(
            // 1 — Setup
            TutorialStep(
                table = baseTable(),
                coach = CoachMark(
                    title = "A new hand",
                    body = "King-Queen suited. Not a monster like aces, but worth seeing a flop with — it makes flushes and straights.",
                    anchor = TutorialAnchor.HoleCards,
                    ctaLabel = "Next",
                ),
                expected = TutorialAction.Continue,
            ),
            // 2 — Prompt to call
            TutorialStep(
                table = baseTable(
                    opponents = listOf(
                        ada.copy(role = BlindRole.Button, lastAction = "Folded", folded = true),
                        ben.copy(role = BlindRole.SmallBlind, stack = STARTING_STACK - SB),
                        cleo.copy(role = BlindRole.BigBlind, stack = STARTING_STACK - BB),
                    ),
                    actions = TutorialLegalActions(
                        showFold = true,
                        showCall = true,
                        callAmount = BB,
                        showRaise = true,
                        raiseAmount = 60L,
                        enabled = TutorialAction.Call,
                    ),
                ),
                coach = CoachMark(
                    title = "Call",
                    body = "It costs you 20 to see three more cards. With a hand that can improve, that's a good deal. Tap Call.",
                    anchor = TutorialAnchor.ActionBar,
                ),
                expected = TutorialAction.Call,
            ),
            // 3 — Flop comes, prompt to check
            TutorialStep(
                table = baseTable(
                    community = listOf(
                        card(Rank.Two, Suit.Hearts),
                        card(Rank.Seven, Suit.Hearts),
                        card(Rank.Jack, Suit.Clubs),
                    ),
                    pot = SB + BB + BB + BB,
                    opponents = listOf(
                        ada.copy(role = BlindRole.Button, lastAction = "Folded", folded = true),
                        ben.copy(role = BlindRole.SmallBlind, stack = STARTING_STACK - BB, lastAction = "Called 10"),
                        cleo.copy(role = BlindRole.BigBlind, stack = STARTING_STACK - BB, lastAction = "Checked"),
                    ),
                    heroStack = STARTING_STACK - BB,
                    heroLastAction = "Called 20",
                    heroLabel = "Flush draw",
                    actions = TutorialLegalActions(
                        showCheck = true,
                        showFold = true,
                        showRaise = true,
                        raiseAmount = 40L,
                        enabled = TutorialAction.Check,
                    ),
                ),
                coach = CoachMark(
                    title = "Check",
                    body = "Two hearts on the flop — you're one card away from a flush. Nobody bet into you, so check to see the next card for free.",
                    anchor = TutorialAnchor.ActionBar,
                ),
                expected = TutorialAction.Check,
            ),
            // 4 — After-check explanation, auto-resolve
            TutorialStep(
                table = baseTable(
                    community = listOf(
                        card(Rank.Two, Suit.Hearts),
                        card(Rank.Seven, Suit.Hearts),
                        card(Rank.Jack, Suit.Clubs),
                        card(Rank.Four, Suit.Hearts),
                        card(Rank.Ten, Suit.Spades),
                    ),
                    pot = 60L,
                    opponents = listOf(
                        ada.copy(role = BlindRole.Button, lastAction = "Folded", folded = true),
                        ben.copy(role = BlindRole.SmallBlind, stack = STARTING_STACK - BB, lastAction = "Folded", folded = true),
                        cleo.copy(role = BlindRole.BigBlind, stack = STARTING_STACK - BB, lastAction = "Folded", folded = true),
                    ),
                    heroStack = STARTING_STACK - BB + 60L,
                    heroLabel = "Flush, king-high",
                ),
                coach = CoachMark(
                    title = "Hearts everywhere",
                    body = "The turn brought another heart — you made your flush. Bets dried up and the pot's yours. Checking when unsure is free information.",
                    anchor = TutorialAnchor.Pot,
                    ctaLabel = "Next hand",
                ),
                expected = TutorialAction.Continue,
            ),
        )
    }

    // ------------------------------------------------------------------
    // Hand 3 — Fold when you're beat (7-2 offsuit vs a raise)
    // ------------------------------------------------------------------
    private fun handThree(): List<TutorialStep> {
        val title = "Fold when you're beat"
        val handNumber = 3

        val holeCards = listOf(card(Rank.Seven, Suit.Clubs), card(Rank.Two, Suit.Diamonds))

        fun baseTable(
            opponents: List<TutorialOpponent>,
            pot: Long = SB + BB,
            heroStack: Long = STARTING_STACK - BB,
            heroLastAction: String? = null,
            heroLabel: String? = "Seven-deuce offsuit",
            actions: TutorialLegalActions = TutorialLegalActions(),
        ) = TutorialTable(
            handTitle = title,
            handNumber = handNumber,
            totalHands = TOTAL_HANDS,
            opponents = opponents,
            community = emptyList(),
            pot = pot,
            heroName = HERO_NAME,
            heroEmoji = HERO_EMOJI,
            heroBackgroundColorHex = HERO_BG,
            heroHoleCards = holeCards,
            heroStack = heroStack,
            heroRole = BlindRole.BigBlind,
            heroLastAction = heroLastAction,
            heroHandLabel = heroLabel,
            legalActions = actions,
        )

        return listOf(
            // 1 — Setup
            TutorialStep(
                table = baseTable(
                    opponents = listOf(
                        ada.copy(role = BlindRole.SmallBlind, stack = STARTING_STACK - SB),
                        ben.copy(role = BlindRole.Button),
                        cleo,
                    ),
                ),
                coach = CoachMark(
                    title = "Your worst nightmare",
                    body = "Seven-deuce offsuit — statistically the worst starting hand in poker. Different suits, no straights, no flushes.",
                    anchor = TutorialAnchor.HoleCards,
                    ctaLabel = "Ouch",
                ),
                expected = TutorialAction.Continue,
            ),
            // 2 — Opponent raises
            TutorialStep(
                table = baseTable(
                    opponents = listOf(
                        ada.copy(role = BlindRole.SmallBlind, stack = STARTING_STACK - SB),
                        ben.copy(role = BlindRole.Button, stack = STARTING_STACK - 60L, lastAction = "Raised 60"),
                        cleo,
                    ),
                    pot = SB + BB + 60L,
                ),
                coach = CoachMark(
                    title = "Ben raised",
                    body = "To stay in the hand, you'd have to put another 40 in with junk cards against a player who likes their hand. Bad math.",
                    anchor = TutorialAnchor.Opponents,
                    ctaLabel = "I see",
                ),
                expected = TutorialAction.Continue,
            ),
            // 3 — Prompt to fold
            TutorialStep(
                table = baseTable(
                    opponents = listOf(
                        ada.copy(role = BlindRole.SmallBlind, stack = STARTING_STACK - SB, lastAction = "Folded", folded = true),
                        ben.copy(role = BlindRole.Button, stack = STARTING_STACK - 60L, lastAction = "Raised 60"),
                        cleo.copy(lastAction = "Folded", folded = true),
                    ),
                    pot = SB + BB + 60L,
                    actions = TutorialLegalActions(
                        showFold = true,
                        showCall = true,
                        callAmount = 40L,
                        showRaise = true,
                        raiseAmount = 120L,
                        enabled = TutorialAction.Fold,
                    ),
                ),
                coach = CoachMark(
                    title = "Fold",
                    body = "Folding costs nothing but the 20 you already posted as the blind. Tap Fold.",
                    anchor = TutorialAnchor.ActionBar,
                ),
                expected = TutorialAction.Fold,
            ),
            // 4 — After-fold explanation
            TutorialStep(
                table = baseTable(
                    opponents = listOf(
                        ada.copy(role = BlindRole.SmallBlind, stack = STARTING_STACK - SB, lastAction = "Folded", folded = true),
                        ben.copy(role = BlindRole.Button, stack = STARTING_STACK - 60L + (SB + BB + 60L), lastAction = "Wins 90"),
                        cleo.copy(lastAction = "Folded", folded = true),
                    ),
                    pot = 0,
                    heroLastAction = "Folded",
                ),
                coach = CoachMark(
                    title = "Folding saves money",
                    body = "Most starting hands aren't worth playing. Folding bad cards quickly is the most profitable habit in poker.",
                    anchor = TutorialAnchor.HoleCards,
                    ctaLabel = "Finish tutorial",
                ),
                expected = TutorialAction.Continue,
            ),
        )
    }

    private fun card(rank: Rank, suit: Suit) = Card(rank, suit)
}
