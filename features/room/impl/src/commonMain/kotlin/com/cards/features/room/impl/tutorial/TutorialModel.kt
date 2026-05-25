package com.dangerfield.cards.features.room.impl.tutorial

import com.dangerfield.cards.libraries.gameplay.Card

/**
 * State + step model for the scripted poker tutorial. Deliberately
 * decoupled from the live game's `TableUiState` / `GameState` — the
 * tutorial doesn't run the engine; it walks a fixed list of snapshots
 * keyed to player actions.
 *
 * Each [TutorialStep] is rendered as:
 *  1. A complete table snapshot (opponents, board, pot, hero cards, stack).
 *  2. A coach-mark callout (title + body) anchored to a UI region.
 *  3. An optional CTA — the action button the script wants the user to tap
 *     ("Tap Raise 40"). The action bar restricts itself to that action.
 *  4. An [expectedAction] that advances the script when received.
 */

enum class TutorialAnchor {
    None,
    Opponents,
    Pot,
    Community,
    HoleCards,
    Stack,
    ActionBar,
}

enum class TutorialAction {
    /** Tap-to-continue narration step. */
    Continue,
    Raise,
    Call,
    Check,
    Fold,
}

enum class BlindRole { SmallBlind, BigBlind, Button }

data class TutorialOpponent(
    val name: String,
    val emoji: String,
    val backgroundColorHex: String,
    val stack: Long,
    val role: BlindRole? = null,
    val lastAction: String? = null,
    /** Render this opponent's avatar with a slight dim — they've folded. */
    val folded: Boolean = false,
)

data class TutorialLegalActions(
    val showCheck: Boolean = false,
    val showCall: Boolean = false,
    val callAmount: Long = 0,
    val showRaise: Boolean = false,
    val raiseAmount: Long = 0,
    val showFold: Boolean = false,
    /** Which single button is enabled. Everything else is visible but disabled. */
    val enabled: TutorialAction? = null,
)

data class TutorialTable(
    val handTitle: String,
    val handNumber: Int,
    val totalHands: Int,
    val opponents: List<TutorialOpponent>,
    val community: List<Card>,
    val pot: Long,
    val heroName: String,
    val heroEmoji: String,
    val heroBackgroundColorHex: String,
    val heroHoleCards: List<Card>,
    val heroStack: Long,
    val heroRole: BlindRole? = null,
    val heroLastAction: String? = null,
    /** Optional hand-strength label under hero cards — "Pair of aces". */
    val heroHandLabel: String? = null,
    val legalActions: TutorialLegalActions = TutorialLegalActions(),
)

data class CoachMark(
    val title: String?,
    val body: String,
    val anchor: TutorialAnchor,
    /** Optional CTA label rendered on the coach-mark itself for Continue
     *  steps. Action steps use the action bar button as the CTA, so this
     *  is null for them. */
    val ctaLabel: String? = null,
)

data class TutorialStep(
    val table: TutorialTable,
    val coach: CoachMark,
    val expected: TutorialAction,
)
