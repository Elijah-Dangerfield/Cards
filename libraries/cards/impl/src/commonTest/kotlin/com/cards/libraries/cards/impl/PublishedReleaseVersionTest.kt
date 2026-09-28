package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppVersion
import com.dangerfield.cards.libraries.cards.LatestReleaseVersionCode
import com.dangerfield.cards.libraries.cards.LatestReleaseVersionName
import com.dangerfield.cards.libraries.config.AppConfigMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PublishedReleaseVersionTest {

    @Test
    fun matchingCode_yieldsThePublishedName() {
        val published = publishedRelease(code = 1234, name = "0.4.0")
        assertEquals(AppVersion(0, 4, 0), published.nameFor(1234))
    }

    @Test
    fun storeAheadOrBehindConfig_yieldsNothing() {
        // Config not written yet, or Play still offering the previous release:
        // either way the label would be a guess, so there is none.
        val published = publishedRelease(code = 1234, name = "0.4.0")
        assertNull(published.nameFor(1233))
        assertNull(published.nameFor(1235))
    }

    @Test
    fun unsetSentinel_yieldsNothingEvenIfTheStoreAgrees() {
        val published = publishedRelease(code = 0, name = "")
        assertNull(published.nameFor(0))
    }

    @Test
    fun unparseableName_yieldsNothing() {
        val published = publishedRelease(code = 1234, name = "latest")
        assertNull(published.nameFor(1234))
    }

    private fun publishedRelease(code: Int, name: String): PublishedReleaseVersion {
        val config = object : AppConfigMap() {
            override val map: Map<String, *> = mapOf(
                "upgrade" to mapOf("latestVersionCode" to code, "latestVersionName" to name),
            )
        }
        return PublishedReleaseVersion(LatestReleaseVersionCode(config), LatestReleaseVersionName(config))
    }
}
