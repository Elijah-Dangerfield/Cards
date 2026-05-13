package com.dangerfield.cards.libraries.core

import kotlinx.coroutines.flow.StateFlow

interface AppState {
    val isOffline: StateFlow<Boolean>
    val isBlockActive: StateFlow<Boolean>

}
