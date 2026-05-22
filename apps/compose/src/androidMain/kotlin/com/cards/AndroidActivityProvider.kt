package com.dangerfield.cards

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.dangerfield.cards.libraries.cards.ActivityProvider
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.lang.ref.WeakReference

/**
 * Tracks the foreground [Activity] via [Application.ActivityLifecycleCallbacks]
 * so DI bindings (e.g. `AndroidReviewLauncher`) can launch flows that need a
 * real Activity reference.
 *
 * Held weakly so a backgrounded Activity can be GC'd. `currentActivity()`
 * returns null when the app is fully backgrounded.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = ActivityProvider::class)
@Inject
class AndroidActivityProvider(
    context: Context,
) : ActivityProvider, Application.ActivityLifecycleCallbacks {

    private var current: WeakReference<Activity> = WeakReference(null)

    init {
        (context.applicationContext as Application).registerActivityLifecycleCallbacks(this)
    }

    override fun currentActivity(): Activity? = current.get()

    override fun onActivityResumed(activity: Activity) {
        current = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (current.get() === activity) current = WeakReference(null)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
