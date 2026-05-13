package com.dangerfield.cards.libraries.core

import kotlinx.coroutines.flow.Flow

interface MotionDetector {
    val motionUpdates: Flow<MotionUpdate>
    
    fun start()
    fun stop()
}

data class MotionUpdate(
    val timestampMs: Long,
    val magnitude: Float,
) {
    val isStill: Boolean get() = magnitude < STILLNESS_THRESHOLD
    
    companion object {
        const val STILLNESS_THRESHOLD = 0.15f
    }
}
