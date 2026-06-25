package com.dangerfield.cards

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountSetupExplainerTest {

    @Test
    fun showsWhenPendingAndUnseen() {
        assertTrue(shouldShowAccountSetupExplainer(pending = true, hasSeenExplainer = false))
    }

    @Test
    fun hiddenOnceSeen() {
        assertFalse(shouldShowAccountSetupExplainer(pending = true, hasSeenExplainer = true))
    }

    @Test
    fun hiddenWhenNotPending() {
        assertFalse(shouldShowAccountSetupExplainer(pending = false, hasSeenExplainer = false))
        assertFalse(shouldShowAccountSetupExplainer(pending = false, hasSeenExplainer = true))
    }
}
