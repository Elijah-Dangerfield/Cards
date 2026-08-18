package com.dangerfield.cards.libraries.ui.debug

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `SideEffect` runs after the *initial* composition too, so the tracker used
 * to report a recomposition for a composable that had merely appeared. On the
 * app root that meant every cold launch logged
 * "App recomposed (this should be rare)" at WARN, on both platforms — a
 * standing false alarm that cost real triage time on CARDS-3 (ENG-42).
 */
class RecompositionTrackerTest {

    private val counts = mutableListOf<Long>()

    private fun tracker(
        rapidThreshold: Int = 40,
        rapidWindowMillis: Long = 2_000,
    ) = RecompositionTracker(
        tag = "Test",
        logEvery = 1,
        rapidThreshold = rapidThreshold,
        rapidWindowMillis = rapidWindowMillis,
        rapidCooldownMillis = 0,
    )

    private fun RecompositionTracker.compose(times: Int) {
        repeat(times) { onRecompose(onRecompose = { counts += it }, onRapidRecomposition = {}) }
    }

    @Test
    fun initialComposition_isNotARecomposition() {
        tracker().compose(times = 1)

        assertEquals(emptyList(), counts)
    }

    @Test
    fun countStartsAtOneOnTheFirstActualRecomposition() {
        tracker().compose(times = 3)

        assertEquals(listOf(1L, 2L), counts)
    }

    @Test
    fun rapidRecompositionWindow_ignoresTheInitialComposition() {
        val rapid = mutableListOf<RapidRecompositionInfo>()
        val tracker = tracker(rapidThreshold = 3)
        fun compose() = tracker.onRecompose(onRecompose = {}, onRapidRecomposition = { rapid += it })

        repeat(3) { compose() }

        assertEquals(emptyList(), rapid, "one composition plus two recompositions must not trip a threshold of three")

        compose()

        assertEquals(listOf(3), rapid.map { it.countInWindow })
        assertEquals(listOf(3L), rapid.map { it.totalCount })
    }
}
