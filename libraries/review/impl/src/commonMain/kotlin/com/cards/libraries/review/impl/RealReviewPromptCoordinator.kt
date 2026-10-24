package com.dangerfield.cards.libraries.review.impl

import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.review.ReviewLaunchOutcome
import com.dangerfield.cards.libraries.review.ReviewLauncher
import com.dangerfield.cards.libraries.review.ReviewPromptCoordinator
import com.dangerfield.cards.libraries.review.ReviewTrigger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime

/**
 * Default [ReviewPromptCoordinator]. Eligibility gate: the install
 * must be at least [minInstallAge] old, and the previous prompt
 * (if any) must be at least [minPromptCooldown] in the past. The
 * first request also stamps the install time, so the cooldown floor
 * begins counting from the first positive moment we see — not from
 * actual install, which we can't observe retroactively for users who
 * upgrade past the version that introduces this coordinator.
 *
 * Thread-safety: a single mutex serializes the "read state → decide
 * → maybe launch → write state" critical section, so concurrent
 * triggers (achievement + level-up in the same frame) collapse into
 * a single attempt.
 *
 * The platform [ReviewLauncher] is called *after* persisting
 * `lastReviewPromptAt`. That ordering is on purpose: the OS's own
 * de-dupe / annual quota means the launcher may silently no-op, but
 * once we've decided to ask we should record that we asked. The
 * worst case is "we recorded a prompt the OS didn't actually show,
 * cooldown holds for 30 days" — acceptable. The bad case would be
 * recording-after-call and crashing in between, which would let us
 * spam the user on the next launch.
 *
 * [ReviewLaunchOutcome.NoForegroundUi] is the one case that rolls
 * that stamp back. Everywhere else we genuinely can't tell whether
 * the OS drew anything, so we assume it did; here the launcher never
 * reached the platform, so there is nothing to assume. Spending a
 * 30-day cooldown — and one of roughly three asks a year the stores
 * allow — on a celebration that fired while the app had no window is
 * a loss we can just decline to take (CARDS-D0). The cost of being
 * wrong is one extra prompt in a month, against a quota the OS
 * enforces anyway.
 *
 * A burst of triggers at one such moment still only tries once:
 * [noForegroundUiBackoff] holds the in-memory door shut, because
 * "the app has no window" won't change in the seconds between two
 * achievement celebrations. It's deliberately not persisted — a new
 * process means a foregrounded app, so the condition is gone.
 */
@OptIn(ExperimentalTime::class)
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class RealReviewPromptCoordinator(
    private val appCache: AppCache,
    private val launcher: ReviewLauncher,
    private val clock: Clock,
) : ReviewPromptCoordinator {

    private val mutex = Mutex()
    private var lastNoForegroundUiAt: Long = 0L

    override suspend fun requestPrompt(trigger: ReviewTrigger): Boolean = mutex.withLock {
        val nowMs = clock.now().toEpochMilliseconds()
        val state = appCache.get()

        val installAt = if (state.reviewInstallAt == 0L) nowMs else state.reviewInstallAt
        val installAge = (nowMs - installAt).coerceAtLeast(0L)
        val ageEligible = installAge >= minInstallAge.inWholeMilliseconds

        val sinceLastPrompt = if (state.lastReviewPromptAt == 0L) {
            Long.MAX_VALUE
        } else {
            (nowMs - state.lastReviewPromptAt).coerceAtLeast(0L)
        }
        val cooldownEligible = sinceLastPrompt >= minPromptCooldown.inWholeMilliseconds

        if (!ageEligible) {
            if (state.reviewInstallAt == 0L) {
                appCache.update { it.copy(reviewInstallAt = nowMs) }
            }
            return@withLock false
        }
        if (!cooldownEligible) return@withLock false

        val sinceNoForegroundUi = nowMs - lastNoForegroundUiAt
        if (lastNoForegroundUiAt != 0L &&
            sinceNoForegroundUi < noForegroundUiBackoff.inWholeMilliseconds
        ) {
            return@withLock false
        }

        appCache.update {
            it.copy(
                reviewInstallAt = installAt,
                lastReviewPromptAt = nowMs,
            )
        }

        if (launcher.requestReview() == ReviewLaunchOutcome.NoForegroundUi) {
            lastNoForegroundUiAt = nowMs
            appCache.update { it.copy(lastReviewPromptAt = state.lastReviewPromptAt) }
            return@withLock false
        }

        true
    }

    companion object {
        /** Don't ask brand-new players — give them time to form an opinion. */
        val minInstallAge: Duration = 3.days

        /**
         * In-app floor between prompts. Independent of (and stricter than)
         * the OS's annual quota. 30d matches Apple's documented behavior
         * for `SKStoreReviewController` and gives the Play side a similar
         * shape.
         */
        val minPromptCooldown: Duration = 30.days

        /**
         * How long to stop re-trying the platform after it reported no
         * window to draw in. Long enough to collapse a celebration
         * burst, short enough that a user who comes back to the app
         * still gets the next positive moment.
         */
        val noForegroundUiBackoff: Duration = 1.minutes
    }
}
