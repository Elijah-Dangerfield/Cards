package com.dangerfield.cards.libraries.cards.impl

import android.app.Activity
import android.content.Context
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logOnFailure
import com.dangerfield.cards.libraries.cards.ReviewPrompter
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
 * In any of those cases this returns silently. That's the contract per Play's
 * docs — the OS owns the visibility decision.
 *
 * Requires the foreground [Activity], which this binding gets via the
 * [ActivityProvider] contract that the host app supplies.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class AndroidReviewPrompter(
    private val context: Context,
    private val activityProvider: ActivityProvider,
) : ReviewPrompter {

    override suspend fun requestReview() {
        Catching {
            val activity = activityProvider.currentActivity()
                ?: error("No foreground Activity available for in-app review.")
            val manager = ReviewManagerFactory.create(context)
            val info = manager.requestReviewFlow().asDeferredOrNull()
                ?: error("Play returned a null ReviewInfo.")
            suspendCancellableCoroutine { cont ->
                manager.launchReviewFlow(activity, info).addOnCompleteListener {
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }.logOnFailure { "In-app review prompt failed (non-fatal)" }
    }
}

/**
 * Host-app contract for handing the foreground [Activity] to bindings that
 * need it. Implement once in `:apps:compose` (`androidMain`) — typically by
 * watching the Application's `ActivityLifecycleCallbacks`.
 */
interface ActivityProvider {
    fun currentActivity(): Activity?
}

private suspend fun <T> com.google.android.gms.tasks.Task<T>.asDeferredOrNull(): T? =
    suspendCancellableCoroutine { cont ->
        addOnSuccessListener { result -> if (cont.isActive) cont.resume(result) }
        addOnFailureListener { _ -> if (cont.isActive) cont.resume(null) }
        addOnCanceledListener { if (cont.isActive) cont.resume(null) }
    }
