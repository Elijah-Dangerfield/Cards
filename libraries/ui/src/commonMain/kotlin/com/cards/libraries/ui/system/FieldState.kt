package com.dangerfield.cards.libraries.ui

import androidx.compose.runtime.Immutable
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/**
 * Used to represent a view that has a state of being idle, valid, invalid, or in an error state.
 * @param T The type of the input value, must be serializable. All fiel
 */
@Immutable
sealed class FieldState<out T>(val value: T?, val error: String? = null) {

    /**
     * Default state. Not valid or invalid. Ex: Textfield is empty
     */
    data class Idle<T>(val input: T?, val message: String? = null) : FieldState<T>(input)

    /**
     * State where the input is valid
     * @param input The input value
     * @param message Optional message to display to the user
     *
     * ex: Email field is valid
     */
    data class Valid<T>(val input: T?, val message: String? = null) : FieldState<T>(input)

    /**
     * State where the input is invalid
     * @param input The input value
     * @param errorMessage The error message to display to the user
     *
     * ex: Email field is invalid
     */
    data class Invalid<T>(val input: T?, val errorMessage: String) : FieldState<T>(input, errorMessage)


    /**
     * State where the input is in an error state
     * @param input The input value
     * @param errorMessage The error message to display to the user
     *
     * ex: Email field is in an error state. Maybe a login failed and the email was the problem.
     */
    data class Error<T>(val input: T? = null, val errorMessage: String? = null) :
        FieldState<T>(input, errorMessage)
}

@OptIn(ExperimentalContracts::class)
fun <T> FieldState<T>.isValid(): Boolean {
    contract {
        returns(true) implies (this@isValid is FieldState.Valid)
    }
    return this is FieldState.Valid
}