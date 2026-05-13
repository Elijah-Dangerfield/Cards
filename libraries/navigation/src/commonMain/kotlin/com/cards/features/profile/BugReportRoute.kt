package com.dangerfield.cards.features.profile

import com.dangerfield.cards.libraries.navigation.NavigableWhileBlocked
import com.dangerfield.cards.libraries.navigation.TrackableRoute
import kotlinx.serialization.Serializable

@Serializable
data class BugReportRoute(
	val logId: String? = null,
	val errorCode: Int? = null,
	val contextMessage: String? = null,
) : TrackableRoute("bugReportScreenOpens"), NavigableWhileBlocked
