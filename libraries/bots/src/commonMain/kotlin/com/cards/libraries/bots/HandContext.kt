package com.dangerfield.cards.libraries.bots

import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.PlayerAction
import com.dangerfield.cards.libraries.gameplay.SeatStatus

enum class TablePosition {
    Early,
    Middle,
    Late,
    SmallBlind,
    BigBlind,
    HeadsUpButton,
    HeadsUpBlind,
}

data class StreetAction(
    val seatIndex: Int,
    val action: PlayerAction,
)

/**
 * In-hand reads available to a bot at decision time. Built from the engine action log
 * by the session; the bot itself only sees this summary.
 *
 * `streetActionsBeforeSelf` is in order — newest action last.
 */
data class HandContext(
    val position: TablePosition,
    val streetActionsBeforeSelf: List<StreetAction>,
    val preflopAggressorSeatIndex: Int?,
    val selfRaisedThisStreet: Boolean,
) {
    val foldsInFront: Int =
        streetActionsBeforeSelf.count { it.action is PlayerAction.Fold }

    val callersInFront: Int =
        streetActionsBeforeSelf.count { it.action is PlayerAction.Call }

    val raisesInFront: Int =
        streetActionsBeforeSelf.count {
            it.action is PlayerAction.Raise ||
                it.action is PlayerAction.Bet ||
                it.action is PlayerAction.AllIn
        }

    val isUnopened: Boolean = raisesInFront == 0

    /** Consecutive folds immediately before the bot's turn. */
    val consecutiveFoldStreak: Int = run {
        var c = 0
        for (a in streetActionsBeforeSelf.asReversed()) {
            if (a.action is PlayerAction.Fold) c++ else break
        }
        c
    }

    companion object {
        val Empty: HandContext = HandContext(
            position = TablePosition.Middle,
            streetActionsBeforeSelf = emptyList(),
            preflopAggressorSeatIndex = null,
            selfRaisedThisStreet = false,
        )

        /**
         * Position of `seatIndex` relative to the dealer button, restricted to seats
         * still able to participate this hand. Returns Middle for degenerate tables.
         */
        fun positionOf(
            seatIndex: Int,
            buttonSeatIndex: Int,
            activeSeats: List<Int>,
        ): TablePosition {
            if (activeSeats.size < 2) return TablePosition.Middle
            val sorted = activeSeats.sorted()
            val buttonOrFirstAfter = sorted.firstOrNull { it >= buttonSeatIndex } ?: sorted.first()
            val startIdx = sorted.indexOf(buttonOrFirstAfter)
            // ordered[0] is the button (or nearest seat clockwise); ordered[1] = SB, ordered[2] = BB...
            val ordered = sorted.drop(startIdx) + sorted.take(startIdx)
            val n = ordered.size
            val offset = ordered.indexOf(seatIndex).coerceAtLeast(0)

            if (n == 2) {
                return if (offset == 0) TablePosition.HeadsUpButton
                else TablePosition.HeadsUpBlind
            }
            return when {
                offset == 0 -> TablePosition.Late // BTN
                offset == 1 -> TablePosition.SmallBlind
                offset == 2 -> TablePosition.BigBlind
                n <= 4 -> TablePosition.Middle
                offset == n - 1 -> TablePosition.Late // CO
                offset <= n / 2 -> TablePosition.Early
                else -> TablePosition.Middle
            }
        }
    }
}

/**
 * Session-side helper: rebuild a HandContext for the seat about to act from
 * a flat action log of the current street and the preflop aggressor (if any).
 *
 * `currentStreetLog` must be ordered oldest-first and only include actions
 * since the most recent street advance.
 */
fun buildHandContext(
    state: GameState,
    actingSeatIndex: Int,
    currentStreetLog: List<StreetAction>,
    preflopAggressorSeatIndex: Int?,
): HandContext {
    val activeIndexes = state.seats
        .filter { it.handParticipation != HandParticipation.NotDealt && it.seatStatus == SeatStatus.Active }
        .map { it.index }
    val position = HandContext.positionOf(actingSeatIndex, state.buttonSeatIndex, activeIndexes)
    val before = currentStreetLog
    val selfRaised = before.any {
        it.seatIndex == actingSeatIndex &&
            (it.action is PlayerAction.Raise ||
                it.action is PlayerAction.Bet ||
                it.action is PlayerAction.AllIn)
    }
    return HandContext(
        position = position,
        streetActionsBeforeSelf = before,
        preflopAggressorSeatIndex = if (state.street == BettingRound.Preflop) null else preflopAggressorSeatIndex,
        selfRaisedThisStreet = selfRaised,
    )
}
