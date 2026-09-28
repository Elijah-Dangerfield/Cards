package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ITunesLookupTest {

    @Test
    fun readsTheVersionOffTheFirstResult() {
        val body = """{"resultCount":1,"results":[{"trackName":"Downcard","version":"0.4.0","bundleId":"x"}]}"""
        assertEquals(AppVersion(0, 4, 0), iTunesLookupVersion(body))
    }

    @Test
    fun noResults_isNoAnswer() {
        // What the lookup returns for an unknown bundle id (an Xcode build with a
        // team-suffixed identifier, say).
        assertNull(iTunesLookupVersion("""{"resultCount":0,"results":[]}"""))
    }

    @Test
    fun garbage_isNoAnswer() {
        assertNull(iTunesLookupVersion("<html>rate limited</html>"))
        assertNull(iTunesLookupVersion(""))
        assertNull(iTunesLookupVersion("""{"results":[{"version":"latest"}]}"""))
    }
}
