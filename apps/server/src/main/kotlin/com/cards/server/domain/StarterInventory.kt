package com.dangerfield.cards.server.domain

/**
 * Fixed list of catalog product ids granted to every new user at profile
 * creation. Seeded atomically inside [ProfileRepository.findOrCreate] so a
 * fresh profile + its starter rows commit in one transaction.
 *
 * Lives next to the catalog migrations as the single source of truth for
 * "what does every user own from day one". Add a new id here, mirror it as
 * an `unlock_only = TRUE` catalog row, and the corresponding row(s) appear
 * in My Items on the user's first launch.
 *
 * All entries must be `unlock_only = TRUE` in the products table so they
 * never appear in the shop — starter equipment is conceptually earned at
 * sign-up, not purchasable. The list is intentionally short; "starter"
 * means "the minimum so My Items isn't empty + each cosmetic family the
 * user touches early (felt / card back / avatar pack) has at least one
 * default row." Emote blasts are not a starter family — those packs are
 * purchase-only.
 */
object StarterInventory {

    val productIds: List<String> = listOf(
        "felt_default",
        "cardback_default",
        "avatars_starter",
    )
}
