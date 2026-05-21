package com.dangerfield.cards.libraries.review.impl

import com.dangerfield.cards.libraries.review.ReviewLauncher
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Fallback [ReviewLauncher] binding — kept for tests / wiring that
 * don't want to drag in the platform [com.dangerfield.cards.libraries.cards.ReviewPrompter].
 *
 * Production binding is [AdaptedReviewLauncher], which delegates to the
 * existing platform-specific `ReviewPrompter` (Android Play Core,
 * iOS `SKStoreReviewController`). The `@ContributesBinding(replaces = …)`
 * on the adapter is what swaps this out at DI graph merge time.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class NoOpReviewLauncher : ReviewLauncher {
    override suspend fun requestReview() = Unit
}
