package com.dangerfield.cards.server.domain

/**
 * Curated emoji packs the avatar picker can render. The wire is a list
 * of [Pack]s so the client groups by name and the server can add new
 * packs without a UI release.
 *
 * `Pack.unlockProductId`:
 *  - `null` → starter pack, always available.
 *  - non-null → premium pack, granted via shop purchase. The avatar
 *    endpoint joins the caller's inventory and includes the pack only
 *    if the matching product ID appears there. Validation on
 *    `PATCH /v1/me` uses the same join — owning the pack is what
 *    permits picking one of its emojis.
 *
 * Curation rules for [Starter] emojis:
 *  - No person / face emojis. Random assignment of skin tone or gendered
 *    emoji is a problem we don't need to take on.
 *  - No ZWJ sequences (broader font support, especially on older
 *    Android builds).
 *  - Kept deliberately basic — a small set of common animals plus two
 *    poker-themed entries. Cooler / themed emojis live in paid unlock
 *    packs so the purchase feels like a real upgrade.
 *  - 8 entries — matches the 2×4 grid the onboarding picker renders
 *    without scroll, and is the same 8 the client ships as its no-
 *    network fallback list.
 *
 * **Append-only invariant.** Once an emoji is in [Starter] **it can
 * never be removed**, only added to. Old APKs ship a hardcoded
 * snapshot of this list as a no-network fallback; if the server were
 * to drop an emoji that the user later patches with, their save would
 * fail with `invalid_avatar_emoji`. This contract is what lets the
 * client and server drift across releases without lockstep deploys.
 * If a starter emoji ever needs to be retired, retire it from *new*
 * picker rendering on the client only — leave it server-valid for
 * existing profiles forever.
 *
 * Future packs live as siblings to [Starter]. To add one: define a
 * Pack with the matching `unlockProductId`, add it to [all]. Server
 * picks it up; the picker renders a new section.
 */
object AvatarPacks {

    data class Pack(
        val id: String,
        val name: String,
        val emojis: List<String>,
        /**
         * If non-null, the user must own this product (per the inventory
         * table) for the pack to appear and for its emojis to validate.
         */
        val unlockProductId: String? = null,
    )

    val Starter: Pack = Pack(
        id = "starter",
        name = "Starter pack",
        emojis = listOf(
            "🦊", "🐱", "🐼", "🐯",
            "🐸", "🦁", "🃏", "🎲",
        ),
    )

    // Paid packs are curated to be net-new vs [Starter] — buying a pack
    // should give the user emojis they didn't already have, otherwise the
    // purchase feels like the bulk of the pack was already free. If you
    // edit Starter, re-check the paid lists for overlap and keep them
    // disjoint.
    val Animals: Pack = Pack(
        id = "animals",
        name = "Animals",
        emojis = listOf("🐶", "🐻", "🐰", "🐨", "🐮", "🐷", "🐔", "🐒"),
        unlockProductId = "avatars_animals",
    )

    val Food: Pack = Pack(
        id = "food",
        name = "Foodie",
        emojis = listOf("🍕", "🍔", "🌮", "🍣", "🍰", "🥑", "🍩", "☕"),
        unlockProductId = "avatars_food",
    )

    val Sports: Pack = Pack(
        id = "sports",
        name = "Sports",
        emojis = listOf("⚽", "🏀", "🏈", "⚾", "🎾", "🏓", "🎳", "🥊"),
        unlockProductId = "avatars_sports",
    )

    val Fantasy: Pack = Pack(
        id = "fantasy",
        name = "Fantasy",
        emojis = listOf("🧙", "🧚", "🧛", "🧜", "🐉", "🔮", "🗡️", "🧞"),
        unlockProductId = "avatars_fantasy",
    )

    val Mythical: Pack = Pack(
        id = "mythical",
        name = "Mythical",
        emojis = listOf("🦖", "🐙", "🦕", "🦑", "🦞", "🦀", "🐡", "🦈"),
        unlockProductId = "avatars_mythical",
    )

    val all: List<Pack> = listOf(Starter, Animals, Food, Sports, Fantasy, Mythical)

    /**
     * Packs accessible to a caller given their owned product IDs.
     * Always includes [Starter]; premium packs are included only when
     * the user owns the matching product. Order is stable so the picker
     * renders sections in the same order across sessions.
     */
    fun availableFor(ownedProductIds: Set<String>): List<Pack> = all.filter { pack ->
        pack.unlockProductId == null || pack.unlockProductId in ownedProductIds
    }

    /**
     * Set membership check for `PATCH /v1/me` validation against the
     * caller's currently-available pack union. O(packs × emojis) for a
     * tiny constant N; fine.
     */
    fun isEmojiAvailable(emoji: String, ownedProductIds: Set<String>): Boolean =
        availableFor(ownedProductIds).any { emoji in it.emojis }
}

/**
 * Legacy alias kept for the random-assignment path. The starter pack is
 * always the pool we pick from — premium-pack ownership at signup time
 * is by definition zero.
 */
object AvatarStarterPack {
    val values: List<String> get() = AvatarPacks.Starter.emojis
    fun contains(emoji: String): Boolean = emoji in values
}
