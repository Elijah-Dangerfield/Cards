package com.dangerfield.cards.libraries.telemetry.impl

import android.app.ActivityManager.RunningAppProcessInfo
import android.app.ApplicationExitInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class PreviousExitReasonMappingTest {

    @Test
    fun crashes_mapToCrash() {
        assertEquals(PreviousExit.Crash, previousExitForReason(ApplicationExitInfo.REASON_CRASH, RunningAppProcessInfo.IMPORTANCE_FOREGROUND))
        assertEquals(PreviousExit.Crash, previousExitForReason(ApplicationExitInfo.REASON_CRASH_NATIVE, RunningAppProcessInfo.IMPORTANCE_FOREGROUND))
    }

    @Test
    fun anr_mapsToAnr() {
        assertEquals(PreviousExit.Anr, previousExitForReason(ApplicationExitInfo.REASON_ANR, RunningAppProcessInfo.IMPORTANCE_FOREGROUND))
    }

    @Test
    fun lowMemoryWhileOnScreen_mapsToOom() {
        assertEquals(PreviousExit.Oom, previousExitForReason(ApplicationExitInfo.REASON_LOW_MEMORY, RunningAppProcessInfo.IMPORTANCE_FOREGROUND))
        assertEquals(PreviousExit.Oom, previousExitForReason(ApplicationExitInfo.REASON_LOW_MEMORY, RunningAppProcessInfo.IMPORTANCE_VISIBLE))
        assertEquals(PreviousExit.Oom, previousExitForReason(ApplicationExitInfo.REASON_LOW_MEMORY, RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE))
    }

    @Test
    fun lowMemoryWhileBackgrounded_isNotACrash() {
        assertEquals(PreviousExit.BackgroundKill, previousExitForReason(ApplicationExitInfo.REASON_LOW_MEMORY, RunningAppProcessInfo.IMPORTANCE_SERVICE))
        assertEquals(PreviousExit.BackgroundKill, previousExitForReason(ApplicationExitInfo.REASON_LOW_MEMORY, RunningAppProcessInfo.IMPORTANCE_CACHED))
    }

    @Test
    fun userAndSelfExits_mapToClean() {
        assertEquals(PreviousExit.Clean, previousExitForReason(ApplicationExitInfo.REASON_EXIT_SELF, RunningAppProcessInfo.IMPORTANCE_FOREGROUND))
        assertEquals(PreviousExit.Clean, previousExitForReason(ApplicationExitInfo.REASON_USER_REQUESTED, RunningAppProcessInfo.IMPORTANCE_FOREGROUND))
        assertEquals(PreviousExit.Clean, previousExitForReason(ApplicationExitInfo.REASON_USER_STOPPED, RunningAppProcessInfo.IMPORTANCE_FOREGROUND))
        assertEquals(PreviousExit.Clean, previousExitForReason(ApplicationExitInfo.REASON_SIGNALED, RunningAppProcessInfo.IMPORTANCE_FOREGROUND))
    }

    @Test
    fun everythingElse_mapsToUnknown() {
        assertEquals(PreviousExit.Unknown, previousExitForReason(ApplicationExitInfo.REASON_UNKNOWN, RunningAppProcessInfo.IMPORTANCE_FOREGROUND))
        assertEquals(PreviousExit.Unknown, previousExitForReason(ApplicationExitInfo.REASON_DEPENDENCY_DIED, RunningAppProcessInfo.IMPORTANCE_FOREGROUND))
        assertEquals(PreviousExit.Unknown, previousExitForReason(ApplicationExitInfo.REASON_INITIALIZATION_FAILURE, RunningAppProcessInfo.IMPORTANCE_FOREGROUND))
        assertEquals(PreviousExit.Unknown, previousExitForReason(ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE, RunningAppProcessInfo.IMPORTANCE_FOREGROUND))
        assertEquals(PreviousExit.Unknown, previousExitForReason(ApplicationExitInfo.REASON_OTHER, RunningAppProcessInfo.IMPORTANCE_FOREGROUND))
    }
}
