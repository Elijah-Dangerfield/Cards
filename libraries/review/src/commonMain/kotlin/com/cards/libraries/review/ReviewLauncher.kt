package com.dangerfield.cards.libraries.review

/**
 * Thin wrapper around the platform's in-app review API: Android's
 * `ReviewManager.launchReviewFlow()` and iOS's
 * `SKStoreReviewController.requestReview()`. Lives behind an
 * interface so the eligibility gate in
 * [ReviewPromptCoordinator] is fully testable without touching the
 * platform.
 *
 * **Default binding is [com.dangerfield.cards.libraries.review.impl.NoOpReviewLauncher]**
 * while the platform impls are still being wired. With the no-op
 * bound the coordinator runs end-to-end — eligibility checks fire,
 * timestamps persist — but nothing user-visible happens. Replace the
 * binding with a real `AndroidReviewLauncher` / `IosReviewLauncher`
 * impl to enable the actual prompt.
 *
 * Implementations must not throw: the platform APIs report failure
 * via callbacks / no-ops, and a thrown launcher would punch through
 * a UI-thread coroutine. Swallow + log instead.
 */
interface ReviewLauncher {

    /**
     * Ask the platform to surface a review prompt. The OS may or may
     * not actually show anything; from the caller's perspective this
     * is fire-and-forget. Suspends only long enough to hand the
     * request off to the platform.
     */
    suspend fun requestReview()
}
