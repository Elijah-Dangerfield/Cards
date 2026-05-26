package com.dangerfield.cards.features.room.impl.tutorial

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.libraries.cards.AchievementRepository
import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject

/**
 * Walks the user through the scripted [TutorialScript]. Advancement is
 * driven either by a matching [PlayerIntent] (action-prompt steps) or by
 * an explicit [advance] call from the coach-mark CTA (narration steps).
 *
 * Awards `AchievementId.TUTORIAL_COMPLETE` when the user reaches the
 * end of the script. Idempotent, repeated playthroughs (via Settings
 * → "How to play") no-op at the repo layer.
 */
class TutorialViewModel @Inject constructor(
    private val achievementRepository: AchievementRepository,
    private val appCache: AppCache,
) : ViewModel() {

    private val logger = KLog.withTag("TutorialViewModel")

    private val script = TutorialScript.steps
    private val _state = MutableStateFlow(stateForIndex(0))
    internal val state: StateFlow<TutorialState> = _state.asStateFlow()

    /**
     * Builds a [TutorialState] for the given global step index, deriving
     * the per-section progress counter so the UI can show "Step 2 of 3 ·
     * Basics" instead of "Step 2 of 13." Section transitions reset the
     * within-section counter back to 1.
     */
    private fun stateForIndex(globalIndex: Int): TutorialState {
        val step = script[globalIndex]
        val section = step.section
        val stepsInSection = script.filter { it.section == section }
        val sectionStepIndex = stepsInSection
            .indexOfFirst { it === step } // identity-compare, list contains dupes? unlikely, but cheap
            .coerceAtLeast(0)
        return TutorialState(
            step = step,
            stepIndex = globalIndex,
            totalSteps = script.size,
            sectionStepIndex = sectionStepIndex,
            sectionTotalSteps = stepsInSection.size,
            completed = false,
        )
    }

    /** User submitted an intent through the live action bar / swipe-fold.
     *  Advances the script if the current step's predicate accepts it. */
    fun submit(intent: PlayerIntent) {
        val current = _state.value
        if (current.completed) return
        val predicate = current.step.advanceOn ?: return
        if (predicate(intent)) advance()
    }

    /** Narration-step CTA tapped. Always advances. */
    fun advance() {
        val current = _state.value
        if (current.completed) return
        val nextIndex = current.stepIndex + 1
        if (nextIndex >= script.size) {
            // Flip completed=true immediately so the screen transitions
            // without waiting on the grant. wasFirstCompletion stays
            // null briefly; the completion screen renders text + CTA
            // unconditionally and gates the achievement reveal on the
            // resolved value, so a sub-100ms null window is invisible.
            _state.value = current.copy(completed = true, wasFirstCompletion = null)
            recordCompletion()
        } else {
            _state.value = stateForIndex(nextIndex)
        }
    }

    /** Jump past the foundational poker-rules intro to the first tableau
     *  step. No-op if we've already moved past the basics. */
    fun skipBasics() {
        val current = _state.value
        if (current.completed) return
        val firstNonBasics = script.indexOfFirst { !it.isBasics }
        if (firstNonBasics <= current.stepIndex || firstNonBasics == -1) return
        _state.value = stateForIndex(firstNonBasics)
    }

    /** Restart from the first basics step. Triggered by the "Back to
     *  basics" option in the leave dialog, for players who got into the
     *  tableau and realized they actually do want the primer. */
    fun restartBasics() {
        val firstBasics = script.indexOfFirst { it.isBasics }
        if (firstBasics == -1) return
        _state.value = stateForIndex(firstBasics)
    }

    private fun recordCompletion() {
        // Fire-and-forget on the side-effects (appCache update is OK to
        // miss on failure; the home banner stays dismissable manually).
        // But we DO need to await the achievement repo's response so we
        // can tell the completion screen whether this is a first-time
        // unlock (full reveal sequence) or a replay (no medallion).
        viewModelScope.launch {
            val wasFirst = Catching {
                achievementRepository.recordTutorialComplete() != null
            }.also { result ->
                result.exceptionOrNull()
                    ?.let { logger.w(it) { "Tutorial-complete grant failed" } }
            }.getOrDefault(true)

            _state.value = _state.value.copy(wasFirstCompletion = wasFirst)

            Catching {
                // Finishing the tutorial implicitly dismisses the Home
                // banner; leaving a "Learn the basics" CTA in place
                // would be weird since the user just learned them.
                // Idempotent: appCache.update is a no-op if the flag
                // is already true (manual dismiss happened earlier).
                appCache.update { it.copy(tutorialBannerDismissed = true) }
            }
        }
    }
}

internal data class TutorialState(
    val step: TutorialStep,
    /** 0-based index into the global step list. Still useful as an
     *  animation key (e.g. for resetting drag offset between steps)
     *  even though the UI surfaces the per-section counter to the user. */
    val stepIndex: Int,
    val totalSteps: Int,
    /** 0-based index of [step] within its [TutorialSection]. Drives the
     *  human-facing "Step 2 of 3 · Basics" pill. */
    val sectionStepIndex: Int,
    /** Total step count for [step.section]. */
    val sectionTotalSteps: Int,
    val completed: Boolean,
    /** True if this completion newly unlocked TUTORIAL_COMPLETE, false
     *  if the user had already earned it on a previous playthrough.
     *  Null while the grant call is in flight (sub-100ms window right
     *  after completion). The completion screen renders the celebration
     *  reveal only when this resolves to true; on replay (false) it
     *  hides the medallion entirely. */
    val wasFirstCompletion: Boolean? = null,
) {
    val section: TutorialSection get() = step.section
}
