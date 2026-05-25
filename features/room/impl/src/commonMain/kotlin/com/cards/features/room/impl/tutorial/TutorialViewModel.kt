package com.dangerfield.cards.features.room.impl.tutorial

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.tatarka.inject.annotations.Inject

/**
 * Walks the user through the scripted [TutorialScript]. State is just a
 * pointer into the step list plus a "completed" flag; advancement is gated
 * by [submit] matching the current step's expected action.
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
    val state: StateFlow<TutorialState> = _state.asStateFlow()

    /** User performed an action in the tutorial UI. Advances the script
     *  only if [action] matches the current step's [TutorialStep.expected]. */
    fun submit(action: TutorialAction) {
        val current = _state.value
        if (current.completed) return
        if (action != current.step.expected) return
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

data class TutorialState(
    val step: TutorialStep,
    val stepIndex: Int,
    val totalSteps: Int,
    val completed: Boolean,
)
