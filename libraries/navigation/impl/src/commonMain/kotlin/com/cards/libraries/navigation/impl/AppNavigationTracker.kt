package com.dangerfield.cards.libraries.navigation.impl

import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.navigation.NavigationTracker
import com.dangerfield.cards.libraries.navigation.Route
import com.dangerfield.cards.libraries.navigation.TrackableRoute
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Tracks navigation events and updates AppCache accordingly.
 * 
 * Any route that extends TrackableRoute will automatically have its
 * visits tracked using its trackingKey. No additional code changes needed.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = NavigationTracker::class)
@Inject
class AppNavigationTracker(
    private val appCache: AppCache
) : NavigationTracker {

    override suspend fun onNavigate(route: Route) {
        if (route is TrackableRoute) {
            appCache.update { it.incrementVisit(route.trackingKey) }
        }
    }
}
