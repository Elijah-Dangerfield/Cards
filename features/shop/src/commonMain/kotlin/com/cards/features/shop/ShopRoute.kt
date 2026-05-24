package com.dangerfield.cards.features.shop

import com.dangerfield.cards.libraries.navigation.TabRoute
import kotlinx.serialization.Serializable

/**
 * @param pendingProductId Optional product id the shop should auto-open
 * its purchase confirmation sheet for once the catalog has loaded.
 * Lets surfaces outside the shop (e.g. the locked-pack CTA on Edit
 * profile) deep-link straight to "I want this thing" rather than
 * dumping the user on the grid with no orientation. One-shot: the
 * shop consumes the id on the first composition and ignores it on
 * subsequent visits via the bottom-bar tab (which always navigates
 * with the id null).
 */
@Serializable
class ShopRoute(
    val pendingProductId: String? = null,
) : TabRoute()
