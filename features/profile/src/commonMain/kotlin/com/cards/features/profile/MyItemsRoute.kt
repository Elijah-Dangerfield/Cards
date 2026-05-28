package com.dangerfield.cards.features.profile

import com.dangerfield.cards.libraries.navigation.Route
import kotlinx.serialization.Serializable

/**
 * "My Items" — list of every product the user has purchased, with
 * equip/unequip toggles per item. Reachable from the profile screen.
 *
 * The optional [highlightProductId] is a deep-link hint: when navigated
 * from the post-purchase / post-grant snackbar's "View in My Items"
 * action, the screen scrolls the matching row into view and pulses it
 * briefly so the user can spot the just-acquired item.
 *
 * Lives in the profile api module so the profile screen (and any future
 * deep-link / shop "manage your items" affordance) can reference it
 * without depending on impl.
 */
@Serializable
data class MyItemsRoute(
    val highlightProductId: String? = null,
) : Route()
