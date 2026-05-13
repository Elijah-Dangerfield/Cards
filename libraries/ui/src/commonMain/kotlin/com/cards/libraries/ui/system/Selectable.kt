package com.dangerfield.cards.libraries.ui

/**
 * used to allow any item to be marked as selected or not in a given UI without having to create a
 * new data class or add a new field to the base data class
 */
data class Selectable<T>(
    val item: T,
    val isSelected: Boolean = false
) {
    fun toggled() = copy(isSelected = !isSelected)
}