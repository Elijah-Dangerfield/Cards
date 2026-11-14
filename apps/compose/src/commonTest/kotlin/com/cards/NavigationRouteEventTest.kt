package com.dangerfield.cards

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NavigationRouteEventTest {
    @Test
    fun firstRouteHasNoPreviousRoute() {
        assertEquals("HomeRoute" to null, nextRouteEvent(previous = null, current = "HomeRoute"))
    }

    @Test
    fun changingRoutesIncludesPreviousRoute() {
        assertEquals("LobbyRoute" to "HomeRoute", nextRouteEvent(previous = "HomeRoute", current = "LobbyRoute"))
    }

    @Test
    fun recompositionDoesNotRepeatTheSameRoute() {
        assertNull(nextRouteEvent(previous = "HomeRoute", current = "HomeRoute"))
    }

    @Test
    fun missingRouteDoesNotEmit() {
        assertNull(nextRouteEvent(previous = "HomeRoute", current = null))
        assertNull(nextRouteEvent(previous = null, current = null))
    }
}
