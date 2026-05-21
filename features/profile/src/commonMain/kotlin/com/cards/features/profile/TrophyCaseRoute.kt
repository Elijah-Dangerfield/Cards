package com.dangerfield.cards.features.profile

import com.dangerfield.cards.libraries.navigation.Route
import kotlinx.serialization.Serializable

/**
 * "Trophy Case" — display-only surface for the user's owned unlock-only
 * cosmetics (legendary-achievement, league-finish, RFT and achievement-
 * chain rewards). Disjoint from `MyItemsRoute`: My Items lists items the
 * user purchased (equip / unequip toggles allowed), Trophy Case lists
 * items earned via progression (no equip, no purchase path — ownership
 * itself is the prestige signal).
 *
 * V1 ships with an empty unlock-only catalog, so the screen renders an
 * "earn legendary achievements or league wins to unlock prestige
 * cosmetics" empty state. Once the server's inventory-grant-on-reward
 * path wires unlock-only product ids into the user's inventory, the
 * screen will render owned trophies + locked silhouettes for unearned
 * ones (mirroring the existing achievement-grid pattern).
 *
 * See `docs/todo.md` → "`unlock_only` flag on products + Trophy Case
 * surface" for the broader plan; this route is the client scaffold.
 */
@Serializable
class TrophyCaseRoute : Route()
