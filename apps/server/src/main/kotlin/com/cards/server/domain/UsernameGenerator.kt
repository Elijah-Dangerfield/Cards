package com.dangerfield.cards.server.domain

/**
 * Generates random display names in Reddit-style format:
 * `Adjective-Noun-NNN` (e.g. `Hungry-Octagon-542`).
 *
 * Always includes a numeric suffix because:
 *  - The plain `Adjective-Noun` space is "only" 200×250 = 50k combos; the
 *    suffix expands that by 4 orders of magnitude.
 *  - Reddit-style names have a distinctive look that's instantly readable
 *    as "auto-generated, not a chosen name."
 *  - Players who want a custom name can change it later via the profile
 *    rename UI (Phase 3.x).
 *
 * Repository-level retry handles the collision tail — the `display_name`
 * unique constraint on `profiles` is the canonical arbiter.
 *
 * V1 English only. To add more languages: ship a `adjectives-<locale>.txt`
 * + `nouns-<locale>.txt` pair under `src/main/resources/usernames/`, pass
 * the caller's `Accept-Language` (already plumbed through `ClientContext`)
 * into the repository, and select the matching pair. Bidi / non-Latin
 * scripts (Arabic, CJK) want their own format conventions (no hyphens,
 * different number placement) — design that pass separately.
 */
interface UsernameGenerator {
    /** A fresh `Adjective-Noun-NNN` candidate. Three-to-four digit suffix. */
    fun random(): String
}
