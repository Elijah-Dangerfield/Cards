package com.dangerfield.cards.features.profile.impl.items

import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.earned_founding_member_description
import cards.libraries.resources.generated.resources.earned_founding_member_how
import cards.libraries.resources.generated.resources.earned_founding_member_thanks
import cards.libraries.resources.generated.resources.earned_founding_member_title
import org.jetbrains.compose.resources.StringResource

/**
 * Display copy for `unlock_only` / prestige grants. The product catalog
 * (`GET /v1/products`) omits these, so the [OwnedItem] arrives with no
 * title / description / emoji — falling back to a prettified id + "🎁".
 * This client map gives the known ones a real ceremonial moment: a name,
 * what it is, how it was earned, and a thank-you for prestige grants.
 *
 * Client map over a server field by deliberate choice: the set is tiny,
 * changes only when we mint a new prestige grant (a code change anyway),
 * and the copy is user-facing so it belongs in `:libraries:resources`
 * next to every other string rather than on the wire. When the set grows
 * past a handful or needs per-cohort copy, promote it to a server field.
 */
data class EarnedItemInfo(
    val emoji: String,
    val title: StringResource,
    val description: StringResource,
    val howEarned: StringResource,
    /** Optional extra warmth for true prestige grants; null skips the line. */
    val thanks: StringResource?,
)

val KnownEarnedItems: Map<String, EarnedItemInfo> = mapOf(
    "badge_founding_member_1000" to EarnedItemInfo(
        emoji = "🏛",
        title = Res.string.earned_founding_member_title,
        description = Res.string.earned_founding_member_description,
        howEarned = Res.string.earned_founding_member_how,
        thanks = Res.string.earned_founding_member_thanks,
    ),
)
