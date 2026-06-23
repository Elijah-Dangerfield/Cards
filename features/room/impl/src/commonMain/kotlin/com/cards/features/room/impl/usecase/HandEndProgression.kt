package com.dangerfield.cards.features.room.impl.usecase

import com.dangerfield.cards.features.room.impl.HapticKind
import com.dangerfield.cards.features.room.impl.PlayPokerEvent
import com.dangerfield.cards.features.room.impl.PlayPokerViewModel
import com.dangerfield.cards.features.room.impl.SoundKind

import com.dangerfield.cards.libraries.cards.AchievementHandContext
import com.dangerfield.cards.libraries.gameplay.GameEvent
import com.dangerfield.cards.libraries.gameplay.GameState

/**
 * Pure end-of-hand derivations, split out of [PlayPokerViewModel] so they're
 * unit-testable without the VM's async repositories or coroutine scope. The VM
 * keeps the orchestration (awarding XP, recording achievements, the review
 * prompt); this object owns the deterministic shape it feeds in.
 */
object HandEndProgression {

    /**
     * One-off audio/haptic feedback for a finished hand, from the local human's
     * perspective: a showdown sound plus a won/lost haptic, and an extra
     * [HapticKind.Bust] cue when their stack hit zero. Empty when the human
     * isn't seated (spectator / pre-snapshot — [humanSeatIndex] not found in the
     * state) so another seat's outcome is never attributed to the local player.
     */
    fun feedbackEvents(
        event: GameEvent.HandEnded,
        state: GameState,
        humanSeatIndex: Int,
    ): List<PlayPokerEvent> {
        val humanSeat = state.seats.firstOrNull { it.index == humanSeatIndex }
            ?: return emptyList()
        val humanWon = event.winners.any { it.seatIndex == humanSeatIndex }
        return buildList {
            add(PlayPokerEvent.PlaySound(SoundKind.Showdown))
            add(PlayPokerEvent.PlayHaptic(if (humanWon) HapticKind.HandWon else HapticKind.HandLost))
            if (humanSeat.stack <= 0L) add(PlayPokerEvent.PlayHaptic(HapticKind.Bust))
        }
    }

    /**
     * The achievement-attribution context for a finished hand: the bot
     * opponents faced, the table difficulty, the human's start/end stacks, and
     * how many opponents busted this hand. Bots auto-rebuy on the *next* hand,
     * so the end-of-hand snapshot still shows their bust at attribution time.
     */
    fun achievementContext(
        state: GameState,
        humanSeatIndex: Int,
        humanStartingStack: Long,
        difficultyName: String,
    ): AchievementHandContext = AchievementHandContext(
        opponentBotNames = state.seats
            .filter { it.index != humanSeatIndex && it.isBot }
            .map { it.displayName },
        botDifficulty = difficultyName,
        humanStartingStack = humanStartingStack,
        humanEndingStack = state.seats.firstOrNull { it.index == humanSeatIndex }?.stack ?: 0L,
        bigBlind = state.settings.bigBlind,
        bustedOpponentCount = state.seats
            .count { it.index != humanSeatIndex && it.stack <= 0 },
    )
}
