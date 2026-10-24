@file:OptIn(ExperimentalObjCName::class)

package com.dangerfield.cards.libraries.review

import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * Thin wrapper around the platform's in-app review API: Android's
 * `ReviewManager.launchReviewFlow()` and iOS's
 * `SKStoreReviewController.requestReview()`. Lives behind an interface
 * so the eligibility gate in [ReviewPromptCoordinator] is fully
 * testable without touching the platform.
 *
 * **Android binding** is `AndroidReviewLauncher` in
 * `:libraries:review:impl/androidMain`. **iOS binding** is `IOSReviewLauncher`
 * (Swift) passed into the DI graph via `IosAppComponent.provideReviewLauncher`.
 *
 * Implementations must not throw: the platform APIs report failure via
 * callbacks / no-ops, and a thrown launcher would punch through a
 * UI-thread coroutine. Swallow + log instead.
 */
@ObjCName("ReviewLauncher", exact = true)
interface ReviewLauncher {

    /**
     * Ask the platform to surface a review prompt. The OS may or may
     * not actually show anything; from the caller's perspective this
     * is fire-and-forget. Suspends only long enough to hand the
     * request off to the platform.
     */
    suspend fun requestReview(): ReviewLaunchOutcome
}

/**
 * How far a [ReviewLauncher.requestReview] call got. The coordinator
 * reads this to decide whether the user's ask was spent — see
 * `RealReviewPromptCoordinator`.
 */
@ObjCName("ReviewLaunchOutcome", exact = true)
enum class ReviewLaunchOutcome {

    /**
     * The platform received the request and owns what happens next —
     * including deciding to show nothing. Play handing back no
     * `ReviewInfo` lands here too: that refusal *is* the store's own
     * quota decision, so the ask counts as spent.
     */
    Requested,

    /**
     * There was no Activity or window scene to host a prompt, so
     * nothing was requested of the platform at all. Expected whenever
     * a prompt is fired from a coroutine that outlived the screen.
     */
    NoForegroundUi,
}
