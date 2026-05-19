package com.dangerfield.cards.features.profile

import com.dangerfield.cards.libraries.navigation.NavigableWhileBlocked
import com.dangerfield.cards.libraries.navigation.Route
import kotlinx.serialization.Serializable

/**
 * In-app support routes. Surface from anywhere — the profile feature owns
 * the actual screens, but the routes need to be reference-able from places
 * that can't depend on `:features:profile:impl` (e.g. the navigation
 * library's error-fallback screens, or the app-level shake handler).
 *
 * Living in the api module is the only way to make
 * `@ContributesBinding` impls in `:libraries:navigation:impl` reference
 * them: per `ModuleBoundaries`, only `:apps:*` can depend on `*:impl`.
 */

@Serializable
data class BugReportRoute(
    val logId: String? = null,
    val errorCode: Int? = null,
    val contextMessage: String? = null,
) : Route(), NavigableWhileBlocked

@Serializable
class FeedbackRoute : Route()
