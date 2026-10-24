package com.dangerfield.cards.libraries.navigation.impl

import com.dangerfield.cards.libraries.core.LegalUrls
import platform.Foundation.NSURL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosWebLinkLauncherTest {

    @Test
    fun `hands the link to the system rather than pre-judging whether it can be opened`() {
        var opened: NSURL? = null
        var presented: NSURL? = null

        val result = IosWebLinkLauncher().open(
            url = LegalUrls.TERMS_OF_SERVICE,
            opener = { url, onResult ->
                opened = url
                onResult(true)
            },
            presenter = { url ->
                presented = url
                true
            },
        )

        assertEquals(LegalUrls.TERMS_OF_SERVICE, opened?.absoluteString)
        assertNull(presented)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `a refused web page is shown in an in-app browser instead`() {
        var presented: NSURL? = null

        val result = IosWebLinkLauncher().open(
            url = LegalUrls.RESPONSIBLE_PLAY,
            opener = { _, onResult -> onResult(false) },
            presenter = { url ->
                presented = url
                true
            },
        )

        assertEquals(LegalUrls.RESPONSIBLE_PLAY, presented?.absoluteString)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `a refused link only another app could service is not forced into a browser`() {
        var presented = false

        val result = IosWebLinkLauncher().open(
            url = "mailto:support@downcard.app",
            opener = { _, onResult -> onResult(false) },
            presenter = {
                presented = true
                true
            },
        )

        assertFalse(presented)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `an in-app browser that cannot present is reported rather than thrown at the caller`() {
        val result = IosWebLinkLauncher().open(
            url = LegalUrls.RESPONSIBLE_PLAY,
            opener = { _, onResult -> onResult(false) },
            presenter = { error("no window") },
        )

        assertTrue(result.isSuccess)
    }

    @Test
    fun `a url the system cannot parse fails without an open attempt`() {
        var attempted = false

        val result = IosWebLinkLauncher().open(
            url = "https://down card.app/terms",
            opener = { _, _ -> attempted = true },
            presenter = { true },
        )

        assertFalse(attempted)
        assertTrue(result.isFailure)
    }
}
