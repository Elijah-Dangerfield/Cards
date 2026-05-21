package com.dangerfield.cards.libraries.review.impl

import com.dangerfield.cards.libraries.cards.ReviewPrompter
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AdaptedReviewLauncherTest : CoroutineTest() {

    @Test
    fun requestReview_delegatesToReviewPrompter() = runUnitTest {
        val prompter = CountingReviewPrompter()
        val launcher = AdaptedReviewLauncher(prompter)

        launcher.requestReview()
        launcher.requestReview()

        assertEquals(2, prompter.requestCount)
    }

    private class CountingReviewPrompter : ReviewPrompter {
        var requestCount: Int = 0
            private set

        override suspend fun requestReview() {
            requestCount += 1
        }
    }
}
