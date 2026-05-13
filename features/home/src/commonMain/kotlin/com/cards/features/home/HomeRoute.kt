package com.dangerfield.cards.features.home

import com.dangerfield.cards.libraries.navigation.Route
import com.dangerfield.cards.libraries.navigation.TrackableRoute
import kotlinx.serialization.Serializable

@Serializable
class HomeRoute : Route()

@Serializable
data class SettingsRoute(
    val visitCount: Int = 1,
) : TrackableRoute("settingsVisits")

@Serializable
class FeedbackRoute : TrackableRoute("feedbackScreenOpens")
