package com.dangerfield.cards.server.domain

/**
 * Picks the random emoji used as a fresh identity's avatar. Curated set
 * (animals, objects, food, nature) — no person emojis, since random
 * assignment of person emojis has obvious problems.
 *
 * Players can swap to an unlocked-pack emoji later via the avatar picker
 * (Phase 3.x or 4 depending on prioritization); the value generated here
 * is the day-1 default and stays in `profiles.avatar_emoji` until then.
 */
interface AvatarGenerator {
    fun random(): String

    /**
     * Picks the random background color used as a fresh identity's avatar
     * disc fill. Returns a `#rrggbb` hex string drawn from [AvatarPalette].
     *
     * The avatar background is part of identity (same role as the display
     * name and emoji) — every new account gets a real value so all
     * rendering surfaces have one color to agree on. See
     * [docs/decisions.md] for the 2026-05-23 design call to make this
     * non-null at create time.
     */
    fun randomBackgroundColor(): String
}
