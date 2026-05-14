package com.dangerfield.cards.libraries.gameplay

import kotlin.random.Random

data class StepResult(
    val state: GameState,
    val events: List<GameEvent>,
)

object GameEngine {

    fun startHand(
        settings: RoomSettings,
        seats: List<Seat>,
        handNumber: Int,
        buttonSeatIndex: Int,
        deck: Deck,
    ): StepResult {
        val eligibleSeats = seats.filter { it.seatStatus == SeatStatus.Active && it.stack > 0 }
        require(eligibleSeats.size >= 2) { "Need at least 2 active seats with chips to start a hand" }

        val orderedSeats = seats.sortedBy { it.index }
        val resetSeats = orderedSeats.map { seat ->
            if (seat.seatStatus == SeatStatus.Active && seat.stack > 0) {
                seat.copy(
                    handParticipation = HandParticipation.InHand,
                    contributedThisStreet = 0,
                    contributedThisHand = 0,
                    holeCards = emptyList(),
                    hasActedThisStreet = false,
                )
            } else {
                seat.copy(
                    handParticipation = HandParticipation.NotDealt,
                    contributedThisStreet = 0,
                    contributedThisHand = 0,
                    holeCards = emptyList(),
                    hasActedThisStreet = false,
                )
            }
        }

        val active = resetSeats.filter { it.handParticipation == HandParticipation.InHand }
        val (sbSeat, bbSeat) = pickBlindSeats(active, buttonSeatIndex)

        val events = mutableListOf<GameEvent>()
        var seq = 0L
        events += GameEvent.HandStarted(++seq, handNumber, buttonSeatIndex)

        var seatList = resetSeats
        val sbAmount = minOf(settings.smallBlind, sbSeat.stack)
        seatList = updateSeat(seatList, sbSeat.index) { applyChipsToStreet(it, sbAmount) }
        events += GameEvent.BlindPosted(++seq, sbSeat.index, sbAmount, isSmall = true)

        val bbAmount = minOf(settings.bigBlind, bbSeat.stack)
        seatList = updateSeat(seatList, bbSeat.index) { applyChipsToStreet(it, bbAmount) }
        events += GameEvent.BlindPosted(++seq, bbSeat.index, bbAmount, isSmall = false)

        seatList = seatList.map { seat ->
            if (seat.handParticipation != HandParticipation.InHand && seat.handParticipation != HandParticipation.AllIn) seat
            else {
                val card1 = deck.draw()
                val card2 = deck.draw()
                seat.copy(holeCards = listOf(card1, card2))
            }
        }
        for (seat in seatList) {
            if (seat.holeCards.size == 2) {
                events += GameEvent.HoleCardsDealt(++seq, seat.index, seat.holeCards)
            }
        }

        val firstToAct = firstToActPreflop(seatList, buttonSeatIndex)
            ?: error("No player can act preflop")

        val state = GameState(
            settings = settings,
            handNumber = handNumber,
            buttonSeatIndex = buttonSeatIndex,
            seats = seatList,
            community = emptyList(),
            street = BettingRound.Preflop,
            currentBetThisStreet = settings.bigBlind,
            lastFullRaiseSize = settings.bigBlind,
            actingSeatIndex = firstToAct.index,
            deckRemaining = deck.snapshot(),
            pots = emptyList(),
            lastSequence = seq,
        )

        events += GameEvent.StreetAdvanced(++seq, BettingRound.Preflop, emptyList())

        return StepResult(state.copy(lastSequence = seq), events)
    }

    fun applyIntent(state: GameState, intent: PlayerIntent): StepResult {
        require(state.street != BettingRound.Complete) { "Hand is already complete" }
        require(state.actingSeatIndex == intent.seatIndex) {
            "Not seat ${intent.seatIndex}'s turn (expected ${state.actingSeatIndex})"
        }
        val seat = state.seatAt(intent.seatIndex)
        require(seat.canAct) { "Seat ${seat.index} cannot act (status=${seat.handParticipation}, stack=${seat.stack})" }

        val events = mutableListOf<GameEvent>()
        var seq = state.lastSequence
        var seats = state.seats
        var currentBet = state.currentBetThisStreet
        var lastFullRaise = state.lastFullRaiseSize

        val (newSeat, action) = resolveAction(seat, intent, currentBet, lastFullRaise)

        seats = seats.map { if (it.index == seat.index) newSeat else it }

        events += GameEvent.ActionTaken(
            sequence = ++seq,
            seatIndex = seat.index,
            action = action,
            resultingStreetContribution = newSeat.contributedThisStreet,
        )

        when (action) {
            is PlayerAction.Bet -> {
                lastFullRaise = action.amount
                currentBet = newSeat.contributedThisStreet
                seats = clearActedFlagExcept(seats, seat.index)
            }
            is PlayerAction.Raise -> {
                lastFullRaise = action.raiseAmount
                currentBet = newSeat.contributedThisStreet
                seats = clearActedFlagExcept(seats, seat.index)
            }
            is PlayerAction.AllIn -> {
                if (newSeat.contributedThisStreet > currentBet) {
                    val raise = newSeat.contributedThisStreet - currentBet
                    if (raise >= lastFullRaise) {
                        lastFullRaise = raise
                        seats = clearActedFlagExcept(seats, seat.index)
                    }
                    currentBet = newSeat.contributedThisStreet
                }
            }
            else -> Unit
        }

        val working = state.copy(
            seats = seats,
            currentBetThisStreet = currentBet,
            lastFullRaiseSize = lastFullRaise,
            lastSequence = seq,
        )

        val activeInHand = working.activeContenders
        if (activeInHand.size == 1 && activeInHand.first().handParticipation != HandParticipation.AllIn) {
            val (winState, winEvents, winSeq) = awardPotByFold(working, activeInHand.first(), seq)
            return StepResult(winState.copy(lastSequence = winSeq), events + winEvents)
        }

        if (isStreetComplete(working)) {
            val (advanced, advancedEvents, advancedSeq) = advanceStreet(working, seq)
            return StepResult(advanced.copy(lastSequence = advancedSeq), events + advancedEvents)
        }

        val nextActor = nextActorAfter(working, seat.index)
        return StepResult(
            working.copy(actingSeatIndex = nextActor?.index),
            events,
        )
    }

    private fun pickBlindSeats(active: List<Seat>, buttonIndex: Int): Pair<Seat, Seat> {
        return if (active.size == 2) {
            val buttonSeat = active.firstOrNull { it.index == buttonIndex }
                ?: orderFromAfter(active, buttonIndex).first()
            val other = active.first { it.index != buttonSeat.index }
            buttonSeat to other
        } else {
            val orderedFromButton = orderFromAfter(active, buttonIndex)
            val sb = orderedFromButton.first()
            val bb = orderedFromButton[1]
            sb to bb
        }
    }

    private fun orderFromAfter(seats: List<Seat>, afterIndex: Int): List<Seat> {
        if (seats.isEmpty()) return emptyList()
        val sorted = seats.sortedBy { it.index }
        val firstIdx = sorted.indexOfFirst { it.index > afterIndex }.let { if (it < 0) 0 else it }
        return sorted.drop(firstIdx) + sorted.take(firstIdx)
    }

    private fun firstToActPreflop(seats: List<Seat>, buttonIndex: Int): Seat? {
        val active = seats.filter { it.canAct }
        if (active.isEmpty()) return null
        return if (active.size == 2) {
            active.firstOrNull { it.index == buttonIndex } ?: active.first()
        } else {
            val ordered = orderFromAfter(active, buttonIndex)
            ordered.getOrNull(2) ?: ordered.first()
        }
    }

    private fun firstToActPostflop(seats: List<Seat>, buttonIndex: Int): Seat? {
        val active = seats.filter { it.canAct }
        if (active.isEmpty()) return null
        return orderFromAfter(active, buttonIndex).first()
    }

    private fun applyChipsToStreet(seat: Seat, amount: Long): Seat {
        val actual = minOf(amount, seat.stack)
        val newStack = seat.stack - actual
        val newParticipation = if (newStack == 0L && seat.handParticipation == HandParticipation.InHand) {
            HandParticipation.AllIn
        } else seat.handParticipation
        return seat.copy(
            stack = newStack,
            contributedThisStreet = seat.contributedThisStreet + actual,
            contributedThisHand = seat.contributedThisHand + actual,
            handParticipation = newParticipation,
        )
    }

    /**
     * Translate a player intent into the resulting seat + action pair, with
     * all the poker-rule validation (min bet, min raise, can't check when
     * facing a bet, etc.) inline.
     *
     * The returned action is `PlayerAction.AllIn` whenever the move puts the
     * seat at zero chips, regardless of the original intent — so a "Call"
     * intent that empties the stack surfaces as an All-In to downstream code.
     */
    private fun resolveAction(
        seat: Seat,
        intent: PlayerIntent,
        currentBet: Long,
        lastFullRaise: Long,
    ): Pair<Seat, PlayerAction> {
        return when (intent) {
            is PlayerIntent.Fold -> {
                val newSeat = seat.copy(
                    handParticipation = HandParticipation.Folded,
                    hasActedThisStreet = true,
                )
                newSeat to PlayerAction.Fold
            }
            is PlayerIntent.Check -> {
                require(seat.contributedThisStreet == currentBet) {
                    "Cannot check: must call ${currentBet - seat.contributedThisStreet}"
                }
                val newSeat = seat.copy(hasActedThisStreet = true)
                newSeat to PlayerAction.Check
            }
            is PlayerIntent.Call -> {
                val toCall = currentBet - seat.contributedThisStreet
                require(toCall > 0) { "Nothing to call" }
                val actual = minOf(toCall, seat.stack)
                val updated = applyChipsToStreet(seat, actual).copy(hasActedThisStreet = true)
                val action = if (updated.handParticipation == HandParticipation.AllIn) {
                    PlayerAction.AllIn(actual)
                } else {
                    PlayerAction.Call(actual)
                }
                updated to action
            }
            is PlayerIntent.Bet -> {
                require(currentBet == 0L) { "Cannot bet when there's an open bet — raise instead" }
                require(intent.amount > 0) { "Bet amount must be positive" }
                val minBet = lastFullRaise
                require(intent.amount >= minBet || intent.amount == seat.stack) {
                    "Bet ${intent.amount} below minimum $minBet"
                }
                require(intent.amount <= seat.stack) { "Bet ${intent.amount} exceeds stack ${seat.stack}" }
                val updated = applyChipsToStreet(seat, intent.amount).copy(hasActedThisStreet = true)
                val action = if (updated.handParticipation == HandParticipation.AllIn) {
                    PlayerAction.AllIn(intent.amount)
                } else {
                    PlayerAction.Bet(intent.amount)
                }
                updated to action
            }
            is PlayerIntent.Raise -> {
                require(currentBet > 0) { "No bet to raise — bet first" }
                val total = intent.totalAmountThisStreet
                val raiseAmount = total - currentBet
                require(raiseAmount >= lastFullRaise || total == seat.contributedThisStreet + seat.stack) {
                    "Raise of $raiseAmount below minimum $lastFullRaise"
                }
                val toContribute = total - seat.contributedThisStreet
                require(toContribute > 0) { "Raise must increase your contribution" }
                require(toContribute <= seat.stack) { "Raise exceeds stack" }
                val updated = applyChipsToStreet(seat, toContribute).copy(hasActedThisStreet = true)
                val action = if (updated.handParticipation == HandParticipation.AllIn) {
                    PlayerAction.AllIn(toContribute)
                } else {
                    PlayerAction.Raise(updated.contributedThisStreet, raiseAmount)
                }
                updated to action
            }
            is PlayerIntent.AllIn -> {
                val toContribute = seat.stack
                require(toContribute > 0) { "Stack is empty" }
                val updated = applyChipsToStreet(seat, toContribute).copy(hasActedThisStreet = true)
                updated to PlayerAction.AllIn(toContribute)
            }
        }
    }

    private fun updateSeat(seats: List<Seat>, index: Int, transform: (Seat) -> Seat): List<Seat> {
        return seats.map { if (it.index == index) transform(it) else it }
    }

    private fun clearActedFlagExcept(seats: List<Seat>, exceptIndex: Int): List<Seat> {
        return seats.map { seat ->
            if (seat.index == exceptIndex) seat
            else if (seat.handParticipation == HandParticipation.InHand) seat.copy(hasActedThisStreet = false)
            else seat
        }
    }

    private fun isStreetComplete(state: GameState): Boolean {
        val ableToAct = state.seats.filter { it.canAct }
        if (ableToAct.isEmpty()) return true
        for (seat in ableToAct) {
            if (!seat.hasActedThisStreet) return false
            if (seat.contributedThisStreet < state.currentBetThisStreet) return false
        }
        return true
    }

    private fun nextActorAfter(state: GameState, seatIndex: Int): Seat? {
        val n = state.seats.size
        if (n == 0) return null
        val sorted = state.seats.sortedBy { it.index }
        val startIdx = sorted.indexOfFirst { it.index == seatIndex }
        if (startIdx < 0) return null
        var i = (startIdx + 1) % n
        while (i != startIdx) {
            val seat = sorted[i]
            if (seat.canAct && (!seat.hasActedThisStreet || seat.contributedThisStreet < state.currentBetThisStreet)) {
                return seat
            }
            i = (i + 1) % n
        }
        return null
    }

    private fun advanceStreet(state: GameState, seq: Long): Triple<GameState, List<GameEvent>, Long> {
        val events = mutableListOf<GameEvent>()
        var s = seq
        val pots = PotBuilder.buildPots(state.seats)
        val withPots = state.copy(pots = pots)
        val playersWithChipsInHand = withPots.seats.count {
            it.handParticipation == HandParticipation.InHand && it.stack > 0
        }
        val mustRunOut = playersWithChipsInHand < 2

        var current = withPots
        var nextStreet = nextStreetFrom(current.street)
        val resetSeats = current.seats.map { seat ->
            seat.copy(contributedThisStreet = 0, hasActedThisStreet = false)
        }
        current = current.copy(
            seats = resetSeats,
            currentBetThisStreet = 0,
            lastFullRaiseSize = current.settings.bigBlind,
        )

        var deckCards = ArrayDeque(current.deckRemaining)

        while (nextStreet != BettingRound.Showdown && nextStreet != BettingRound.Complete) {
            val needed = nextStreet.communityCardCount - current.community.size
            val newCommunity = current.community + List(needed) { deckCards.removeFirst() }
            current = current.copy(
                community = newCommunity,
                deckRemaining = deckCards.toList(),
                street = nextStreet,
            )
            events += GameEvent.StreetAdvanced(++s, nextStreet, newCommunity)
            if (mustRunOut) {
                nextStreet = nextStreetFrom(nextStreet)
                continue
            }
            val firstActor = firstToActPostflop(current.seats, current.buttonSeatIndex)
            if (firstActor != null) {
                return Triple(current.copy(actingSeatIndex = firstActor.index), events, s)
            }
            nextStreet = nextStreetFrom(nextStreet)
        }

        return runShowdown(current.copy(street = BettingRound.Showdown), events, s)
    }

    private fun nextStreetFrom(street: BettingRound): BettingRound = when (street) {
        BettingRound.Preflop -> BettingRound.Flop
        BettingRound.Flop -> BettingRound.Turn
        BettingRound.Turn -> BettingRound.River
        BettingRound.River -> BettingRound.Showdown
        BettingRound.Showdown -> BettingRound.Complete
        BettingRound.Complete -> BettingRound.Complete
    }

    private fun awardPotByFold(
        state: GameState,
        winner: Seat,
        seq: Long,
    ): Triple<GameState, List<GameEvent>, Long> {
        val events = mutableListOf<GameEvent>()
        var s = seq
        val pots = PotBuilder.buildPots(state.seats)
        val totalAmount = pots.sumOf { it.amount }
        val updatedSeats = state.seats.map {
            if (it.index == winner.index) it.copy(stack = it.stack + totalAmount) else it
        }
        events += GameEvent.PotAwarded(
            sequence = ++s,
            seatIndex = winner.index,
            amount = totalAmount,
            potIndex = 0,
            withCategory = null,
        )
        events += GameEvent.HandEnded(
            sequence = ++s,
            winners = listOf(HandWinner(winner.index, totalAmount, null, byFold = true)),
            board = state.community,
            revealedHoleCards = emptyMap(),
        )
        return Triple(
            state.copy(
                seats = updatedSeats,
                pots = pots,
                street = BettingRound.Complete,
                actingSeatIndex = null,
            ),
            events,
            s,
        )
    }

    private fun runShowdown(
        state: GameState,
        prior: List<GameEvent>,
        seq: Long,
    ): Triple<GameState, List<GameEvent>, Long> {
        val events = prior.toMutableList()
        var s = seq

        // `state.pots` is set by `advanceStreet` (the only caller) before we
        // ever reach Showdown — trust it.
        val pots = state.pots

        val ranks: Map<Int, HandRank> = state.seats.filter { it.isInHand && it.holeCards.size == 2 }
            .associate { seat ->
                seat.index to HandEvaluator.evaluate(seat.holeCards + state.community)
            }

        val winners = mutableListOf<HandWinner>()
        var seatsAfterAwards = state.seats
        pots.forEachIndexed { potIndex, pot ->
            val contenders = pot.eligibleSeatIndexes.mapNotNull { idx ->
                state.seats.firstOrNull { it.index == idx && it.isInHand }
            }
            if (contenders.isEmpty()) return@forEachIndexed
            val best = contenders.mapNotNull { ranks[it.index]?.let { r -> it to r } }
                .maxByOrNull { it.second }?.second ?: return@forEachIndexed
            val tied = contenders.mapNotNull { c -> ranks[c.index]?.let { c to it } }
                .filter { it.second.compareTo(best) == 0 }
                .map { it.first }
            val share = pot.amount / tied.size
            val remainder = pot.amount - share * tied.size
            val ordered = orderFromAfter(tied, state.buttonSeatIndex)
            for ((i, w) in ordered.withIndex()) {
                val extra = if (i < remainder) 1L else 0L
                val award = share + extra
                seatsAfterAwards = seatsAfterAwards.map {
                    if (it.index == w.index) it.copy(stack = it.stack + award) else it
                }
                events += GameEvent.PotAwarded(
                    sequence = ++s,
                    seatIndex = w.index,
                    amount = award,
                    potIndex = potIndex,
                    withCategory = ranks[w.index]?.category,
                )
                winners += HandWinner(w.index, award, ranks[w.index], byFold = false)
            }
        }

        val revealed = state.seats.filter { it.isInHand && it.holeCards.size == 2 }
            .associate { it.index to it.holeCards }

        events += GameEvent.HandEnded(
            sequence = ++s,
            winners = winners,
            board = state.community,
            revealedHoleCards = revealed,
        )

        return Triple(
            state.copy(
                seats = seatsAfterAwards,
                pots = pots,
                street = BettingRound.Complete,
                actingSeatIndex = null,
            ),
            events,
            s,
        )
    }
}

fun deterministicDeck(seed: Long): Deck = Deck.shuffled(Random(seed))
