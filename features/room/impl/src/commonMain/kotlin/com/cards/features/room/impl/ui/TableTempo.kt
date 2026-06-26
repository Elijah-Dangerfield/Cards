package com.dangerfield.cards.features.room.impl.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.dangerfield.cards.libraries.cards.GameSpeed

/**
 * Scales the play screen's cosmetic deal / reveal / settle timings by the
 * user's chosen [GameSpeed]. Provided once at the play-screen root via
 * [LocalTableTempo]; the card-deal and board-reveal animations read it so a
 * single setting retunes the whole table's pacing.
 *
 * "Instant" collapses every cosmetic delay to zero, so dealt and revealed
 * cards snap to their settled state instead of flying and flipping in.
 */
internal data class TableTempo(val speed: GameSpeed = GameSpeed.Normal) {

    /** True when cosmetic motion should be skipped entirely (jump to settled). */
    val isInstant: Boolean get() = speed == GameSpeed.Instant

    /** Scales a delay (ms) by the current speed; 0 when instant. */
    fun delay(durationMs: Int): Long = (durationMs * speed.animationScale).toLong()

    /** Scales an animation duration (ms); floored at 1 so tween() stays valid. */
    fun duration(durationMs: Int): Int =
        if (isInstant) 0 else (durationMs * speed.animationScale).toInt().coerceAtLeast(1)
}

/**
 * The active table tempo. Defaults to [GameSpeed.Normal] so previews and tests
 * render at the calibrated pace without any provider; the play screen overrides
 * it from the user's setting.
 */
internal val LocalTableTempo = staticCompositionLocalOf { TableTempo() }
