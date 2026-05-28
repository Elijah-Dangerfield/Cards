package com.dangerfield.cards.features.room.impl.tutorial

import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.tutorial_section_at_the_table
import cards.libraries.resources.generated.resources.tutorial_section_basics
import com.dangerfield.cards.features.room.impl.PlayPokerState
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import org.jetbrains.compose.resources.StringResource

/**
 * Step model for the scripted tutorial. Two shapes:
 *
 * - **Tableau step** (`state != null`), renders the **live**
 *   `PlayPokerScreen` with a fabricated [PlayPokerState], overlaying a
 *   floating [CoachMark] banner. Advancement is gated either by:
 *    - the user submitting a matching [PlayerIntent] (action-prompt
 *      steps); see [advanceOn]
 *    - the user tapping the CTA on the coach-mark itself (narration
 *      steps); indicated by a non-null [CoachMark.ctaLabel]
 *
 * - **Narration step** (`state == null`), renders a clean centered
 *   explainer card with a hero illustration up top, a serif italic
 *   headline + body anchored to the bottom, and a primary CTA.
 *
 * The screen reuses the real table chrome, opponents, board, hole
 * cards, action bar, so the tutorial visually matches the live
 * experience. Action-button restriction falls out naturally from
 * setting only the desired flags on [PlayPokerState.table.humanLegalActions].
 */
internal data class TutorialStep(
    val coach: CoachMark,
    /** Which act of the tutorial this step belongs to. Drives the
     *  per-section step counter (so it reads "Step 2 of 3 · Basics"
     *  instead of "Step 2 of 13", the global number hides the fact
     *  that the basics are a self-contained block you can finish).
     *  Defaults to [TutorialSection.AtTheTable] because the tableau
     *  steps outnumber the basics steps. */
    val section: TutorialSection = TutorialSection.AtTheTable,
    /** Null for narration-only intro steps (rendered as centered
     *  explainer cards). Non-null for tableau steps that fabricate a
     *  real-looking table behind the coach mark. */
    val state: PlayPokerState? = null,
    /** Predicate that returns true if the submitted intent should advance
     *  the script. Null = the step advances only via the coach-mark CTA. */
    val advanceOn: ((PlayerIntent) -> Boolean)? = null,
    /** Hero illustration for narration steps. Sealed-typed so each
     *  basics card can render a bespoke visual instead of a generic
     *  emoji glyph. Ignored for tableau steps. */
    val hero: NarrationHero? = null,
) {
    /** True for the foundational basics block. Convenience for the
     *  "Skip basics" button visibility check. */
    val isBasics: Boolean get() = section == TutorialSection.Basics
}

/**
 * The two acts of the tutorial.
 *
 * - [Basics], pure narration, zero-knowledge poker primer. Skippable.
 * - [AtTheTable], scripted hands at a fabricated table. Walks the
 *   player through how the app's chrome works, assuming basic poker
 *   vocabulary.
 */
internal enum class TutorialSection(val displayName: StringResource) {
    Basics(displayName = Res.string.tutorial_section_basics),
    AtTheTable(displayName = Res.string.tutorial_section_at_the_table),
}

/**
 * Hero illustration shown at the top of a narration step. The progress
 * affordance ("Step 1 of 3 · Basics") lives on the inline
 * `StepCounterPill` rendered above the gold headline — no per-hero
 * label needed here.
 */
internal sealed interface NarrationHero {
    /** "The pot" card visual: a coin counter ticking up. */
    data object Pot : NarrationHero

    /** "Hand ranks" card visual: three example mini-cards from weakest
     *  to strongest, the strongest amber-accented. */
    data object HandRanks : NarrationHero

    /** "Three actions" card visual: Fold / Call / Raise as a small
     *  legend. */
    data object Actions : NarrationHero
}

internal data class CoachMark(
    val title: StringResource?,
    val body: StringResource,
    /** Optional bullets rendered below the body in the floating coach
     *  mark. The redesigned basics narration uses [NarrationHero]
     *  illustrations instead of bullets; bullets remain available for
     *  tableau coach marks if a future step wants them. */
    val bullets: List<StringResource> = emptyList(),
    /** Non-null = narration step with an inline Next/Got-it/etc. button.
     *  Null = action-prompt step; the action bar itself is the CTA. */
    val ctaLabel: StringResource? = null,
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
 * - [Top], clears the play-screen top bar; good for steps that point
 *   at the action bar or hole cards (both at the bottom of the screen).
 * - [Middle], sits over the dead zone where the community cards live.
 *   The default; most steps don't need to highlight a specific edge.
 * - [Bottom], hugs the bottom safe-area inset; use for steps that
 *   point at opponents (top of the felt) or the pot reaction.
 */
internal enum class CoachMarkPlacement { Top, Middle, Bottom }
