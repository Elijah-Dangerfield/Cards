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
    // Basics: three narration cards. Compact by design; verbose primers
    // bounce beginners. Each card has a bespoke hero illustration (chip
    // stack, rank cards, action list) rather than an emoji, and a serif
    // italic headline anchored to the bottom of the screen.
    //
    // Order: pot (what you're after) -> hands (how the winner is decided)
    // -> actions (how you participate). Each card builds on the previous.
    // ------------------------------------------------------------------
    private fun intro(): List<TutorialStep> = listOf(
        TutorialStep(
            section = TutorialSection.Basics,
            hero = NarrationHero.Pot,
            coach = CoachMark(
                title = "Win the pot.",
                body = "Every hand, players bet chips into a shared pot. Win the hand and you take it all, either by having the best cards at showdown, or by getting everyone else to fold first.",
                ctaLabel = "Got it",
            ),
        ),
        TutorialStep(
            section = TutorialSection.Basics,
            hero = NarrationHero.HandRanks,
            coach = CoachMark(
                title = "Better hands beat worse ones.",
                body = "Your hand is your two cards combined with five shared cards on the table. The strongest five-card combination wins. We'll show you the ranking in-game whenever you need it.",
                ctaLabel = "Got it",
            ),
        ),
        TutorialStep(
            section = TutorialSection.Basics,
            hero = NarrationHero.Actions,
            coach = CoachMark(
                title = "Three things to do on your turn.",
                body = "When it's your action, you have three choices. Fold and you're out. Call to stay in. Raise to put pressure on. That's the whole game, done thousands of different ways.",
                ctaLabel = "Try a hand",
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
                    title = "Let's play a hand",
                    body = "Meet Ada, Ben, and Cleo. They're bots. Tap anything on this screen if you're curious; most things will tell you what they are.",
                    ctaLabel = "Got it",
                    // Middle placement so we don't cover either the
                    // opponents row or the hole-card area while the
                    // user gets oriented.
                    placement = CoachMarkPlacement.Middle,
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
                    title = "Blinds are in",
                    body = "Ada posted the small blind, Ben the big. The pot already has chips in it before anyone has had a real choice.",
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
                    title = "Pocket aces",
                    body = "Nice start. The label on your cards shows what you have right now; it updates as community cards reveal. Tap your cards to flip and see the back.",
                    ctaLabel = "Got it",
                    // Cards are at the bottom; pin the mark up top so
                    // it doesn't cover what we're talking about.
                    placement = CoachMarkPlacement.Top,
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
                    title = "Your move",
                    body = "Tap Raise to put chips in. The ↑ opens a sheet for sizing the bet; the button itself raises the minimum.",
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
                    title = "They called",
                    body = "Each player's last move shows next to their seat. The pot ticked up.",
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
                    title = "King-Queen suited",
                    body = "Decent hand: same suit, both high cards. Straight, flush, even a royal flush are all on the table. 10 chips to call and see the flop.",
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
                    title = "Flush draw",
                    body = "Two hearts on the flop plus your two: one more heart and you've made a flush. Ada checked, so you can see the next card free. Tap Check.",
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
                    title = "Hearts everywhere",
                    body = "Turn brought another heart, you made your flush. Ada folded along the way, so the pot is yours. Most hands end like this, not at showdown.",
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
                    title = "Fold this one",
                    body = "Tap ↑ then Fold, or swipe up on your cards. Either works.",
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
                    title = "Ready",
                    body = "Tap anything on a real table you're not sure about; most things will explain themselves. Bots are waiting in Practice.",
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
