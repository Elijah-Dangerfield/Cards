@file:OptIn(ExperimentalObjCName::class)

package com.dangerfield.cards.libraries.cards

import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * Asks the platform to show its native in-app review prompt. Both stores
 * heavily throttle this — call it from a delighted-user moment (e.g. after a
 * successful task completion, or after N sessions) and don't sweat whether
 * the dialog actually appears. The OS decides.
 *
 * - iOS: `SKStoreReviewController.requestReview` — silently no-ops if the
 *   user has been prompted recently or has reviewed already.
 * - Android: Play Core ReviewManager — same throttling story.
 *
 * Caller decides the trigger. This interface intentionally doesn't bake in
 * "after N sessions" heuristics because every app's right moment is different.
 */
@ObjCName("ReviewPrompter", exact = true)
interface ReviewPrompter {
    /**
     * Request the OS to show the review prompt if it deems it appropriate.
     * Suspends until the prompt is dismissed (or immediately returns if the
     * OS chose not to show it).
     */
    suspend fun requestReview()
}
