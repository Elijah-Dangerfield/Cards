package com.dangerfield.cards.libraries.telemetry.impl

import android.app.ApplicationExitInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class PreviousExitReasonMappingTest {

    @Test
    fun crashes_mapToCrash() {
        assertEquals(PreviousExit.Crash, previousExitForReason(ApplicationExitInfo.REASON_CRASH))
        assertEquals(PreviousExit.Crash, previousExitForReason(ApplicationExitInfo.REASON_CRASH_NATIVE))
    }

    @Test
    fun anr_mapsToAnr() {
        assertEquals(PreviousExit.Anr, previousExitForReason(ApplicationExitInfo.REASON_ANR))
    }

    @Test
    fun lowMemory_mapsToOom() {
        assertEquals(PreviousExit.Oom, previousExitForReason(ApplicationExitInfo.REASON_LOW_MEMORY))
    }

    @Test
    fun userAndSelfExits_mapToClean() {
        assertEquals(PreviousExit.Clean, previousExitForReason(ApplicationExitInfo.REASON_EXIT_SELF))
        assertEquals(PreviousExit.Clean, previousExitForReason(ApplicationExitInfo.REASON_USER_REQUESTED))
        assertEquals(PreviousExit.Clean, previousExitForReason(ApplicationExitInfo.REASON_USER_STOPPED))
        assertEquals(PreviousExit.Clean, previousExitForReason(ApplicationExitInfo.REASON_SIGNALED))
    }

    @Test
    fun everythingElse_mapsToUnknown() {
        assertEquals(PreviousExit.Unknown, previousExitForReason(ApplicationExitInfo.REASON_UNKNOWN))
        assertEquals(PreviousExit.Unknown, previousExitForReason(ApplicationExitInfo.REASON_DEPENDENCY_DIED))
        assertEquals(PreviousExit.Unknown, previousExitForReason(ApplicationExitInfo.REASON_INITIALIZATION_FAILURE))
        assertEquals(PreviousExit.Unknown, previousExitForReason(ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE))
        assertEquals(PreviousExit.Unknown, previousExitForReason(ApplicationExitInfo.REASON_OTHER))
    }
}
