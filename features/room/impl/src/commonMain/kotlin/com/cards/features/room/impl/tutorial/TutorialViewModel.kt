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
 * end of the script. Idempotent — repeated playthroughs (via Settings
 * → "How to play") no-op at the repo layer.
 */
class TutorialViewModel @Inject constructor(
    private val achievementRepository: AchievementRepository,
    private val appCache: AppCache,
) : ViewModel() {

    private val logger = KLog.withTag("TutorialViewModel")

    private val script = TutorialScript.steps
    private val _state = MutableStateFlow(
        TutorialState(
            step = script.first(),
            stepIndex = 0,
            totalSteps = script.size,
            completed = false,
        )
    )
    internal val state: StateFlow<TutorialState> = _state.asStateFlow()

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
            _state.value = current.copy(completed = true)
            recordCompletion()
        } else {
            _state.value = current.copy(
                step = script[nextIndex],
                stepIndex = nextIndex,
            )
        }
    }

    /** Jump past the foundational poker-rules intro to the first tableau
     *  step. No-op if we've already moved past the basics. */
    fun skipBasics() {
        val current = _state.value
        if (current.completed) return
        val firstNonBasics = script.indexOfFirst { !it.isBasics }
        if (firstNonBasics <= current.stepIndex || firstNonBasics == -1) return
        _state.value = current.copy(
            step = script[firstNonBasics],
            stepIndex = firstNonBasics,
        )
    }

    private fun recordCompletion() {
        // Fire-and-forget. The grant is idempotent at the repo layer, so a
        // process-death mid-grant is safe to retry. Wrap in `Catching` so
        // an XP-ledger or DB hiccup doesn't tank the completion UX.
        viewModelScope.launch {
            Catching {
                achievementRepository.recordTutorialComplete()
                // Finishing the tutorial implicitly dismisses the Home
                // banner — leaving a "Learn the basics" CTA in place
                // would be weird since the user just learned them.
                // Idempotent: appCache.update is a no-op if the flag is
                // already true (manual dismiss happened earlier).
                appCache.update { it.copy(tutorialBannerDismissed = true) }
            }.onFailure { logger.w(it) { "Tutorial-complete grant failed" } }
        }
    }
}

internal data class TutorialState(
    val step: TutorialStep,
    val stepIndex: Int,
    val totalSteps: Int,
    val completed: Boolean,
)
