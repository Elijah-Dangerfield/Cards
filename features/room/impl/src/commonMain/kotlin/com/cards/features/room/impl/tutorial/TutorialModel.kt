package com.dangerfield.cards.features.room.impl.tutorial

import com.dangerfield.cards.features.room.impl.PlayPokerState
import com.dangerfield.cards.libraries.gameplay.PlayerIntent

/**
 * Step model for the scripted tutorial. Two shapes:
 *
 * - **Tableau step** (`state != null`) — renders the **live**
 *   `PlayPokerScreen` with a fabricated [PlayPokerState], overlaying a
 *   floating [CoachMark] banner. Advancement is gated either by:
 *    - the user submitting a matching [PlayerIntent] (action-prompt
 *      steps); see [advanceOn]
 *    - the user tapping the CTA on the coach-mark itself (narration
 *      steps); indicated by a non-null [CoachMark.ctaLabel]
 *
 * - **Narration step** (`state == null`) — renders a clean centered
 *   explainer card with an optional [heroGlyph]. Used for the
 *   foundational poker-rules intro before Hand 1, where there's no
 *   table to point at.
 *
 * The screen reuses the real table chrome — opponents, board, hole
 * cards, action bar — so the tutorial visually matches the live
 * experience. Action-button restriction falls out naturally from
 * setting only the desired flags on [PlayPokerState.table.humanLegalActions].
 */
internal data class TutorialStep(
    val coach: CoachMark,
    /** Which act of the tutorial this step belongs to. Drives the
     *  per-section step counter (so it reads "Step 2 of 3 · Basics"
     *  instead of "Step 2 of 13" — the global number hides the fact
     *  that the basics are a self-contained block you can finish).
     *  Defaults to [TutorialSection.AtTheTable] because the tableau
     *  steps outnumber the basics steps 10:3. */
    val section: TutorialSection = TutorialSection.AtTheTable,
    /** Null for narration-only intro steps (rendered as centered
     *  explainer cards). Non-null for tableau steps that fabricate a
     *  real-looking table behind the coach mark. */
    val state: PlayPokerState? = null,
    /** Predicate that returns true if the submitted intent should advance
     *  the script. Null = the step advances only via the coach-mark CTA. */
    val advanceOn: ((PlayerIntent) -> Boolean)? = null,
    /** Optional hero emoji for narration steps — gives each intro card
     *  a distinct identity. Ignored for tableau steps. */
    val heroGlyph: String? = null,
) {
    /** True for the foundational basics block. Convenience for the
     *  "Skip basics" button visibility check. */
    val isBasics: Boolean get() = section == TutorialSection.Basics
}

/**
 * The two acts of the tutorial.
 *
 * - [Basics] — pure narration, zero-knowledge poker primer. Three cards
 *   covering the goal, betting verbs, and hand ranks. Skippable.
 * - [AtTheTable] — three scripted hands at a fabricated table. Teaches
 *   when to raise, call/check, and fold by walking the player through
 *   a worked example of each.
 */
internal enum class TutorialSection(val displayName: String) {
    Basics(displayName = "Basics"),
    AtTheTable(displayName = "At the table"),
}

internal data class CoachMark(
    val title: String?,
    val body: String,
    /** Optional bullets rendered below the body. Left-aligned regardless
     *  of where the surrounding text aligns — centered bullets read
     *  awkwardly. Empty list = no bullets, just body. */
    val bullets: List<String> = emptyList(),
    /** Non-null = narration step with an inline Next/Got-it/etc. button.
     *  Null = action-prompt step; the action bar itself is the CTA. */
    val ctaLabel: String? = null,
    /** Where the coach mark sits by default. Picked per-step so steps
     *  that talk about opponents / pot don't cover them, and steps
     *  pointing at hole cards / action bar don't cover those instead.
     *  The user can still drag the mark anywhere via the handle.
     *  Ignored for narration-only steps (which center their content). */
    val placement: CoachMarkPlacement = CoachMarkPlacement.Middle,
)

/**
 * Where the floating coach mark sits on tableau steps.
 *
 * - [Top] — clears the play-screen top bar; good for steps that point
 *   at the action bar or hole cards (both at the bottom of the screen).
 * - [Middle] — sits over the dead zone where the community cards live.
 *   The default — most steps don't need to highlight a specific edge
 *   of the screen.
 * - [Bottom] — hugs the bottom safe-area inset; use for steps that
 *   explicitly point at opponents (Ada/Ben/Cleo sit at the top of
 *   the felt) or the pot reaction.
 */
internal enum class CoachMarkPlacement { Top, Middle, Bottom }
