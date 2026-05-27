package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.libraries.bots.BotPersonality
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.gameplay.GameEvent
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.HandEvaluator
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.HandWinner
import com.dangerfield.cards.libraries.gameplay.PlayerAction
import com.dangerfield.cards.libraries.gameplay.Seat
import com.dangerfield.cards.libraries.identity.profile.Profile

sealed interface TableUiState {
    data object Loading : TableUiState

    data class Active(
        val street: BettingRound,
        val communityCards: List<Card>,
        val pot: Long,
        val potCommittedThisStreet: Long,
        val seats: List<SeatView>,
        val actingSeatIndex: Int?,
        val isHumanTurn: Boolean,
        val humanLegalActions: LegalActions?,
        val humanHandLabel: String?,
        val handResult: HandResultView?,
        val smallBlind: Long,
        val bigBlind: Long,
        val handNumber: Int,
        val buttonSeatIndex: Int,
        val smallBlindSeatIndex: Int?,
        val bigBlindSeatIndex: Int?,
    ) : TableUiState

    companion object {
        fun fromGameState(
            gameState: GameState,
            humanSeatIndex: Int,
            personalitiesBySeat: Map<Int, BotPersonality>,
            lastWinners: GameEvent.HandEnded?,
            lastActionBySeat: Map<Int, PlayerAction>,
            humanProfile: Profile.Authenticated? = null,
            /**
             * Local user's derived level (`levelProgressFor(xp).level`).
             * Used to render the "Lvl N" pill below the human seat's avatar.
             * Null means progression hasn't loaded yet — pill is omitted.
             */
            humanLevel: Int? = null,
            /**
             * Difficulty label for bot seats — "Casual" / "Standard" /
             * "Challenging". Renders as "Bot · {label}" below each bot's
             * avatar. Null means we don't know the table difficulty (e.g.
             * MP table, where bots aren't seated); bot pills collapse to
             * just "Bot".
             */
            botDifficultyLabel: String? = null,
        ): Active {
            val committedThisStreet = gameState.seats.sumOf { it.contributedThisStreet }
            val pot = committedThisStreet + gameState.pots.sumOf { it.amount }
            val acting = gameState.actingSeatIndex
            val isHumanTurn = acting == humanSeatIndex
            val (sbIndex, bbIndex) = blindSeats(gameState)
            val seats = gameState.seats.map { seat ->
                val isHuman = seat.index == humanSeatIndex
                SeatView.fromSeat(
                    seat = seat,
                    isActing = seat.index == acting,
                    isHuman = isHuman,
                    personality = personalitiesBySeat[seat.index],
                    hideHoleCards = seat.index != humanSeatIndex && lastWinners == null,
                    revealedHoleCards = lastWinners?.revealedHoleCards?.get(seat.index),
                    lastAction = lastActionBySeat[seat.index],
                    isDealer = seat.index == gameState.buttonSeatIndex,
                    isSmallBlind = seat.index == sbIndex,
                    isBigBlind = seat.index == bbIndex,
                    street = gameState.street,
                    humanProfile = humanProfile,
                    seatBadge = badgeFor(
                        seat = seat,
                        isHuman = isHuman,
                        humanLevel = humanLevel,
                        botDifficultyLabel = botDifficultyLabel,
                    ),
                )
            }
            val humanSeat = gameState.seats.firstOrNull { it.index == humanSeatIndex }
            val legal = if (isHumanTurn && humanSeat != null) LegalActions.from(gameState, humanSeat) else null
            val humanHandLabel = humanSeat?.let { previewHandLabel(it.holeCards, gameState.community) }
            val result = lastWinners?.let { ev ->
                HandResultView(
                    winners = ev.winners,
                    board = ev.board,
                )
            }
            return Active(
                street = gameState.street,
                communityCards = gameState.community,
                pot = pot,
                potCommittedThisStreet = committedThisStreet,
                seats = seats,
                actingSeatIndex = acting,
                isHumanTurn = isHumanTurn,
                humanLegalActions = legal,
                humanHandLabel = humanHandLabel,
                handResult = result,
                smallBlind = gameState.settings.smallBlind,
                bigBlind = gameState.settings.bigBlind,
                handNumber = gameState.handNumber,
                buttonSeatIndex = gameState.buttonSeatIndex,
                smallBlindSeatIndex = sbIndex,
                bigBlindSeatIndex = bbIndex,
            )
        }

        /**
         * Compose the small badge that renders below an avatar on the play
         * screen — "Lvl 14" for the local human, "Bot · Standard" (or just
         * "Bot" when difficulty is unknown) for bots, null for empty seats
         * and remote humans whose level we don't yet have a source for.
         */
        private fun badgeFor(
            seat: Seat,
            isHuman: Boolean,
            humanLevel: Int?,
            botDifficultyLabel: String?,
        ): String? = when {
            seat.playerId == null -> null
            isHuman -> humanLevel?.let { "Lvl $it" }
            seat.isBot -> botDifficultyLabel?.let { "Bot · $it" } ?: "Bot"
            else -> null // remote human in MP — level plumbing arrives later
        }

        private fun blindSeats(state: GameState): Pair<Int?, Int?> {
            val active = state.seats.filter {
                it.handParticipation == HandParticipation.InHand ||
                    it.handParticipation == HandParticipation.AllIn ||
                    it.handParticipation == HandParticipation.Folded
            }
            if (active.size < 2) return null to null
            val sorted = active.sortedBy { it.index }
            val firstAfterIdx = sorted.indexOfFirst { it.index > state.buttonSeatIndex }
                .let { if (it < 0) 0 else it }
            val ordered = sorted.drop(firstAfterIdx) + sorted.take(firstAfterIdx)
            return if (active.size == 2) {
                ordered.firstOrNull { it.index != state.buttonSeatIndex }?.index to state.buttonSeatIndex
            } else {
                ordered.getOrNull(0)?.index to ordered.getOrNull(1)?.index
            }
        }
    }
}

data class SeatView(
    val index: Int,
    val displayName: String,
    val stack: Long,
    val contributedThisStreet: Long,
    val isActing: Boolean,
    val isHuman: Boolean,
    val isBot: Boolean,
    val avatarKey: String?,
    val emoji: String?,
    /**
     * Avatar background color (`#rrggbb`) for this seat. Currently only
     * populated for the human seat from the user's chosen palette swatch;
     * null seats (bots, pre-identity-load human) fall back to
     * [AvatarCircle]'s name-seeded hue so they still feel distinct.
     */
    val avatarBackgroundColorHex: String? = null,
    val holeCards: List<Card>,
    val showHoleCardBacks: Boolean,
    val participation: HandParticipation,
    val seatEmpty: Boolean,
    val isBusted: Boolean,
    val lastAction: PlayerAction?,
    val isDealer: Boolean,
    val isSmallBlind: Boolean,
    val isBigBlind: Boolean,
    /**
     * Tiny pill rendered below the avatar on the play screen — e.g.
     * "Lvl 14" for the local human, "Bot · Standard" for bots. Null
     * means the seat doesn't have a label to show (empty seat, remote
     * human pre-level-plumbing). See [TableUiState.Companion.badgeFor].
     */
    val seatBadge: String? = null,
    /**
     * Bot personality for this seat — `null` for the human seat and any
     * non-bot seat. Tap-an-opponent surfaces use it to render an archetype
     * descriptor ("Tight aggressive — …"); gameplay code path doesn't read
     * this — bots' decisions go through the engine's own personality map.
     */
    val personality: BotPersonality? = null,
) {
    companion object {
        fun fromSeat(
            seat: Seat,
            isActing: Boolean,
            isHuman: Boolean,
            personality: BotPersonality?,
            hideHoleCards: Boolean,
            revealedHoleCards: List<Card>?,
            lastAction: PlayerAction?,
            isDealer: Boolean,
            isSmallBlind: Boolean,
            isBigBlind: Boolean,
            street: BettingRound,
            humanProfile: Profile.Authenticated? = null,
            seatBadge: String? = null,
        ): SeatView {
            val visibleHole = when {
                seat.handParticipation == HandParticipation.NotDealt -> emptyList()
                isHuman -> seat.holeCards
                revealedHoleCards != null -> revealedHoleCards
                hideHoleCards -> emptyList()
                else -> seat.holeCards
            }
            val backs = !isHuman &&
                seat.handParticipation != HandParticipation.NotDealt &&
                seat.handParticipation != HandParticipation.Folded &&
                visibleHole.isEmpty()
            // Human seat carries the user's chosen display name + avatar
            // when identity is known. Bots keep their engine-side name +
            // personality emoji. The fallback is the engine seat's own
            // displayName so projection still works pre-identity-load.
            val displayName = if (isHuman && humanProfile != null) {
                humanProfile.displayName
            } else {
                seat.displayName
            }
            val emoji = when {
                isHuman && humanProfile != null -> humanProfile.avatarEmoji
                else -> personality?.emoji
            }
            val avatarBackgroundColorHex = if (isHuman) humanProfile?.avatarBackgroundColor else null
            val seatEmpty = seat.playerId == null
            val handResolved = street == BettingRound.Complete ||
                seat.handParticipation == HandParticipation.NotDealt
            val isBusted = !seatEmpty && seat.stack <= 0L && handResolved
            return SeatView(
                index = seat.index,
                displayName = displayName,
                stack = seat.stack,
                contributedThisStreet = seat.contributedThisStreet,
                isActing = isActing,
                isHuman = isHuman,
                isBot = seat.isBot,
                avatarKey = personality?.avatarKey,
                emoji = emoji,
                avatarBackgroundColorHex = avatarBackgroundColorHex,
                holeCards = visibleHole,
                showHoleCardBacks = backs,
                participation = seat.handParticipation,
                seatEmpty = seatEmpty,
                isBusted = isBusted,
                lastAction = lastAction,
                isDealer = isDealer,
                isSmallBlind = isSmallBlind,
                isBigBlind = isBigBlind,
                seatBadge = seatBadge,
                personality = personality,
            )
        }
    }
}

fun PlayerAction.shortLabel(): String = when (this) {
    is PlayerAction.Fold -> "Folded"
    is PlayerAction.Check -> "Checked"
    is PlayerAction.Call -> "Called $amount"
    is PlayerAction.Bet -> "Bet $amount"
    is PlayerAction.Raise -> "Raised to $totalStreetContribution"
    is PlayerAction.AllIn -> "All in $amount"
}

data class LegalActions(
    val canCheck: Boolean,
    val canCall: Boolean,
    val callAmount: Long,
    val canRaise: Boolean,
    val isOpenBet: Boolean,
    val minRaiseTotal: Long,
    val maxRaiseTotal: Long,
    val canAllIn: Boolean,
    val allInAmount: Long,
    val potIfYouCall: Long,
) {
    companion object {
        fun from(state: GameState, seat: Seat): LegalActions {
            val toCall = (state.currentBetThisStreet - seat.contributedThisStreet).coerceAtLeast(0)
            val canCheck = toCall == 0L
            val canCall = toCall in 1..seat.stack
            val canRaise = seat.stack > toCall
            val isOpenBet = state.currentBetThisStreet == 0L
            val minRaiseTotal = if (isOpenBet) {
                state.settings.bigBlind
            } else {
                state.currentBetThisStreet + state.lastFullRaiseSize
            }
            val maxRaiseTotal = seat.contributedThisStreet + seat.stack
            val pot = state.seats.sumOf { it.contributedThisStreet } +
                state.pots.sumOf { it.amount }
            return LegalActions(
                canCheck = canCheck,
                canCall = canCall,
                callAmount = toCall,
                canRaise = canRaise && maxRaiseTotal >= minRaiseTotal,
                isOpenBet = isOpenBet,
                minRaiseTotal = minRaiseTotal,
                maxRaiseTotal = maxRaiseTotal,
                canAllIn = seat.stack > 0,
                allInAmount = seat.stack,
                potIfYouCall = pot + toCall,
            )
        }
    }
}

data class HandResultView(
    val winners: List<HandWinner>,
    val board: List<Card>,
)

private fun previewHandLabel(holeCards: List<Card>, community: List<Card>): String? {
    if (holeCards.size != 2) return null
    val total = holeCards + community
    return when {
        total.size == 2 -> if (holeCards[0].rank == holeCards[1].rank) "Pocket pair" else null
        total.size in 5..7 -> HandEvaluator.evaluate(total).category.displayName
        else -> null
    }
}
