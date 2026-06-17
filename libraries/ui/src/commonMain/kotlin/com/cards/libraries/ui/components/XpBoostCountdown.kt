package com.dangerfield.cards.libraries.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalInspectionMode
import kotlinx.coroutines.delay
import kotlin.time.Clock

/**
 * Ticks the remaining time on an XP boost window once a second, returning the
 * live remaining duration in ms (0 once it lapses, or when [expiresAtEpochMs] is
 * null). Shared by every active-boost surface — the profile banner, the level
 * pill — so they all count down off the same wall clock with one tick loop each.
 *
 * Hooks run unconditionally regardless of the nullability so the call site can
 * sit at the top of a composable that switches content on the result without
 * tripping the rules of composition. In `@Preview` ([LocalInspectionMode]) the
 * value is pinned to a fixed "90s left" instead of ticking, so pins render a
 * stable countdown.
 */
@Composable
fun rememberBoostRemainingMs(expiresAtEpochMs: Long?): Long {
    val inInspection = LocalInspectionMode.current
    var nowEpochMs by remember(expiresAtEpochMs, inInspection) {
        mutableStateOf(
            when {
                expiresAtEpochMs == null -> 0L
                inInspection -> expiresAtEpochMs - 90_000L
                else -> Clock.System.now().toEpochMilliseconds()
            },
        )
    }
    LaunchedEffect(expiresAtEpochMs, inInspection) {
        if (expiresAtEpochMs == null || inInspection) return@LaunchedEffect
        while (true) {
            nowEpochMs = Clock.System.now().toEpochMilliseconds()
            if (nowEpochMs >= expiresAtEpochMs) break
            delay(1_000L)
        }
    }
    return if (expiresAtEpochMs == null) 0L else (expiresAtEpochMs - nowEpochMs).coerceAtLeast(0L)
}

/** Formats a remaining boost duration as a `m:ss` (or `h:mm:ss`) countdown. */
fun formatBoostCountdown(remainingMs: Long): String {
    val totalSeconds = remainingMs / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}
