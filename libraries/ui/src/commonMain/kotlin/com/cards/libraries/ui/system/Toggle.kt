package com.dangerfield.cards.libraries.ui

import androidx.compose.runtime.MutableState

fun MutableState<Boolean>.toggle() {
    value = !value
}