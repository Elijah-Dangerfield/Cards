package com.dangerfield.cards.libraries.review.impl

import com.dangerfield.cards.libraries.cards.ReviewPrompter
import com.dangerfield.cards.libraries.review.ReviewLauncher
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Default [ReviewLauncher] binding. Delegates to the platform-specific
 * [ReviewPrompter] already wired in `:libraries:cards:impl` (Android's
 * Play Core impl via `AndroidReviewPrompter`) and `apps/compose` (iOS
 * `SKStoreReviewController` impl passed in via `IosAppComponentFactory`).
 *
 * Layered this way intentionally — the [ReviewLauncher] / coordinator
 * lives in `:libraries:review` to keep the eligibility gate isolated
 * (install-age floor, prompt cooldown), and the actual OS call stays
 * with the long-standing [ReviewPrompter] binding. No new platform
 * code required.
 *
 * Replaces [NoOpReviewLauncher] as the bound implementation.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [NoOpReviewLauncher::class])
@Inject
class AdaptedReviewLauncher(
    private val reviewPrompter: ReviewPrompter,
) : ReviewLauncher {
    override suspend fun requestReview() {
        reviewPrompter.requestReview()
    }
}
