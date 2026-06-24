package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.features.room.impl.session.RemotePokerSessionFactory
import com.dangerfield.cards.features.room.impl.session.SoloBotsPokerSessionFactory
import com.dangerfield.cards.features.room.impl.ui.TenureHeadline
import com.dangerfield.cards.features.room.impl.ui.label
import com.dangerfield.cards.features.room.impl.usecase.EmoteGate

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
import com.dangerfield.cards.libraries.cards.BotAvatarEmoji
import com.dangerfield.cards.libraries.cards.DefaultLevelCurve
import com.dangerfield.cards.libraries.cards.LevelCurve
import com.dangerfield.cards.libraries.cards.levelProgressFor
import com.dangerfield.cards.libraries.identity.profile.Profile
import com.dangerfield.cards.libraries.ui.components.formatCompactChips

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
        /**
         * The table buy-in (`RoomSettings.startingStack`) — what a fresh seat
         * is dealt. Drives the MP rebuy CTA: the bust dialog shows "Rebuy (N
         * chips)" and gates it against the player's wallet balance. Defaulted
         * for previews/tutorial samples; the real projection sets it from
         * `gameState.settings.startingStack`.
         */
        val buyIn: Long = 1_000,
        val handNumber: Int,
        val buttonSeatIndex: Int,
        val smallBlindSeatIndex: Int?,
        val bigBlindSeatIndex: Int?,
        /**
         * Table-level bot difficulty label ("Casual" / "Standard" /
         * "Challenging") for solo bot sessions. Surfaced by the
         * tap-an-opponent profile sheet's "Difficulty" section so the user
         * sees the table's tuning alongside the individual bot's playing
         * style. Null for MP tables where bots aren't seated.
         */
        val botDifficultyLabel: String? = null,
        /**
         * True when this MP table is bot-stacked / under-filled enough that
         * the hand earns only practice-tier credit (product-spec.md §5.4),
         * so the screen renders a visible "Practice tier · bots present"
         * label. Always false for solo bot sessions — solo is its own flow.
         */
        val practiceTierBotsPresent: Boolean = false,
        /**
         * True when this MP table is the bots-only sub-case of practice tier —
         * the local player is the only human, the rest bots. Drives the extra
         * "real chips aren't at stake" line in the practice-tier explainer.
         */
        val practiceTierBotsOnly: Boolean = false,
        /**
         * True when this bots-only table is the **public disclosed-bot subsidy** —
         * real chips ARE at stake (you keep what you win against the bots, up to a
         * daily limit), house-funded. Flips the bots-only explainer from "no real
         * chips" to "keep what you win". Never set on a private practice bot game.
         */
        val subsidizedBotTable: Boolean = false,
        /**
         * Per-turn timeout (`RoomSettings.turnTimerSeconds`) when the server
         * enforces it — MP only, where a stalling seat is auto-folded/checked
         * by `TurnTimerDriver`. Null for solo bot sessions (no enforcement),
         * which suppresses the on-table countdown ring so it never implies a
         * timer that doesn't exist.
         */
        val turnTimerSeconds: Int? = null,
        /**
         * Engine sequence of the latest applied action (`GameState.lastSequence`).
         * Resets to 0 each hand and bumps on every applied event, so
         * `handNumber to turnSequence` is the turn token the countdown ring
         * re-arms on — including a raise that returns action to the same seat.
         */
        val turnSequence: Long = 0,
        /**
         * True when the local user is a member of this MP table but holds no
         * seat in the current snapshot — i.e. they joined an in-progress game
         * and are spectating until the next hand boundary seats them. Drives
         * the "you'll be dealt in next hand" notice so a mid-game joiner isn't
         * left wondering why they have no cards. Always false for solo bot
         * sessions (the human is always seated) and once the joiner is dealt in.
         */
        val waitingToBeDealtIn: Boolean = false,
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
            /**
             * Whether to surface the "Practice tier · bots present" label —
             * set by [RemotePokerSessionFactory] for bot-stacked MP tables
             * (product-spec.md §5.4). Defaults false for solo sessions.
             */
            practiceTierBotsPresent: Boolean = false,
            /**
             * Whether this MP table is bots-only (local human + bots) — set by
             * [RemotePokerSessionFactory] so the explainer can add the "real
             * chips aren't at stake" line. Defaults false for solo sessions.
             */
            practiceTierBotsOnly: Boolean = false,
            /**
             * Whether this bots-only table is the public disclosed-bot subsidy
             * (real chips at stake) — set by [RemotePokerSessionFactory]. Defaults
             * false (private practice bots / solo).
             */
            subsidizedBotTable: Boolean = false,
            /**
             * Whether the server enforces the per-turn timer for this table —
             * MP only. When true the table surfaces `turnTimerSeconds` so the
             * acting human seat shows a countdown ring; solo sessions leave it
             * false so no countdown is implied where nothing is enforced.
             */
            turnTimerEnforced: Boolean = false,
            /**
             * The server-tunable level curve every derived level on this table
             * runs through, so remote opponents' pills match the level the
             * server granted under a retuned `progression.levelCurve`. Defaults
             * to [DefaultLevelCurve] so previews/tests render without threading
             * a curve through.
             */
            curve: LevelCurve = DefaultLevelCurve,
            /**
             * Whether the local user is a seatless member of this MP table —
             * a mid-game joiner spectating until the next hand boundary. Set by
             * [RemotePokerSessionFactory] when [humanSeatIndex] is negative.
             * Always false for solo sessions (the human always has a seat).
             */
            waitingToBeDealtIn: Boolean = false,
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
                        curve = curve,
                    ),
                    handsAtTable = gameState.handNumber,
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
                buyIn = gameState.settings.startingStack,
                handNumber = gameState.handNumber,
                buttonSeatIndex = gameState.buttonSeatIndex,
                smallBlindSeatIndex = sbIndex,
                bigBlindSeatIndex = bbIndex,
                botDifficultyLabel = botDifficultyLabel,
                practiceTierBotsPresent = practiceTierBotsPresent,
                practiceTierBotsOnly = practiceTierBotsOnly,
                subsidizedBotTable = subsidizedBotTable,
                turnTimerSeconds = gameState.settings.turnTimerSeconds.takeIf { turnTimerEnforced },
                turnSequence = gameState.lastSequence,
                waitingToBeDealtIn = waitingToBeDealtIn,
            )
        }

        /**
         * Compose the small badge that renders below an avatar on the play
         * screen — `SeatBadge.Level(14)` for the local human,
         * `SeatBadge.BotWithDifficulty("Standard")` (or `SeatBadge.BotPlain`
         * when difficulty is unknown) for bots, `null` for empty seats and
         * remote humans whose level we don't yet have a source for.
         *
         * Returns a sealed shape so the renderer can resolve via
         * `stringResource(...)` without re-deriving the formatting; mirrors
         * the playing-style / [TenureHeadline] pattern.
         */
        internal fun badgeFor(
            seat: Seat,
            isHuman: Boolean,
            humanLevel: Int?,
            botDifficultyLabel: String?,
            curve: LevelCurve = DefaultLevelCurve,
        ): SeatBadge? = when {
            seat.playerId == null -> null
            isHuman -> humanLevel?.let { SeatBadge.Level(it) }
            seat.isBot -> botDifficultyLabel?.let { SeatBadge.BotWithDifficulty(it) } ?: SeatBadge.BotPlain
            // Remote human in MP: the server snapshots their lifetime XP onto
            // the Seat at hand-start; derive the level the same way the local
            // human's is, through the same server-tunable [curve]. Null xp (not
            // yet resolved) collapses to no pill.
            else -> seat.xp?.let { SeatBadge.Level(levelProgressFor(it, curve).level) }
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
                // Heads-up: the button posts the small blind, the other seat the
                // big blind — matching GameEngine.pickBlindSeats. (Returned as
                // (smallBlindIndex, bigBlindIndex).)
                state.buttonSeatIndex to ordered.firstOrNull { it.index != state.buttonSeatIndex }?.index
            } else {
                ordered.getOrNull(0)?.index to ordered.getOrNull(1)?.index
            }
        }
    }
}

data class SeatView(
    val index: Int,
    /**
     * Stable user id of the seat's occupant (`Seat.playerId`). For a remote
     * human this is their account id — used to fetch their public play-style
     * for the Opponent Style Reader. Null for empty seats; for bots it's a bot
     * id (never used for a style fetch).
     */
    val userId: String? = null,
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
     * `Level(14)` for the local human, `BotWithDifficulty("Standard")` for
     * bots. Null means the seat doesn't have a label to show (empty seat,
     * remote human pre-level-plumbing). Renderer resolves to a string via
     * [SeatBadge.label]. See [TableUiState.Companion.badgeFor].
     */
    val seatBadge: SeatBadge? = null,
    /**
     * Bot personality for this seat — `null` for the human seat and any
     * non-bot seat. Tap-an-opponent surfaces use it to render an archetype
     * descriptor ("Tight aggressive — …"); gameplay code path doesn't read
     * this — bots' decisions go through the engine's own personality map.
     */
    val personality: BotPersonality? = null,
    /**
     * How many hands this seat has been at this table. Surfaces in the
     * tap-an-opponent profile sheet's "At this table" section as
     * "{N} hands at this table". For solo bot sessions this is the
     * session's hand count (every seat has been here since hand 1); for
     * MP it'll become the seat's own join-tenure once room-membership
     * timestamps are wired. `0` means the seat is empty or no hand has
     * resolved yet — the row is hidden in that case.
     */
    val handsAtTable: Int = 0,
    /**
     * For the local human seat only — `Profile.createdAt` in epoch ms —
     * used by the profile sheet to render "Playing since {month year}".
     * Null for every other seat (no source for remote-human or bot
     * tenure today).
     */
    val playingSinceEpochMs: Long? = null,
    /**
     * Product ids of the cosmetics this player shows off — their equipped
     * Badge-/Title-slot grants, straight off the engine [Seat]. The
     * tap-an-opponent sheet resolves each to display metadata from the catalog
     * to render tappable badge chips. Empty for bots / pre-resolve seats.
     */
    val equippedBadgeProductIds: List<String> = emptyList(),
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
            seatBadge: SeatBadge? = null,
            handsAtTable: Int = 0,
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
                // Every bot reads as a bot — the robot avatar is reserved for
                // them (filtered out of the human picker). Solo bots no longer
                // borrow their personality emoji; MP bots gain an avatar at all.
                seat.isBot -> BotAvatarEmoji
                // Remote human opponent: avatar rides the seat from the server
                // (snapshotted from their profile at join). Falls back to any
                // personality emoji, then to the initials avatar when blank.
                else -> seat.avatarEmoji?.takeIf { it.isNotBlank() } ?: personality?.emoji
            }
            val avatarBackgroundColorHex = when {
                isHuman -> humanProfile?.avatarBackgroundColor
                seat.isBot -> null
                else -> seat.avatarBackgroundColor
            }
            val seatEmpty = seat.playerId == null
            val handResolved = street == BettingRound.Complete ||
                seat.handParticipation == HandParticipation.NotDealt
            val isBusted = !seatEmpty && seat.stack <= 0L && handResolved
            return SeatView(
                index = seat.index,
                userId = seat.playerId,
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
                handsAtTable = if (seatEmpty) 0 else handsAtTable,
                playingSinceEpochMs = if (isHuman) humanProfile?.createdAt?.toEpochMilliseconds() else null,
                equippedBadgeProductIds = seat.badgeProductIds,
            )
        }
    }
}

fun PlayerAction.shortLabel(): String = when (this) {
    is PlayerAction.Fold -> "Folded"
    is PlayerAction.Check -> "Checked"
    is PlayerAction.Call -> "Called ${formatCompactChips(amount)}"
    is PlayerAction.Bet -> "Bet ${formatCompactChips(amount)}"
    is PlayerAction.Raise -> "Raised to ${formatCompactChips(totalStreetContribution)}"
    is PlayerAction.AllIn -> "All in ${formatCompactChips(amount)}"
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

/**
 * Stable key for muting a seat's table emotes: null for the human seat (you
 * can't mute yourself), else the seat's display name (the stable per-personality
 * name for bots). Used by [EmoteGate] and the avatar-tap mute toggle.
 */
fun seatMuteKey(seat: SeatView): String? = if (seat.isHuman) null else seat.displayName

data class HandResultView(
    val winners: List<HandWinner>,
    val board: List<Card>,
)

/**
 * Sealed shape for the badge rendered below an avatar — kept as a typed
 * variant rather than a pre-formatted `String` so the renderer can resolve
 * via `stringResource(...)` and the static factory ([TableUiState.fromGameState])
 * stays non-Composable. Difficulty label on [BotWithDifficulty] is a
 * wire-key from `SoloBotsPokerSessionFactory.difficultyName` and stays
 * inline (same call as the home `OptionPillRow` deferral).
 */
sealed class SeatBadge {
    data class Level(val level: Int) : SeatBadge()
    data class BotWithDifficulty(val difficulty: String) : SeatBadge()
    data object BotPlain : SeatBadge()
}

private fun previewHandLabel(holeCards: List<Card>, community: List<Card>): String? {
    if (holeCards.size != 2) return null
    val total = holeCards + community
    return when {
        total.size == 2 -> if (holeCards[0].rank == holeCards[1].rank) "Pocket pair" else null
        total.size in 5..7 -> HandEvaluator.evaluateOrNull(total)?.category?.displayName
        else -> null
    }
}
