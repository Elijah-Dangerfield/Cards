package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.libraries.bots.BotPersonality
import com.dangerfield.cards.libraries.ui.components.RadarAxis
import kotlin.test.Test
import kotlin.test.assertEquals

class BotPlayingStyleTest {

    @Test
    fun janeIsTightPassive() {
        assertEquals("Tight passive", playingStyleFor(BotPersonality.Jane).label)
    }

    @Test
    fun davidIsLooseAggressive() {
        assertEquals("Loose aggressive", playingStyleFor(BotPersonality.David).label)
    }

    @Test
    fun ginaIsTightAggressive() {
        assertEquals("Tight aggressive", playingStyleFor(BotPersonality.Gina).label)
    }

    @Test
    fun steveIsLoosePassive() {
        assertEquals("Loose passive", playingStyleFor(BotPersonality.Steve).label)
    }

    @Test
    fun mikeIsManiac() {
        assertEquals("Maniac", playingStyleFor(BotPersonality.Mike).label)
    }

    @Test
    fun centerOfTheRangeIsBalanced() {
        val balanced = BotPersonality(
            name = "Balanced",
            tightness = 0.50,
            aggression = 0.50,
            bluffRate = 0.10,
            avatarKey = "avatar_balanced",
            emoji = "🤖",
        )
        assertEquals("Balanced", playingStyleFor(balanced).label)
    }

    @Test
    fun descriptionsAreNonBlank() {
        for (p in BotPersonality.Roster) {
            val style = playingStyleFor(p)
            assertEquals(style.description, style.description.trim())
            check(style.description.isNotBlank()) { "Description was blank for ${p.name}" }
        }
    }

    @Test
    fun radarAxes_includeFourLabelledAxes_inExpectedOrder() {
        val axes = radarAxesFor(BotPersonality.Jane)
        assertEquals(listOf("Tight", "Aggro", "Bluff", "Patient"), axes.map { it.label })
    }

    @Test
    fun radarAxes_normaliseBluffRateToFullScale() {
        // BotPersonality bluffRate caps at 0.4 by contract; the radar
        // rescales to 0..1 so the bluff axis uses the full ring.
        val mikeAxes = radarAxesFor(BotPersonality.Mike)
        val mikeBluff = mikeAxes.single { it.label == "Bluff" }.value
        // Mike's bluffRate=0.30 → 0.30/0.40 = 0.75.
        assertEquals(0.75f, mikeBluff, 0.001f)

        val janeAxes = radarAxesFor(BotPersonality.Jane)
        val janeBluff = janeAxes.single { it.label == "Bluff" }.value
        // Jane's bluffRate=0.04 → 0.04/0.40 = 0.10.
        assertEquals(0.10f, janeBluff, 0.001f)
    }

    @Test
    fun radarAxes_patientIsInverseOfAggression() {
        for (p in BotPersonality.Roster) {
            val axes = radarAxesFor(p)
            val aggro = axes.single { it.label == "Aggro" }.value
            val patient = axes.single { it.label == "Patient" }.value
            assertEquals(1.0f, aggro + patient, 0.001f, "Aggro+Patient should sum to 1 for ${p.name}")
        }
    }

    @Test
    fun radarAxes_eachRosterBotProducesUniqueShape() {
        // Tightness, aggression, and rescaled bluff vary per bot, so the
        // ordered (tight, aggro, bluff, patient) tuple should be unique.
        val tuples = BotPersonality.Roster.map { p ->
            radarAxesFor(p).map { it.value }
        }
        assertEquals(tuples.size, tuples.toSet().size, "Some bots collapsed to the same radar shape")
    }
}

private fun assertEquals(expected: Float, actual: Float, tolerance: Float, message: String? = null) {
    val diff = kotlin.math.abs(expected - actual)
    check(diff <= tolerance) {
        "${message ?: ""}: expected $expected ± $tolerance, was $actual (diff $diff)"
    }
}
