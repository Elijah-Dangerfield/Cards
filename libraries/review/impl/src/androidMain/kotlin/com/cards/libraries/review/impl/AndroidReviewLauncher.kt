package com.dangerfield.cards.libraries.review.impl

import android.content.Context
import com.dangerfield.cards.libraries.cards.ActivityProvider
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logOnFailure
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.review.ReviewLaunchOutcome
import com.dangerfield.cards.libraries.review.ReviewLauncher
import com.google.android.play.core.review.ReviewManagerFactory
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Play Core in-app review. The dialog will not show if:
 *   - The user is on a device without Play Services.
 *   - Play decides the user has been prompted recently.
 *   - The app's signing key doesn't match what Play knows about.
 *
 * In any of those cases this returns silently. That's the contract per
 * Play's docs — the OS owns the visibility decision.
 *
 * Requires the foreground Activity via the host-app-provided
 * [ActivityProvider] binding. A prompt requested from a coroutine can
 * land after the user left the screen or backgrounded the app, so a
 * null Activity is an ordinary outcome and reports
 * [ReviewLaunchOutcome.NoForegroundUi] rather than failing (CARDS-D0
 * threw here and spent the ask on a moment with nowhere to draw).
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class AndroidReviewLauncher(
    private val context: Context,
    private val activityProvider: ActivityProvider,
) : ReviewLauncher {

    override suspend fun requestReview(): ReviewLaunchOutcome {
        val activity = activityProvider.currentActivity() ?: run {
            KLog.withTag(LOG_TAG).i { "In-app review skipped: no foreground Activity." }
            return ReviewLaunchOutcome.NoForegroundUi
        }

        Catching {
            val manager = ReviewManagerFactory.create(context)
            val info = manager.requestReviewFlow().asDeferredOrNull()
                ?: error("Play returned a null ReviewInfo.")
            suspendCancellableCoroutine { cont ->
                manager.launchReviewFlow(activity, info).addOnCompleteListener {
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }.logOnFailure { "In-app review prompt failed (non-fatal)" }

        return ReviewLaunchOutcome.Requested
    }

    private companion object {
        const val LOG_TAG = "Review"
    }
}

private suspend fun <T> com.google.android.gms.tasks.Task<T>.asDeferredOrNull(): T? =
    suspendCancellableCoroutine { cont ->
        addOnSuccessListener { result -> if (cont.isActive) cont.resume(result) }
        addOnFailureListener { _ -> if (cont.isActive) cont.resume(null) }
        addOnCanceledListener { if (cont.isActive) cont.resume(null) }
    }
