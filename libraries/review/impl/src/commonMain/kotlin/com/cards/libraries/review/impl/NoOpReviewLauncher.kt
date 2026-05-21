package com.dangerfield.cards.libraries.review.impl

import com.dangerfield.cards.libraries.review.ReviewLauncher
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Default [ReviewLauncher] binding. Replace with a real
 * `AndroidReviewLauncher` (wrapping `ReviewManager.launchReviewFlow`)
 * or `IosReviewLauncher` (wrapping `SKStoreReviewController.requestReview`)
 * using `@ContributesBinding(replaces = [NoOpReviewLauncher::class])`
 * when the platform impls land.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class NoOpReviewLauncher : ReviewLauncher {
    override suspend fun requestReview() = Unit
}
