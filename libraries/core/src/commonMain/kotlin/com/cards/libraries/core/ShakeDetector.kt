package com.dangerfield.cards.libraries.core

import kotlinx.coroutines.flow.Flow

interface ShakeDetector {
    val shakeEvents: Flow<ShakeEvent>
    
    fun start()
    fun stop()
}

data class ShakeEvent(
    val timestampMs: Long,
    val intensity: ShakeIntensity = ShakeIntensity.NORMAL,
)

enum class ShakeIntensity {
    GENTLE,
    NORMAL,
    VIGOROUS,
}
