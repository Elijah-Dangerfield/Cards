package com.dangerfield.cards.libraries.telemetry.impl

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.dangerfield.cards.libraries.core.Catching
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Reads the most recent [ApplicationExitInfo] for our package — which at
 * launch time is how the *previous* run ended, since the current process
 * hasn't exited yet. API 30+ only; older devices report Unknown.
 *
 * [previousExit] and [previousExitDetail] both answer from the same cached
 * record ([exitInfo]) rather than two separate OS calls: the platform only
 * keeps a short history per package, so a second call could in principle
 * return a different record than the first and disagree with itself.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class AndroidPreviousExitProvider(
    private val context: Context,
) : PreviousExitProvider {

    private val exitInfo: ApplicationExitInfo? by lazy { readLatestExitInfo() }

    override fun previousExit(): PreviousExit =
        exitInfo?.let { previousExitForReason(it.reason) } ?: PreviousExit.Unknown

    override fun previousExitDetail(): PreviousExitDetail? = exitInfo?.let {
        PreviousExitDetail(
            importance = it.importance,
            pssKb = it.pss,
            rssKb = it.rss,
            description = it.description,
        )
    }

    private fun readLatestExitInfo(): ApplicationExitInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return Catching {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            activityManager
                .getHistoricalProcessExitReasons(context.packageName, NO_PID_FILTER, 1)
                .firstOrNull()
        }.getOrNull()
    }

    private companion object {
        const val NO_PID_FILTER = 0
    }
}

internal fun previousExitForReason(reason: Int): PreviousExit = when (reason) {
    ApplicationExitInfo.REASON_CRASH,
    ApplicationExitInfo.REASON_CRASH_NATIVE,
    -> PreviousExit.Crash

    ApplicationExitInfo.REASON_ANR -> PreviousExit.Anr

    ApplicationExitInfo.REASON_LOW_MEMORY -> PreviousExit.Oom

    // On API 30+ fatal native signals report REASON_CRASH_NATIVE and lmkd
    // kills report REASON_LOW_MEMORY, so a bare SIGNALED is the user or the
    // system stopping the process — not a failure we should count.
    ApplicationExitInfo.REASON_EXIT_SELF,
    ApplicationExitInfo.REASON_USER_REQUESTED,
    ApplicationExitInfo.REASON_USER_STOPPED,
    ApplicationExitInfo.REASON_SIGNALED,
    -> PreviousExit.Clean

    else -> PreviousExit.Unknown
}
