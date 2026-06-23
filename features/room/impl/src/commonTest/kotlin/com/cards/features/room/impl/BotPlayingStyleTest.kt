package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.features.room.impl.ui.playingStyleFor
import com.dangerfield.cards.features.room.impl.ui.radarAxesFor

import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.room_player_profile_style_balanced_label
import cards.libraries.resources.generated.resources.room_player_profile_style_loose_aggressive_label
import cards.libraries.resources.generated.resources.room_player_profile_style_loose_passive_label
import cards.libraries.resources.generated.resources.room_player_profile_style_maniac_label
import cards.libraries.resources.generated.resources.room_player_profile_style_tight_aggressive_label
import cards.libraries.resources.generated.resources.room_player_profile_style_tight_passive_label
import com.dangerfield.cards.libraries.bots.BotPersonality
import kotlin.test.Test
import kotlin.test.assertEquals

class BotPlayingStyleTest {

    @Test
    fun janeIsTightPassive() {
        assertEquals(
            Res.string.room_player_profile_style_tight_passive_label,
            playingStyleFor(BotPersonality.Jane).label,
        )
    }

    @Test
    fun davidIsLooseAggressive() {
        assertEquals(
            Res.string.room_player_profile_style_loose_aggressive_label,
            playingStyleFor(BotPersonality.David).label,
        )
    }

    @Test
    fun ginaIsTightAggressive() {
        assertEquals(
            Res.string.room_player_profile_style_tight_aggressive_label,
            playingStyleFor(BotPersonality.Gina).label,
        )
    }

    @Test
    fun steveIsLoosePassive() {
        assertEquals(
            Res.string.room_player_profile_style_loose_passive_label,
            playingStyleFor(BotPersonality.Steve).label,
        )
    }

    @Test
    fun mikeIsManiac() {
        assertEquals(
            Res.string.room_player_profile_style_maniac_label,
            playingStyleFor(BotPersonality.Mike).label,
        )
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
        assertEquals(
            Res.string.room_player_profile_style_balanced_label,
            playingStyleFor(balanced).label,
        )
    }

    @Test
    fun descriptionResourceIsDistinctPerArchetype() {
        val descriptions = BotPersonality.Roster
            .map { playingStyleFor(it).description }
            .toSet()
        // Roster of 5 bots covers 4 distinct archetypes (no Balanced bot in
        // the shipping roster), so 4 unique description keys is the floor.
        check(descriptions.size >= 4) {
            "Expected at least 4 distinct description keys, got ${descriptions.size}"
        }
    }

    @Test
    fun radarAxes_includeFourLabelledAxes_inExpectedOrder() {
        val axes = radarAxesFor(BotPersonality.Jane)
        assertEquals(listOf("Tight", "Aggro", "Bluff", "Patient"), axes.map { it.label })
    }

    @Test
    fun radarAxes_normaliseBluffRateToFullScale() {
        val mikeAxes = radarAxesFor(BotPersonality.Mike)
        val mikeBluff = mikeAxes.single { it.label == "Bluff" }.value
        assertEquals(0.75f, mikeBluff, 0.001f)

        val janeAxes = radarAxesFor(BotPersonality.Jane)
        val janeBluff = janeAxes.single { it.label == "Bluff" }.value
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
