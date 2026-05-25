package com.dangerfield.cards.server.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AvatarPacksTest {

    @Test
    fun paidPacks_haveNoEmojiOverlapWithStarter() {
        val starter = AvatarPacks.Starter.emojis.toSet()
        val paidPacks = AvatarPacks.all.filter { it.unlockProductId != null }
        assertTrue(paidPacks.isNotEmpty(), "No paid packs declared")

        paidPacks.forEach { pack ->
            val overlap = pack.emojis.toSet().intersect(starter)
            assertTrue(
                overlap.isEmpty(),
                "Paid pack \"${pack.name}\" overlaps with Starter: $overlap. " +
                    "Paid packs must be net-new vs Starter so buyers feel they got something.",
            )
        }
    }

    @Test
    fun starter_isAlwaysAvailable() {
        val available = AvatarPacks.availableFor(ownedProductIds = emptySet())
        assertEquals(listOf(AvatarPacks.Starter), available)
    }

    @Test
    fun availableFor_includesPaidPackOnceOwned() {
        val available = AvatarPacks.availableFor(
            ownedProductIds = setOf("avatars_animals"),
        )
        assertTrue(AvatarPacks.Animals in available)
        assertTrue(AvatarPacks.Starter in available)
        assertTrue(AvatarPacks.Food !in available)
    }

    @Test
    fun starter_hasExactlyEightEmojis_forA2x4PickerGrid() {
        // Onboarding's hardcoded picker is a 2×4 grid (`STARTER_TILE_COUNT
        // = 8` on the client). Server's Starter has to match — if it
        // grows past 8 we'll still render a subset on the client, but
        // that surface should be a deliberate redesign, not a quiet
        // server-side drift. If you intentionally grow the pack, bump
        // this assertion and the client's tile count together.
        assertEquals(8, AvatarPacks.Starter.emojis.size)
    }

    @Test
    fun starter_includesAllClientFallbackEmojis_appendOnlyGuard() {
        // **Load-bearing.** The Android/iOS client ships these 8 emojis
        // hardcoded as a no-network fallback (in `ProfileRepositoryImpl
        // .FALLBACK_AVATAR_PACK` and `OnboardingViewModel.STARTER_PACK`).
        // The server's append-only invariant on Starter promises these
        // emojis stay forever; this test guards that promise. If it
        // fails, every APK in the wild carrying these in its fallback
        // will hit `invalid_avatar_emoji` on patchMe the moment a user
        // picks the dropped emoji from the offline picker.
        //
        // To add a new emoji to Starter: append it; never reorder, never
        // remove. To "retire" one: stop rendering it on the client
        // picker, but leave it server-valid forever.
        val clientFallback = setOf("🦊", "🐱", "🐼", "🐯", "🐸", "🦁", "🃏", "🎲")
        val starter = AvatarPacks.Starter.emojis.toSet()
        val missing = clientFallback - starter
        assertTrue(
            missing.isEmpty(),
            "Server starter pack is missing client-fallback emojis: $missing. " +
                "Starter is append-only — old APKs depend on these.",
        )
    }

    @Test
    fun starter_containsNoFaceEmojis_curationRule() {
        // Curation rule documented at the top of AvatarPacks.kt: no
        // person / face emojis (Unicode emotion-bearing yellow faces +
        // gendered-person + ZWJ combinations). Random assignment of
        // gendered / skin-toned emoji is a problem we don't need to
        // take on. Catches accidental face additions during future
        // pack growth.
        val faceEmojis = AvatarPacks.Starter.emojis.filter { emoji ->
            // Unicode 'Emoticons' block (U+1F600..U+1F64F) covers the
            // yellow smiley/face range; 'person' codepoints sit in
            // U+1F466..U+1F487 etc. Covering the smiley block alone
            // catches the most likely accidents (😀🙂🤔😎 etc.).
            emoji.codePoints().anyMatch { cp -> cp in 0x1F600..0x1F64F }
        }
        assertTrue(
            faceEmojis.isEmpty(),
            "Starter contains face emojis (violates curation rule): $faceEmojis",
        )
    }
}
