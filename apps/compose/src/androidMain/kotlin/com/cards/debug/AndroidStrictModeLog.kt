package com.dangerfield.cards.debug

import android.content.Context
import android.os.Build
import android.os.StrictMode
import android.os.strictmode.Violation
import com.dangerfield.cards.libraries.core.BuildInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * [StrictModeLog] backed by StrictMode's `penaltyListener`, with the set of
 * already-seen violations persisted so "new" survives a restart.
 *
 * `penaltyListener` needs API 28; below that this records nothing and the whole
 * feature quietly does not exist, which is the right trade for a debug tool.
 * The listener fires on the violating thread — often the main thread, that being
 * the point — so it does as little as possible: hash a signature, bump a
 * counter, and get out.
 *
 * Violations arriving from the framework rather than from app code are dropped.
 * WebView init, resource loading and Play Services all trip these policies, and
 * a list you cannot act on is a list you stop opening.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class AndroidStrictModeLog(private val context: Context) : StrictModeLog {

    private val byLine = LinkedHashMap<String, StrictModeViolation>()
    private val state = MutableStateFlow<List<StrictModeViolation>>(emptyList())
    private val newCount = MutableStateFlow(0)

    override val violations: StateFlow<List<StrictModeViolation>> = state.asStateFlow()
    override val newViolationCount: StateFlow<Int> = newCount.asStateFlow()

    private val prefs by lazy {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /**
     * Arms StrictMode and routes violations here. Debug + API 28 only; a no-op
     * everywhere else so callers need no version check of their own.
     */
    fun install() {
        if (!BuildInfo.isDebug || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return

        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .penaltyListener(executor) { record(it) }
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedSqlLiteObjects()
                .detectActivityLeaks()
                .penaltyLog()
                .penaltyListener(executor) { record(it) }
                .build(),
        )
    }

    private fun record(violation: Violation) {
        val kind = violation::class.java.simpleName
        val origin = violation.stackTrace
            .firstOrNull { it.className.startsWith(APP_PACKAGE) }
            ?.let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
            ?: return // Framework-only trace: nothing here we could act on.

        val signature = "$kind@$origin"
        synchronized(byLine) {
            val existing = byLine[signature]
            byLine[signature] = if (existing != null) {
                existing.copy(count = existing.count + 1)
            } else {
                StrictModeViolation(
                    signature = signature,
                    kind = kind,
                    origin = origin,
                    count = 1,
                    isNew = signature !in seenSignatures(),
                )
            }
            publish()
        }
    }

    private fun publish() {
        val all = byLine.values.sortedByDescending { it.count }
        state.value = all
        newCount.value = all.count { it.isNew }
    }

    override fun markAllSeen() {
        synchronized(byLine) {
            if (byLine.isEmpty()) return
            prefs.edit().putStringSet(KEY_SEEN, seenSignatures() + byLine.keys).apply()
            byLine.keys.forEach { key ->
                byLine[key] = byLine.getValue(key).copy(isNew = false)
            }
            publish()
        }
    }

    /** Defensive copy: `getStringSet` hands back a set the prefs still own. */
    private fun seenSignatures(): Set<String> =
        prefs.getStringSet(KEY_SEEN, emptySet())?.toSet().orEmpty()

    private companion object {
        const val PREFS = "strict_mode_log"
        const val KEY_SEEN = "seen_signatures"
        const val APP_PACKAGE = "com.dangerfield.cards"
    }
}
