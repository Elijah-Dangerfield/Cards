package com.dangerfield.cards.libraries.flowroutines

import androidx.lifecycle.ViewModelStore
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.core.logging.LogEntry
import com.dangerfield.cards.libraries.core.logging.LogId
import com.dangerfield.cards.libraries.core.logging.LogLevel
import com.dangerfield.cards.libraries.core.logging.LogTree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SEAViewModelClearTest {

    private val recorder = RecordingTree()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        KLog.plant(recorder)
    }

    @AfterTest
    fun tearDown() {
        KLog.uproot(recorder)
        Dispatchers.resetMain()
    }

    private data class PlainState(val count: Int = 0)

    private class Vm : SEAViewModel<PlainState, Unit, Unit>(initialStateArg = PlainState()) {
        override suspend fun handleAction(action: Unit) = Unit
    }

    // ENG-27: onCleared used to write the state object into a SavedStateHandle.
    // A plain data-class state fails the handle's savable-type validation, so
    // every VM clear logged an IllegalArgumentException error (FeedbackState x2
    // in session 9f552c69) — and nothing ever read the value back, because the
    // default handle was never platform-wired. This pins the silence.
    @Test
    fun clearingViewModel_withPlainDataClassState_logsNoError() = runTest {
        val store = ViewModelStore()
        store.put("vm", Vm())

        store.clear()

        assertTrue(
            recorder.errors.isEmpty(),
            "clearing a VM must not log errors, but saw: ${recorder.errors.map { it.message }}",
        )
    }

    private class RecordingTree : LogTree() {
        val errors = mutableListOf<LogEntry>()
        override fun log(entry: LogEntry): LogId? {
            if (entry.level == LogLevel.Error) errors += entry
            return null
        }
    }
}
