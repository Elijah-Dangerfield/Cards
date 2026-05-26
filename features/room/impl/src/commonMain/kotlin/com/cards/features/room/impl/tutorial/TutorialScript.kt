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
    // Intro: five foundational narration cards. Assumes ZERO poker
    // knowledge. Each marked `isBasics = true` so experienced players can
    // skip the whole block in two taps. No fabricated table behind these,
    // just centered explainers with a hero glyph.
    //
    // Order is intentional: goal -> verbs -> how a hand unfolds (streets)
    // -> who acts when (position + blinds) -> who wins (hand ranks). Each
    // card builds on the previous: you can't explain "the flop" until the
    // player knows the verbs they'll use on it, and ranking matters most
    // at the end when you compare hands.
    // ------------------------------------------------------------------
    private fun intro(): List<TutorialStep> = listOf(
        TutorialStep(
            section = TutorialSection.Basics,
            heroGlyph = "🎯",
            coach = CoachMark(
                title = "The goal: win the pot",
                body = "The pot is the pile of chips in the middle of the table. Every hand, players fight to claim it.",
                bullets = listOf(
                    "Make the best hand by the end of the hand.",
                    "Or scare everyone else into folding; you win uncontested.",
                ),
                ctaLabel = "Got it",
            ),
        ),
        TutorialStep(
            section = TutorialSection.Basics,
            heroGlyph = "💰",
            coach = CoachMark(
                title = "How the pot grows",
                body = "Players take turns putting chips in. Four actions:",
                bullets = listOf(
                    "Call: match the current bet to stay in.",
                    "Raise: put in more, forcing others to match or quit.",
                    "Check: pass for free. Only legal when nobody has bet yet.",
                    "Fold: give up the hand. Save your remaining chips for a better spot.",
                ),
                ctaLabel = "Makes sense",
            ),
        ),
        TutorialStep(
            section = TutorialSection.Basics,
            heroGlyph = "🃏",
            coach = CoachMark(
                title = "How a hand unfolds",
                body = "Each hand plays out over four rounds. New community cards reveal between rounds, and betting happens after each reveal:",
                bullets = listOf(
                    "Preflop: you get two private cards (your hole cards). First bet.",
                    "Flop: three shared cards land in the middle. Second bet.",
                    "Turn: a fourth shared card. Third bet.",
                    "River: the fifth and final shared card. Last bet.",
                    "If you survive to the end, hands are compared and the best 5-card combo wins.",
                ),
                ctaLabel = "Got it",
            ),
        ),
        TutorialStep(
            section = TutorialSection.Basics,
            heroGlyph = "🔁",
            coach = CoachMark(
                title = "Who acts when",
                body = "Each hand has a dealer seat marked with a small button. Two seats to the dealer's left post forced bets to seed the pot:",
                bullets = listOf(
                    "Small Blind (SB): one seat left of the dealer.",
                    "Big Blind (BB): two seats left of the dealer, double the SB.",
                    "Action starts left of the BB and moves clockwise.",
                    "Each new hand, the dealer button shifts one seat left, so everyone takes turns posting.",
                ),
                ctaLabel = "Got it",
            ),
        ),
        TutorialStep(
            section = TutorialSection.Basics,
            heroGlyph = "🏆",
            coach = CoachMark(
                title = "Best 5-card hand wins",
                body = "If multiple players survive to the river, the strongest 5-card hand (made from your two hole cards plus the five shared cards) wins the pot. Ranks weakest to strongest:",
                bullets = listOf(
                    "Pair, two pair, three of a kind.",
                    "Straight, flush, full house.",
                    "Four of a kind, straight flush, royal flush.",
                    "Tap the ? on the live table anytime for the full chart.",
                ),
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
    // Hand 1, Raise when you're strong (pocket aces)
    // ------------------------------------------------------------------
    private fun handOne(): List<TutorialStep> {
        val holeCards = listOf(
            Card(Rank.Ace, Suit.Spades),
            Card(Rank.Ace, Suit.Hearts),
        )

        // Opponents seeded with blinds posted; human has the button.
        // Human first-to-act preflop (heads-up convention: SB acts first
        // preflop AND postflop, so use 4-handed positioning instead, button
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
            // 1, Orient (no actions enabled)
            TutorialStep(
                state = baseState(
                    table(
                        seats = seats(),
                        pot = SB + BB,
                        potCommitted = SB + BB,
                    )
                ),
                coach = CoachMark(
                    title = "Here's where things live",
                    body = "Your hole cards at the bottom (yours). Community cards land in the middle as the hand plays out. Opponents up top, with their chip stacks and last action shown next to each seat. The pot total floats between them.",
                    ctaLabel = "Got it",
                    // Whole-table orientation; bottom-pin so the user
                    // can scan all three regions while reading.
                    placement = CoachMarkPlacement.Bottom,
                ),
            ),
            // 2, Blinds explainer
            TutorialStep(
                state = baseState(
                    table(
                        seats = seats(),
                        pot = SB + BB,
                        potCommitted = SB + BB,
                    )
                ),
                coach = CoachMark(
                    title = "The blinds are already in",
                    body = "Ada posted the small blind (10) and Ben posted the big blind (20). Their chip contributions are visible next to their seats. The pot has been seeded before anyone has had a real choice.",
                    ctaLabel = "Next",
                    // Pointing at Ada and Ben's chip contributions
                    // at the top of the felt.
                    placement = CoachMarkPlacement.Bottom,
                ),
            ),
            // 3, Hole cards explainer
            TutorialStep(
                state = baseState(
                    table(
                        seats = seats(),
                        pot = SB + BB,
                        potCommitted = SB + BB,
                    )
                ),
                coach = CoachMark(
                    title = "Your hand label",
                    body = "Your two hole cards sit at the bottom. The label above them tells you what hand you currently have. Right now: a pair of aces. As community cards land, the label updates to reflect your best 5-card combo. Tap the ? in the top bar anytime for the full rank chart.",
                    ctaLabel = "Got it",
                ),
            ),
            // 4, Prompt to raise
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
                    title = "The action bar",
                    body = "When it's your turn, the action bar appears at the bottom with the moves you're allowed to make. Tap Raise. The ↑ on the Raise button opens a sheet with bet-size controls; tap the button itself for the minimum raise.",
                    ctaLabel = null,
                ),
                advanceOn = { it is PlayerIntent.Raise || it is PlayerIntent.Bet },
            ),
            // 5, Result explainer
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
                    title = "Watch the seats update",
                    body = "Both bots called. Each seat shows the player's last action next to their name (Call, Raise, Fold, etc.), so you can always glance up and see what just happened. The pot total in the middle ticked up with the new chips.",
                    ctaLabel = "Next hand",
                    // Pointing at the seat labels + pot in the middle,
                    // both at the top half of the screen.
                    placement = CoachMarkPlacement.Bottom,
                ),
            ),
        )
    }

    // ------------------------------------------------------------------
    // Hand 2, Call when the price is right (KQ suited, then check on flop)
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
            // 1, Prompt to call (cheap completion)
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
                    title = "Calling matches the bet",
                    body = "New hand: the dealer button has shifted left and you're the small blind now. To stay in for the flop you need to match Ada's bet for 10 more chips. Tap Call.",
                    ctaLabel = null,
                ),
                advanceOn = { it is PlayerIntent.Call },
            ),
            // 2, Flop comes; prompt to check
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
                    title = "The flop, and checking",
                    body = "The flop has landed in the middle: three community cards everyone can use. Your hand label above your cards updated to reflect them. Ada checked (passed without betting). When no one has bet, Check is legal for you too. Tap Check to advance to the next street.",
                    ctaLabel = null,
                ),
                advanceOn = { it is PlayerIntent.Check },
            ),
            // 3, Result + lesson
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
                    title = "How a hand ends",
                    body = "The turn and river dealt out. Ada folded along the way, so you win the pot uncontested. Your hand label settled on its final value. Most hands won't reach showdown; folds are how a hand usually ends.",
                    ctaLabel = "Next hand",
                    // Mentions Ada folding + the community cards which
                    // sit mid-table. Bottom-pin keeps both visible.
                    placement = CoachMarkPlacement.Bottom,
                ),
            ),
        )
    }

    // ------------------------------------------------------------------
    // Hand 3, Fold when you're beat (7-2 offsuit vs a raise)
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
                //, no confirmation dialog interrupts the lesson.
                state = baseState(tableAction).copy(swipeFoldGestureAck = true),
                coach = CoachMark(
                    title = "Two ways to fold",
                    body = "Two ways to fold here. Tap ↑ on the action bar to open the full action sheet, then tap Fold. Or, faster: swipe up on your hole cards to fold with a gesture. Either works. Pick one.",
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
                    title = "You know the chrome",
                    body = "That's the table chrome covered. Action bar at the bottom, opponents and seat labels up top, hand label above your cards, ? button for hand ranks. The bots are waiting in Practice when you're ready.",
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
        // Hide the level-pill XP ticker, there's no real progression
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
