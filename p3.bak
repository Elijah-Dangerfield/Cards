package com.dangerfield.cards.libraries.telemetry.impl

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.dangerfield.cards.libraries.core.Catching
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Reads the most recent `ApplicationExitInfo` for our package — which at
 * launch time is how the *previous* run ended, since the current process
 * hasn't exited yet. API 30+ only; older devices report Unknown.
 *
 * **Why the platform type never appears on this class.** `ApplicationExitInfo`
 * is API 30. A `Build.VERSION.SDK_INT` check only protects the *instructions*
 * inside a method; it cannot protect a type in a field or method **signature**,
 * because ART resolves those to execute the member at all. The first version of
 * this class held `private val exitInfo: ApplicationExitInfo? by lazy { ... }`,
 * whose synthetic getter returns `ApplicationExitInfo`, so on Android 10 the
 * getter threw `NoClassDefFoundError` before the guard inside it ever ran
 * (CARDS-CK, Android 10, `cards@0.5.0+1272`). It was caught by the dispatcher's
 * `Catching`, so nothing crashed — the app just silently stopped emitting
 * `app.launched` on every device below API 30, which is also where the memory
 * and ANR problems this telemetry exists to measure are most common.
 *
 * So: this class traffics only in [ExitRecord], which is plain data, and every
 * mention of the platform type is confined to [ExitInfoReader], a separate
 * class that is only ever loaded from behind the SDK check.
 * `AndroidPreviousExitProviderApiLevelTest` asserts that structurally.
 *
 * [previousExit] and [previousExitDetail] both answer from the same cached
 * record rather than two separate OS calls: the platform only keeps a short
 * history per package, so a second call could in principle return a different
 * record than the first and disagree with itself.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class AndroidPreviousExitProvider(
    private val context: Context,
) : PreviousExitProvider {

    private val record: ExitRecord? by lazy { readLatestExitRecord(context) }

    override fun previousExit(): PreviousExit =
        record?.let { previousExitForReason(it.reason) } ?: PreviousExit.Unknown

    override fun previousExitDetail(): PreviousExitDetail? = record?.let {
        PreviousExitDetail(
            importance = it.importance,
            pssKb = it.pssKb,
            rssKb = it.rssKb,
            description = it.description,
        )
    }
}

/**
 * The parts of `ApplicationExitInfo` we actually report, as plain data, so the
 * provider can hold one without naming the API-30 type. See the note on
 * [AndroidPreviousExitProvider].
 */
internal data class ExitRecord(
    val reason: Int,
    val importance: Int,
    val pssKb: Long,
    val rssKb: Long,
    val description: String?,
)

private fun readLatestExitRecord(context: Context): ExitRecord? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    // Only here, past the guard, does ExitInfoReader get loaded — and with it
    // the first and only reference to the platform type.
    return Catching { ExitInfoReader.latest(context) }.getOrNull()
}

@RequiresApi(Build.VERSION_CODES.R)
private object ExitInfoReader {
    fun latest(context: Context): ExitRecord? {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info: ApplicationExitInfo = activityManager
            .getHistoricalProcessExitReasons(context.packageName, NO_PID_FILTER, 1)
            .firstOrNull()
            ?: return null
        return ExitRecord(
            reason = info.reason,
            importance = info.importance,
            pssKb = info.pss,
            rssKb = info.rss,
            description = info.description,
        )
    }

    private const val NO_PID_FILTER = 0
}

/**
 * Safe to name `ApplicationExitInfo` here: `REASON_*` are compile-time
 * constants, so the compiler inlines the integers and the bytecode carries no
 * reference to the class. Keep them as constants — swapping any of these for a
 * non-constant member of `ApplicationExitInfo` would reintroduce CARDS-CK in a
 * place the structural test does not watch.
 */
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
