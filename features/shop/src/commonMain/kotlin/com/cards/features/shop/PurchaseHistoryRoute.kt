package com.dangerfield.cards.features.shop

import com.dangerfield.cards.libraries.navigation.Route
import kotlinx.serialization.Serializable

/**
 * The in-app purchase-history screen — a real, app-specific list of the user's
 * chip-pack purchases with a "sync purchases" button, since the App Store's own
 * purchase history is buried and not app-scoped. A sub-route of the Shop tab.
 *
 * A `class` (not a `data object`): a `data object` route crashes the iOS
 * navigator with a SIGSEGV at navigate time.
 */
@Serializable
class PurchaseHistoryRoute : Route()
