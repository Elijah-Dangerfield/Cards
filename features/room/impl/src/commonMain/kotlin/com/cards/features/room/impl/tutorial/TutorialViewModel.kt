package com.dangerfield.cards.features.room.impl.tutorial

import androidx.lifecycle.ViewModel
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.tatarka.inject.annotations.Inject

/**
 * Walks the user through the scripted [TutorialScript]. Advancement is
 * driven either by a matching [PlayerIntent] (action-prompt steps) or by
 * an explicit [advance] call from the coach-mark CTA (narration steps).
 */
class TutorialViewModel @Inject constructor() : ViewModel() {

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
        _state.value = if (nextIndex >= script.size) {
            current.copy(completed = true)
        } else {
            current.copy(
                step = script[nextIndex],
                stepIndex = nextIndex,
            )
        }
    }
}

internal data class TutorialState(
    val step: TutorialStep,
    val stepIndex: Int,
    val totalSteps: Int,
    val completed: Boolean,
)
