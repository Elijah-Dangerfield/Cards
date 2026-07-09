package com.dangerfield.cards.features.onboarding.impl

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.DpRect
import com.dangerfield.cards.libraries.ui.PreviewContent
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Host-side Compose UI tests for [OnboardingScreen] (AUTH-17). The guest and
 * post-OAuth paths render the PickIdentity step with different chrome (the
 * OAuth path hides the back affordance once an identity is claimed), and the
 * host draws the "step N of N" chip as a top-center overlay — so the step
 * content must keep the same headroom on both paths or the chip lands on the
 * avatar and the header reads as broken.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(manifest = Config.NONE, sdk = [34])
class OnboardingScreenTest {

    @Test
    fun pickIdentity_postOAuth_rendersContentAtGuestPathPositions() = runComposeUiTest {
        // AUTH-17: after returning from the Google OAuth flow the back
        // affordance is suppressed (identityClaimed), which used to collapse
        // the header — the step chip overlay landed on the avatar and the
        // "This is you" title shifted up. Both paths must render the step
        // content identically.
        var identityClaimed by mutableStateOf(false)
        setContent {
            PreviewContent {
                OnboardingScreen(
                    state = OnboardingState(
                        step = OnboardingStep.PickIdentity,
                        displayName = "QuietAce72",
                        identityClaimed = identityClaimed,
                    ),
                    onAction = {},
                )
            }
        }
        waitForIdle()
        val guestTitleBounds = titleBounds()

        identityClaimed = true
        waitForIdle()

        onNodeWithText(IDENTITY_TITLE).assertIsDisplayed()
        assertEquals(
            guestTitleBounds,
            titleBounds(),
            "post-OAuth PickIdentity must render the title exactly where the guest path does",
        )
        val chipBounds = onNodeWithText(STEP_CHIP, substring = true).getBoundsInRoot()
        assertTrue(
            chipBounds.bottom <= guestTitleBounds.top,
            "step chip ($chipBounds) must sit above the step content, not overlay it",
        )
    }

    private fun ComposeUiTest.titleBounds(): DpRect =
        onNodeWithText(IDENTITY_TITLE).getBoundsInRoot()

    private companion object {
        const val IDENTITY_TITLE = "This is you"
        const val STEP_CHIP = "Step 1 of"
    }
}
