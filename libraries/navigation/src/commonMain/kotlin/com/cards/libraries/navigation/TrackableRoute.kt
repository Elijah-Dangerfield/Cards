package com.dangerfield.cards.libraries.navigation

import kotlinx.serialization.Serializable

/**
 * A Route that automatically has its visits tracked when navigated to.
 * 
 * @param trackingKey Unique key used to identify this route for tracking purposes.
 *                    This should match a field name in AppData.
 *                    Example: "settingsVisits", "aboutMeScreenOpens"
 */
@Serializable
open class TrackableRoute(
    val trackingKey: String,
) : Route()
