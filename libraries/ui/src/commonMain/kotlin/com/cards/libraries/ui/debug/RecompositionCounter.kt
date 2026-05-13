package com.dangerfield.cards.libraries.ui.debug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import com.dangerfield.cards.libraries.core.logging.KLog
import io.ktor.util.date.getTimeMillis
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Lightweight utility to keep an eye on how often a composable recomposes.
 * Emits periodic debug logs and raises an error log when recompositions
 * pile up inside a short window ("rapid recompositions").
 */
@Composable
fun RecompositionCounter(
    /** Label appended to the log tag so you can trace which composable is being tracked. */
    tag: String,
    /** Frequency (in recomposition count) at which we emit a debug log. */
    logEvery: Int = 50,
    /** Number of recompositions inside [rapidRecompositionWindow] that should be considered problematic. */
    rapidRecompositionThreshold: Int = 40,
    /** Rolling window used to evaluate the [rapidRecompositionThreshold]. */
    rapidRecompositionWindow: Duration = 2.seconds,
    /** Cooldown between successive rapid-recomposition error logs to avoid log spam. */
    rapidLogCooldown: Duration = 5.seconds,
    /** Optional hook invoked every time a recomposition is observed. */
    onRecompose: (count: Long) -> Unit = {},
    /** Optional hook invoked whenever the rapid recomposition criteria are met. */
    onRapidRecomposition: (RapidRecompositionInfo) -> Unit = {},
) {
    require(logEvery > 0) { "logEvery must be > 0" }
    require(rapidRecompositionThreshold > 0) { "rapidRecompositionThreshold must be > 0" }
    val tracker = remember(tag, logEvery, rapidRecompositionThreshold, rapidRecompositionWindow, rapidLogCooldown) {
        RecompositionTracker(
            tag = tag,
            logEvery = logEvery,
            rapidThreshold = rapidRecompositionThreshold,
            rapidWindowMillis = rapidRecompositionWindow.inWholeMilliseconds,
            rapidCooldownMillis = rapidLogCooldown.inWholeMilliseconds
        )
    }

    SideEffect {
        tracker.onRecompose(
            onRecompose = onRecompose,
            onRapidRecomposition = onRapidRecomposition
        )
    }
}

private class RecompositionTracker(
    tag: String,
    private val logEvery: Int,
    private val rapidThreshold: Int,
    private val rapidWindowMillis: Long,
    private val rapidCooldownMillis: Long,
) {
    private val logger = KLog.withTag("Recompose/$tag")
    private var totalCount: Long = 0
    private val timestamps = ArrayDeque<Long>()
    private var lastRapidLoggedAt: Long = 0

    fun onRecompose(
        onRecompose: (Long) -> Unit,
        onRapidRecomposition: (RapidRecompositionInfo) -> Unit,
    ) {
        totalCount += 1
        onRecompose(totalCount)
        if (totalCount % logEvery == 0L) {
            logger.d { "Recomposed $totalCount times" }
        }

        if (rapidWindowMillis <= 0L) return

        val now = getTimeMillis()
        timestamps.addLast(now)
        prune(now)

        if (timestamps.size >= rapidThreshold && shouldLogRapid(now)) {
            lastRapidLoggedAt = now
            logger.e {
                "Rapid recompositions detected: ${timestamps.size} in ${rapidWindowMillis}ms (threshold=$rapidThreshold, total=$totalCount)"
            }
            onRapidRecomposition(
                RapidRecompositionInfo(
                    countInWindow = timestamps.size,
                    totalCount = totalCount,
                    windowMillis = rapidWindowMillis
                )
            )
        }
    }

    private fun prune(now: Long) {
        val windowStart = now - rapidWindowMillis
        while (timestamps.isNotEmpty() && timestamps.first() < windowStart) {
            timestamps.removeFirst()
        }
    }

    private fun shouldLogRapid(now: Long): Boolean {
        if (rapidCooldownMillis <= 0L) return true
        val elapsed = now - lastRapidLoggedAt
        return elapsed >= rapidCooldownMillis
    }
}

data class RapidRecompositionInfo(
    val countInWindow: Int,
    val totalCount: Long,
    val windowMillis: Long,
)
