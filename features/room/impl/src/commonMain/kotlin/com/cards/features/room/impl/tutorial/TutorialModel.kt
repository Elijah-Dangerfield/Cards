package com.dangerfield.cards.features.room.impl.tutorial

import com.dangerfield.cards.features.room.impl.PlayPokerState
import com.dangerfield.cards.libraries.gameplay.PlayerIntent

/**
 * Step model for the scripted tutorial. Each step renders the **live**
 * `PlayPokerScreen` with a fabricated [PlayPokerState], overlaying a
 * floating [CoachMark] banner. Advancement is gated either by:
 *  - the user submitting a matching [PlayerIntent] (action-prompt steps);
 *    see [advanceOn]
 *  - the user tapping the CTA on the coach-mark itself (narration steps);
 *    indicated by a non-null [CoachMark.ctaLabel]
 *
 * The screen reuses the real table chrome — opponents, board, hole cards,
 * action bar — so the tutorial visually matches the live experience.
 * Action-button restriction falls out naturally from setting only the
 * desired flags on [PlayPokerState.table.humanLegalActions].
 */
internal data class TutorialStep(
    val state: PlayPokerState,
    val coach: CoachMark,
    /** Predicate that returns true if the submitted intent should advance
     *  the script. Null = the step advances only via the coach-mark CTA. */
    val advanceOn: ((PlayerIntent) -> Boolean)? = null,
)

internal data class CoachMark(
    val title: String?,
    val body: String,
    /** Non-null = narration step with an inline Next/Got-it/etc. button.
     *  Null = action-prompt step; the action bar itself is the CTA. */
    val ctaLabel: String? = null,
)
