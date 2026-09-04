package com.dangerfield.cards.libraries.navigation.impl

import com.dangerfield.cards.libraries.core.LegalUrls
import platform.Foundation.NSURL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosWebLinkLauncherTest {

    @Test
    fun `hands the link to the system rather than pre-judging whether it can be opened`() {
        var opened: NSURL? = null

        val result = IosWebLinkLauncher().open(LegalUrls.TERMS_OF_SERVICE) { url, onResult ->
            opened = url
            onResult(true)
        }

        assertEquals(LegalUrls.TERMS_OF_SERVICE, opened?.absoluteString)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `a refusal from the system is reported rather than thrown at the caller`() {
        val result = IosWebLinkLauncher().open(LegalUrls.TERMS_OF_SERVICE) { _, onResult ->
            onResult(false)
        }

        assertTrue(result.isSuccess)
    }

    @Test
    fun `a url the system cannot parse fails without an open attempt`() {
        var attempted = false

        val result = IosWebLinkLauncher().open("https://down card.app/terms") { _, _ ->
            attempted = true
        }

        assertFalse(attempted)
        assertTrue(result.isFailure)
    }
}
