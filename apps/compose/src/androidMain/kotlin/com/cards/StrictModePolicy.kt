package com.dangerfield.cards

import android.os.StrictMode

/**
 * Turns on StrictMode for debug builds.
 *
 * StrictMode watches for the two things that block the main thread long enough
 * to ANR without ever throwing: disk and network I/O where the UI runs. Those
 * are invisible on a fast development device with a warm page cache and a good
 * network, and then cost a real user on a cold cache and a bad connection five
 * seconds and a killed process. Failing loudly on a developer's machine is the
 * only cheap time to notice.
 *
 * **Logs, never dies.** `penaltyDeath()` is the tempting setting and the wrong
 * one to start with: the framework itself trips these policies (resource
 * loading, WebView init, some Play Services paths), so crashing on violation
 * mostly teaches people to delete StrictMode. Log first, drive the count to
 * zero, and only then consider tightening.
 *
 * Debug-only on purpose. StrictMode has real overhead and release builds should
 * never pay it — production ANRs are caught after the fact through
 * `PreviousExit.Anr`, which is a different job.
 */
internal fun enableStrictModeForDebugBuilds() {
    if (!BuildConfig.DEBUG) return

    StrictMode.setThreadPolicy(
        StrictMode.ThreadPolicy.Builder()
            .detectDiskReads()
            .detectDiskWrites()
            .detectNetwork()
            .penaltyLog()
            .build(),
    )

    StrictMode.setVmPolicy(
        StrictMode.VmPolicy.Builder()
            // Unclosed cursors, streams and sockets. A leaked connection is
            // both a resource leak and, once the pool is exhausted, a stall.
            .detectLeakedClosableObjects()
            .detectLeakedSqlLiteObjects()
            // An Activity the GC can't collect keeps its whole view tree and
            // every bitmap in it alive, which is how a session drifts toward
            // memory pressure and the OOM killer.
            .detectActivityLeaks()
            .penaltyLog()
            .build(),
    )
}
