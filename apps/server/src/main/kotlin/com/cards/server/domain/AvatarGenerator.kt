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
}
