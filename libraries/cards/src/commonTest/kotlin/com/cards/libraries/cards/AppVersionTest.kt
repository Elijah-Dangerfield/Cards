package com.dangerfield.cards.libraries.cards

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppVersionTest {

    @Test
    fun parse_readsTheUsualThreePartShape() {
        assertEquals(AppVersion(1, 2, 3), AppVersion.parseOrNull("1.2.3"))
    }

    @Test
    fun parse_fillsMissingComponentsWithZero() {
        // App Store Connect happily reports a two-part version.
        assertEquals(AppVersion(0, 2, 0), AppVersion.parseOrNull("0.2"))
        assertEquals(AppVersion(3, 0, 0), AppVersion.parseOrNull("3"))
    }

    @Test
    fun parse_ignoresTrailingJunkAndWhitespace() {
        assertEquals(AppVersion(0, 2, 0), AppVersion.parseOrNull("  0.2.0 (1026)  "))
        assertEquals(AppVersion(1, 4, 2), AppVersion.parseOrNull("1.4.2-rc1"))
    }

    @Test
    fun parse_refusesRatherThanGuesses() {
        // A misread version drives a wrong prompt, which is worse than none.
        assertNull(AppVersion.parseOrNull(null))
        assertNull(AppVersion.parseOrNull(""))
        assertNull(AppVersion.parseOrNull("   "))
        assertNull(AppVersion.parseOrNull("v1.2.3"))
        assertNull(AppVersion.parseOrNull("latest"))
    }

    @Test
    fun compare_ordersByMajorThenMinorThenPatch() {
        assertTrue(AppVersion(1, 0, 0) > AppVersion(0, 9, 9))
        assertTrue(AppVersion(0, 3, 0) > AppVersion(0, 2, 99))
        assertTrue(AppVersion(0, 2, 2) > AppVersion(0, 2, 1))
        assertEquals(AppVersion(0, 2, 0), AppVersion(0, 2, 0))
    }

    // ── the prompt rule ──────────────────────────────────────────────────────

    private val installed = AppVersion(0, 2, 0)

    @Test
    fun prompts_onAMinorBump() {
        assertTrue(AppVersion(0, 3, 0).isWorthPromptingFrom(installed, lastPrompted = null))
    }

    @Test
    fun prompts_onAMajorBump() {
        assertTrue(AppVersion(1, 0, 0).isWorthPromptingFrom(installed, lastPrompted = null))
    }

    @Test
    fun staysSilent_onPatchReleases() {
        // The whole point of the feature-bump gate: a run of patches must not
        // turn the dialog into background noise.
        assertFalse(AppVersion(0, 2, 1).isWorthPromptingFrom(installed, lastPrompted = null))
        assertFalse(AppVersion(0, 2, 9).isWorthPromptingFrom(installed, lastPrompted = null))
    }

    @Test
    fun staysSilent_whenTheStoreIsNotAhead() {
        assertFalse(AppVersion(0, 2, 0).isWorthPromptingFrom(installed, lastPrompted = null))
        // A staged rollout the user is ahead of, or a lagging store cache.
        assertFalse(AppVersion(0, 1, 0).isWorthPromptingFrom(installed, lastPrompted = null))
    }

    @Test
    fun asksOncePerFeatureVersion_notOncePerLaunch() {
        val next = AppVersion(0, 3, 0)
        assertTrue(next.isWorthPromptingFrom(installed, lastPrompted = null))
        // Already asked about 0.3.0 — don't ask again on the next cold start.
        assertFalse(next.isWorthPromptingFrom(installed, lastPrompted = next))
    }

    @Test
    fun asksAgain_whenAFurtherFeatureReleaseLands() {
        // Skipped 0.3.0, then 0.4.0 ships: worth one more ask, not silence
        // forever and not a nag on every release.
        assertTrue(
            AppVersion(0, 4, 0).isWorthPromptingFrom(installed, lastPrompted = AppVersion(0, 3, 0)),
        )
    }

    @Test
    fun staysSilent_forPatchesOnTopOfAnAlreadyPromptedVersion() {
        // 0.3.1 lands after we already asked about 0.3.0. It is newer than the
        // prompted version, but still only a patch above what's installed.
        assertFalse(
            AppVersion(0, 3, 1).isWorthPromptingFrom(
                installed = AppVersion(0, 3, 0),
                lastPrompted = AppVersion(0, 3, 0),
            ),
        )
    }
}
