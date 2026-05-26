package com.dangerfield.cards.features.room.impl.tutorial

import com.dangerfield.cards.features.room.impl.LegalActions
import com.dangerfield.cards.features.room.impl.PlayPokerState
import com.dangerfield.cards.features.room.impl.SeatView
import com.dangerfield.cards.features.room.impl.TableUiState
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.PlayerAction
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.gameplay.Rank
import com.dangerfield.cards.libraries.gameplay.Suit

/**
 * Three-hand scripted walkthrough rendered through the live
 * `PlayPokerScreen`. Each hand teaches one decision by setting up a
 * scenario, prompting the correct action via the action bar (or swipe-fold
 * gesture), and explaining *why* after the action lands.
 */
internal object TutorialScript {

    // `by lazy` because the helper builders below reference the opponent
    // constants declared later in this object; eager init would NPE.
    val steps: List<TutorialStep> by lazy {
        buildList {
            addAll(intro())
            addAll(handOne())
            addAll(handTwo())
            addAll(handThree())
        }
    }

    // ------------------------------------------------------------------
    // Intro — three foundational narration cards. Assumes ZERO poker
    // knowledge. Each marked `isBasics = true` so experienced players can
    // skip the whole block in two taps. No fabricated table behind these
    // — pure centered explainers with a hero glyph.
    // ------------------------------------------------------------------
    private fun intro(): List<TutorialStep> = listOf(
        TutorialStep(
            isBasics = true,
            heroGlyph = "🎯",
            coach = CoachMark(
                title = "The goal: win the pot",
                body = "The pot is the pile of chips in the middle of the table. Every hand, players fight to claim it. Either you make the best hand by the end — or everyone else gives up and you win it uncontested.",
                ctaLabel = "Got it",
            ),
        ),
        TutorialStep(
            isBasics = true,
            heroGlyph = "💰",
            coach = CoachMark(
                title = "How the pot grows",
                body = "Players take turns putting chips in. You can CALL (match the current bet to stay in), RAISE (put in more, forcing others to match or quit), or CHECK (pass — only legal when no one's bet yet). Raise enough and opponents may FOLD — give up the hand and let you win it without showdown.",
                ctaLabel = "Makes sense",
            ),
        ),
        TutorialStep(
            isBasics = true,
            heroGlyph = "🏆",
            coach = CoachMark(
                title = "Best 5-card hand wins",
                body = "If multiple players survive to the end, the strongest 5-card hand wins. Weakest → strongest: pair · two pair · three of a kind · straight · flush · full house · four of a kind · straight flush · royal flush. Tap the ? on the live table anytime to pull up the full chart.",
                ctaLabel = "Let's play",
            ),
        ),
    )

    // -- Constants -----------------------------------------------------

    private const val SB = 10L
    private const val BB = 20L
    private const val HUMAN_INDEX = 0
    private const val ADA_INDEX = 1
    private const val BEN_INDEX = 2
    private const val CLEO_INDEX = 3

    // ------------------------------------------------------------------
    // Hand 1 — Raise when you're strong (pocket aces)
    // ------------------------------------------------------------------
    private fun handOne(): List<TutorialStep> {
        val holeCards = listOf(
            Card(Rank.Ace, Suit.Spades),
            Card(Rank.Ace, Suit.Hearts),
        )

        // Opponents seeded with blinds posted; human has the button.
        // Human first-to-act preflop (heads-up convention: SB acts first
        // preflop AND postflop, so use 4-handed positioning instead — button
        // is human, SB is Ada, BB is Ben, Cleo is UTG. Cleo folds, Ada calls,
        // Ben checks → action back to human).
        // For simplicity we set everyone folded except human + Ada by
        // step 3 so the pot equals SB+BB+call-amount, and the user just
        // needs to tap Raise.
        fun seats(
            humanContributed: Long = 0,
            adaContributed: Long = SB,
            benContributed: Long = BB,
            cleoContributed: Long = 0,
            humanLastAction: PlayerAction? = null,
            adaLastAction: PlayerAction? = null,
            benLastAction: PlayerAction? = null,
            cleoLastAction: PlayerAction? = PlayerAction.Fold,
            cleoFolded: Boolean = true,
            humanActing: Boolean = false,
        ): List<SeatView> = listOf(
            human(
                holeCards = holeCards,
                stack = 10_000L - humanContributed,
                contributed = humanContributed,
                isActing = humanActing,
                isDealer = true,
                lastAction = humanLastAction,
            ),
            bot(
                index = ADA_INDEX,
                name = "Ada",
                emoji = "🦊",
                stack = 10_000L - adaContributed,
                contributed = adaContributed,
                isSmallBlind = true,
                lastAction = adaLastAction,
            ),
            bot(
                index = BEN_INDEX,
                name = "Ben",
                emoji = "💀",
                stack = 10_000L - benContributed,
                contributed = benContributed,
                isBigBlind = true,
                lastAction = benLastAction,
            ),
            bot(
                index = CLEO_INDEX,
                name = "Cleo",
                emoji = "🐉",
                stack = 10_000L - cleoContributed,
                contributed = cleoContributed,
                participation = if (cleoFolded) HandParticipation.Folded else HandParticipation.InHand,
                lastAction = cleoLastAction,
            ),
        )

        fun table(
            seats: List<SeatView>,
            pot: Long,
            potCommitted: Long,
            community: List<Card> = emptyList(),
            street: BettingRound = BettingRound.Preflop,
            actingIndex: Int? = null,
            legal: LegalActions? = null,
            handLabel: String? = "Pair of aces",
        ): TableUiState.Active = TableUiState.Active(
            street = street,
            communityCards = community,
            pot = pot,
            potCommittedThisStreet = potCommitted,
            seats = seats,
            actingSeatIndex = actingIndex,
            isHumanTurn = actingIndex == HUMAN_INDEX,
            humanLegalActions = legal,
            humanHandLabel = handLabel,
            handResult = null,
            smallBlind = SB,
            bigBlind = BB,
            handNumber = 1,
            buttonSeatIndex = HUMAN_INDEX,
            smallBlindSeatIndex = ADA_INDEX,
            bigBlindSeatIndex = BEN_INDEX,
        )

        return listOf(
            // 1 — Orient (no actions enabled)
            TutorialStep(
                state = baseState(
                    table(
                        seats = seats(),
                        pot = SB + BB,
                        potCommitted = SB + BB,
                    )
                ),
                coach = CoachMark(
                    title = "Welcome to the table",
                    body = "These are your opponents — Ada, Ben, and Cleo. They're bots, and no real chips are at stake. Let's play a hand.",
                    ctaLabel = "Got it",
                    // Opponents sit at the top of the felt; pin the mark
                    // to the bottom so the user can see who they're being
                    // introduced to.
                    placement = CoachMarkPlacement.Bottom,
                ),
            ),
            // 2 — Blinds explainer
            TutorialStep(
                state = baseState(
                    table(
                        seats = seats(),
                        pot = SB + BB,
                        potCommitted = SB + BB,
                    )
                ),
                coach = CoachMark(
                    title = "Small Blind, Big Blind",
                    body = "Every hand starts with two forced bets — SB and BB. They build the pot before anyone acts. At this table SB is 10 and BB is 20, but real tables use different amounts depending on the stake tier you sit down at. Look at Ada and Ben.",
                    ctaLabel = "Next",
                    // Step explicitly says "Look at Ada and Ben" — keep
                    // their chip contributions visible at the top.
                    placement = CoachMarkPlacement.Bottom,
                ),
            ),
            // 3 — Hole cards explainer
            TutorialStep(
                state = baseState(
                    table(
                        seats = seats(),
                        pot = SB + BB,
                        potCommitted = SB + BB,
                    )
                ),
                coach = CoachMark(
                    title = "Your cards",
                    body = "Two aces — the strongest starting hand in poker. You're a clear favorite to win this pot.",
                    ctaLabel = "Nice",
                ),
            ),
            // 4 — Prompt to raise
            TutorialStep(
                state = baseState(
                    table(
                        seats = seats(humanActing = true),
                        pot = SB + BB,
                        potCommitted = SB + BB,
                        actingIndex = HUMAN_INDEX,
                        legal = LegalActions(
                            canCheck = false,
                            canCall = true,
                            callAmount = BB,
                            canRaise = true,
                            isOpenBet = false,
                            minRaiseTotal = 60L,
                            maxRaiseTotal = 10_000L,
                            canAllIn = true,
                            allInAmount = 10_000L,
                            potIfYouCall = SB + BB + BB,
                        ),
                    )
                ),
                coach = CoachMark(
                    title = "Raise",
                    body = "When you have the best of it, raise to build the pot. Tap Raise to put more chips in.",
                    ctaLabel = null,
                ),
                advanceOn = { it is PlayerIntent.Raise || it is PlayerIntent.Bet },
            ),
            // 5 — Result explainer
            TutorialStep(
                state = baseState(
                    table(
                        seats = seats(
                            humanContributed = 60L,
                            adaContributed = 60L,
                            adaLastAction = PlayerAction.Call(50L),
                            benContributed = 60L,
                            benLastAction = PlayerAction.Call(40L),
                            cleoLastAction = PlayerAction.Fold,
                            cleoFolded = true,
                            humanLastAction = PlayerAction.Raise(totalStreetContribution = 60L, raiseAmount = 40L),
                        ),
                        pot = SB + BB + 60L + 60L + 60L - SB - BB,
                        potCommitted = 180L,
                    )
                ),
                coach = CoachMark(
                    title = "Now there's something to play for",
                    body = "Both bots called (matched your raise of 60). The pot's a real pot now. Raising with strong hands is how you make chips over the long run.",
                    ctaLabel = "Next hand",
                    // Talks about the bots' reactions + the pot —
                    // bottom-pin so the opponents and pot stay visible.
                    placement = CoachMarkPlacement.Bottom,
                ),
            ),
        )
    }

    // ------------------------------------------------------------------
    // Hand 2 — Call when the price is right (KQ suited, then check on flop)
    // ------------------------------------------------------------------
    private fun handTwo(): List<TutorialStep> {
        val holeCards = listOf(
            Card(Rank.King, Suit.Hearts),
            Card(Rank.Queen, Suit.Hearts),
        )

        fun seats(
            humanContributed: Long = SB,
            adaContributed: Long = BB,
            benContributed: Long = 0,
            cleoContributed: Long = 0,
            adaLastAction: PlayerAction? = null,
            benLastAction: PlayerAction? = PlayerAction.Fold,
            cleoLastAction: PlayerAction? = PlayerAction.Fold,
            benFolded: Boolean = true,
            cleoFolded: Boolean = true,
            humanLastAction: PlayerAction? = null,
            humanActing: Boolean = false,
        ): List<SeatView> = listOf(
            human(
                holeCards = holeCards,
                stack = 10_000L - humanContributed,
                contributed = humanContributed,
                isActing = humanActing,
                isSmallBlind = true,
                lastAction = humanLastAction,
            ),
            bot(
                index = ADA_INDEX,
                name = "Ada",
                emoji = "🦊",
                stack = 10_000L - adaContributed,
                contributed = adaContributed,
                isBigBlind = true,
                lastAction = adaLastAction,
            ),
            bot(
                index = BEN_INDEX,
                name = "Ben",
                emoji = "💀",
                stack = 10_000L - benContributed,
                contributed = benContributed,
                participation = if (benFolded) HandParticipation.Folded else HandParticipation.InHand,
                lastAction = benLastAction,
            ),
            bot(
                index = CLEO_INDEX,
                name = "Cleo",
                emoji = "🐉",
                stack = 10_000L - cleoContributed,
                contributed = cleoContributed,
                isDealer = true,
                participation = if (cleoFolded) HandParticipation.Folded else HandParticipation.InHand,
                lastAction = cleoLastAction,
            ),
        )

        fun table(
            seats: List<SeatView>,
            pot: Long,
            potCommitted: Long,
            community: List<Card> = emptyList(),
            street: BettingRound = BettingRound.Preflop,
            actingIndex: Int? = null,
            legal: LegalActions? = null,
            handLabel: String? = null,
        ): TableUiState.Active = TableUiState.Active(
            street = street,
            communityCards = community,
            pot = pot,
            potCommittedThisStreet = potCommitted,
            seats = seats,
            actingSeatIndex = actingIndex,
            isHumanTurn = actingIndex == HUMAN_INDEX,
            humanLegalActions = legal,
            humanHandLabel = handLabel,
            handResult = null,
            smallBlind = SB,
            bigBlind = BB,
            handNumber = 2,
            buttonSeatIndex = CLEO_INDEX,
            smallBlindSeatIndex = HUMAN_INDEX,
            bigBlindSeatIndex = ADA_INDEX,
        )

        val flop = listOf(
            Card(Rank.Two, Suit.Hearts),
            Card(Rank.Seven, Suit.Hearts),
            Card(Rank.Jack, Suit.Clubs),
        )
        val turnAndRiver = flop + listOf(
            Card(Rank.Four, Suit.Hearts),
            Card(Rank.Ten, Suit.Spades),
        )

        return listOf(
            // 1 — Prompt to call (cheap completion)
            TutorialStep(
                state = baseState(
                    table(
                        seats = seats(humanActing = true),
                        pot = SB + BB,
                        potCommitted = SB + BB,
                        actingIndex = HUMAN_INDEX,
                        legal = LegalActions(
                            canCheck = false,
                            canCall = true,
                            callAmount = BB - SB,
                            canRaise = true,
                            isOpenBet = false,
                            minRaiseTotal = BB * 2,
                            maxRaiseTotal = 10_000L - SB,
                            canAllIn = true,
                            allInAmount = 10_000L - SB,
                            potIfYouCall = BB + BB,
                        ),
                        handLabel = "King-Queen suited",
                    )
                ),
                coach = CoachMark(
                    title = "Call when it's cheap",
                    body = "King-Queen suited. Not a monster, but worth seeing the flop (the first three shared cards everyone gets to use). It only costs 10 to match Ada's bet — tap Call.",
                    ctaLabel = null,
                ),
                advanceOn = { it is PlayerIntent.Call },
            ),
            // 2 — Flop comes; prompt to check
            TutorialStep(
                state = baseState(
                    table(
                        seats = seats(
                            humanContributed = 0,
                            adaContributed = 0,
                            humanLastAction = null,
                            adaLastAction = null,
                            humanActing = true,
                        ),
                        pot = BB * 2,
                        potCommitted = 0,
                        community = flop,
                        street = BettingRound.Flop,
                        actingIndex = HUMAN_INDEX,
                        legal = LegalActions(
                            canCheck = true,
                            canCall = false,
                            callAmount = 0,
                            canRaise = true,
                            isOpenBet = true,
                            minRaiseTotal = BB,
                            maxRaiseTotal = 10_000L - BB,
                            canAllIn = true,
                            allInAmount = 10_000L - BB,
                            potIfYouCall = BB * 2,
                        ),
                        handLabel = "Flush draw, king-high",
                    )
                ),
                coach = CoachMark(
                    title = "Check for free",
                    body = "Two hearts on the flop — if a third heart comes, you'll have a flush (5 cards of the same suit, a strong hand). Ada checked. You don't have to bet either — tap Check to see the next card for free.",
                    ctaLabel = null,
                ),
                advanceOn = { it is PlayerIntent.Check },
            ),
            // 3 — Result + lesson
            TutorialStep(
                state = baseState(
                    table(
                        seats = seats(
                            humanContributed = 0,
                            adaContributed = 0,
                            adaLastAction = PlayerAction.Fold,
                            humanLastAction = PlayerAction.Check,
                        ).mapIndexed { i, s ->
                            if (i == ADA_INDEX) s.copy(participation = HandParticipation.Folded)
                            else s
                        },
                        pot = BB * 2,
                        potCommitted = 0,
                        community = turnAndRiver,
                        street = BettingRound.River,
                        handLabel = "Flush, king-high",
                    )
                ),
                coach = CoachMark(
                    title = "Hearts everywhere",
                    body = "The turn (4th shared card) brought another heart — that's three hearts plus your two, you've made your flush. Ada folded (gave up the hand). Calling cheap and checking free is how you win without bloating the pot.",
                    ctaLabel = "Next hand",
                    // Mentions Ada folding + the community cards which
                    // sit mid-table. Bottom-pin keeps both visible.
                    placement = CoachMarkPlacement.Bottom,
                ),
            ),
        )
    }

    // ------------------------------------------------------------------
    // Hand 3 — Fold when you're beat (7-2 offsuit vs a raise)
    // ------------------------------------------------------------------
    private fun handThree(): List<TutorialStep> {
        val holeCards = listOf(
            Card(Rank.Seven, Suit.Clubs),
            Card(Rank.Two, Suit.Diamonds),
        )

        fun seats(humanActing: Boolean = false): List<SeatView> = listOf(
            human(
                holeCards = holeCards,
                stack = 10_000L - BB,
                contributed = BB,
                isActing = humanActing,
                isBigBlind = true,
            ),
            bot(
                index = ADA_INDEX,
                name = "Ada",
                emoji = "🦊",
                stack = 10_000L - SB,
                contributed = SB,
                isSmallBlind = true,
                lastAction = PlayerAction.Fold,
                participation = HandParticipation.Folded,
            ),
            bot(
                index = BEN_INDEX,
                name = "Ben",
                emoji = "💀",
                stack = 10_000L - 60L,
                contributed = 60L,
                isDealer = true,
                lastAction = PlayerAction.Raise(totalStreetContribution = 60L, raiseAmount = 40L),
            ),
            bot(
                index = CLEO_INDEX,
                name = "Cleo",
                emoji = "🐉",
                stack = 10_000L,
                contributed = 0,
                lastAction = PlayerAction.Fold,
                participation = HandParticipation.Folded,
            ),
        )

        val potBeforeFold = SB + BB + 60L
        val tableAction = TableUiState.Active(
            street = BettingRound.Preflop,
            communityCards = emptyList(),
            pot = potBeforeFold,
            potCommittedThisStreet = potBeforeFold,
            seats = seats(humanActing = true),
            actingSeatIndex = HUMAN_INDEX,
            isHumanTurn = true,
            humanLegalActions = LegalActions(
                canCheck = false,
                canCall = true,
                callAmount = 40L,
                canRaise = true,
                isOpenBet = false,
                minRaiseTotal = 120L,
                maxRaiseTotal = 10_000L - BB,
                canAllIn = true,
                allInAmount = 10_000L - BB,
                potIfYouCall = potBeforeFold + 40L,
            ),
            humanHandLabel = "Seven-deuce offsuit",
            handResult = null,
            smallBlind = SB,
            bigBlind = BB,
            handNumber = 3,
            buttonSeatIndex = BEN_INDEX,
            smallBlindSeatIndex = ADA_INDEX,
            bigBlindSeatIndex = HUMAN_INDEX,
        )

        return listOf(
            TutorialStep(
                // Silent swipe-fold so the swipe gesture commits immediately
                // — no confirmation dialog interrupts the lesson.
                state = baseState(tableAction).copy(swipeFoldGestureAck = true),
                coach = CoachMark(
                    title = "Fold when you're beat",
                    body = "Seven-two offsuit — the worst hand in poker. Ben raised to 60. Don't put more chips in with junk. Tap ↑ then Fold, or swipe up on your cards.",
                    ctaLabel = null,
                ),
                advanceOn = { it is PlayerIntent.Fold },
            ),
            TutorialStep(
                state = baseState(
                    TableUiState.Active(
                        street = BettingRound.Preflop,
                        communityCards = emptyList(),
                        pot = potBeforeFold,
                        potCommittedThisStreet = potBeforeFold,
                        seats = seats().mapIndexed { i, s ->
                            if (i == HUMAN_INDEX) s.copy(
                                participation = HandParticipation.Folded,
                                lastAction = PlayerAction.Fold,
                            ) else s
                        },
                        actingSeatIndex = null,
                        isHumanTurn = false,
                        humanLegalActions = null,
                        humanHandLabel = null,
                        handResult = null,
                        smallBlind = SB,
                        bigBlind = BB,
                        handNumber = 3,
                        buttonSeatIndex = BEN_INDEX,
                        smallBlindSeatIndex = ADA_INDEX,
                        bigBlindSeatIndex = HUMAN_INDEX,
                    )
                ),
                coach = CoachMark(
                    title = "Folding saves money",
                    body = "Most hands aren't worth playing. Folding the bad ones quickly is the most profitable habit in poker. You're ready for the real tables.",
                    ctaLabel = "Done",
                ),
            ),
        )
    }

    // ------------------------------------------------------------------
    // Builders
    // ------------------------------------------------------------------

    private fun baseState(table: TableUiState.Active): PlayPokerState = PlayPokerState(
        table = table,
        // Hide the level-pill XP ticker — there's no real progression
        // happening during the tutorial.
        humanLevel = null,
        xp = 0,
    )

    private fun human(
        holeCards: List<Card>,
        stack: Long,
        contributed: Long,
        isActing: Boolean,
        isDealer: Boolean = false,
        isSmallBlind: Boolean = false,
        isBigBlind: Boolean = false,
        lastAction: PlayerAction? = null,
        participation: HandParticipation = HandParticipation.InHand,
    ): SeatView = SeatView(
        index = HUMAN_INDEX,
        displayName = "You",
        stack = stack,
        contributedThisStreet = contributed,
        isActing = isActing,
        isHuman = true,
        isBot = false,
        avatarKey = null,
        emoji = "🐱",
        avatarBackgroundColorHex = "#a18bff",
        holeCards = holeCards,
        showHoleCardBacks = false,
        participation = participation,
        seatEmpty = false,
        isBusted = false,
        lastAction = lastAction,
        isDealer = isDealer,
        isSmallBlind = isSmallBlind,
        isBigBlind = isBigBlind,
    )

    private fun bot(
        index: Int,
        name: String,
        emoji: String,
        stack: Long,
        contributed: Long,
        isDealer: Boolean = false,
        isSmallBlind: Boolean = false,
        isBigBlind: Boolean = false,
        lastAction: PlayerAction? = null,
        participation: HandParticipation = HandParticipation.InHand,
    ): SeatView = SeatView(
        index = index,
        displayName = name,
        stack = stack,
        contributedThisStreet = contributed,
        isActing = false,
        isHuman = false,
        isBot = true,
        avatarKey = "avatar_$name",
        emoji = emoji,
        avatarBackgroundColorHex = null,
        // Bots always show backs during in-hand; engine flips them on
        // showdown via revealedHoleCards. We don't do showdown in the
        // tutorial, so they stay face-down throughout.
        holeCards = emptyList(),
        showHoleCardBacks = participation == HandParticipation.InHand,
        participation = participation,
        seatEmpty = false,
        isBusted = false,
        lastAction = lastAction,
        isDealer = isDealer,
        isSmallBlind = isSmallBlind,
        isBigBlind = isBigBlind,
    )
}
