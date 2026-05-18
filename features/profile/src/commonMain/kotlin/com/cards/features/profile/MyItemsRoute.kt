package com.dangerfield.cards.features.profile

import com.dangerfield.cards.libraries.navigation.Route
import kotlinx.serialization.Serializable

/**
 * "My Items" — list of every product the user has purchased, with
 * equip/unequip toggles per item. Reachable from the profile screen.
 *
 * Lives in the profile api module so the profile screen (and any future
 * deep-link / shop "manage your items" affordance) can reference it
 * without depending on impl.
 */
@Serializable
class MyItemsRoute : Route()
