package com.dangerfield.cards.libraries.navigation

/**
 * Interface for tracking navigation events.
 * 
 * This is called by the Router whenever navigation occurs to a TrackableRoute.
 * Implementations can record these visits for analytics, personalization,
 * or algorithm purposes.
 */
interface NavigationTracker {
    /**
     * Called when navigating to a route that implements TrackableRoute.
     * 
     * @param route The route being navigated to
     */
    suspend fun onNavigate(route: Route)
}
