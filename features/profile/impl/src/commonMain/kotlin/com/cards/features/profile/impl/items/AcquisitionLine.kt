package com.dangerfield.cards.features.profile.impl.items

import com.dangerfield.cards.libraries.cards.AcquisitionSource
import com.dangerfield.cards.libraries.cards.isDefaultCosmetic

/**
 * How the detail sheet should describe where an owned cosmetic came from, or
 * `null` when there's nothing honest to say. Kept pure (no Compose) so the
 * "which line, if any" decision has its own regression guard — the sheet only
 * resolves the copy for the chosen kind.
 */
sealed interface AcquisitionLineKind {
    /** Earned via an achievement / league / prize path. */
    data object Earned : AcquisitionLineKind

    /** Bought with chips ([costChips] > 0). */
    data class Bought(val costChips: Long) : AcquisitionLineKind

    /** Acquired at no chip cost (IAP grant, gift). */
    data object BoughtFree : AcquisitionLineKind
}

/**
 * The acquisition line to show for [item], or `null` to show none.
 *
 * Default/starter cosmetics (the felt + card back every account ships with)
 * are granted at account creation, not earned or bought — so they get no line
 * at all. Without this guard their seeded `acquiredAtEpochMs` resolves to a
 * "Bought free today" / "Earned today" badge, which is wrong (SHOP-4). A row
 * with no real acquisition timestamp also shows nothing.
 */
fun acquisitionLineKind(item: OwnedItem): AcquisitionLineKind? {
    if (isDefaultCosmetic(item.productId)) return null
    if (item.acquiredAtEpochMs <= 0L) return null
    return when {
        item.acquisitionSource == AcquisitionSource.Earned -> AcquisitionLineKind.Earned
        item.costChipsAtPurchase > 0L -> AcquisitionLineKind.Bought(item.costChipsAtPurchase)
        else -> AcquisitionLineKind.BoughtFree
    }
}
