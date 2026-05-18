package com.dangerfield.cards.libraries.flowroutines.testing

import com.dangerfield.cards.libraries.flowroutines.DispatcherProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Base class for coroutine-driven unit tests in this codebase.
 *
 * Every test that touches a `ViewModel`, a `Flow`, or any suspend code should
 * extend this. Centralizing test-coroutine setup eliminates the bug-prone
 * boilerplate of `Dispatchers.setMain` / `resetMain` / scheduler wiring in
 * each test file and ensures every test on a given run uses the same dispatch
 * model.
 *
 * What you get:
 *
 *   - [Dispatchers.setMain] installed before each test, [resetMain] after.
 *     `viewModelScope` (which is `Dispatchers.Main.immediate`) routes through
 *     the test scheduler so `vm.takeAction(...)` propagates to state in the
 *     same virtual tick.
 *
 *   - [dispatchers]: a [DispatcherProvider] whose four dispatchers are all
 *     the same [TestDispatcher]. Pass it to production code that takes a
 *     [DispatcherProvider] so `withContext(dispatchers.default)` runs on the
 *     test scheduler instead of a real worker thread.
 *
 *   - [runUnitTest]: a [runTest] wrapper that cancels any leaked child
 *     coroutines at exit. Long-lived workers (the bot loop suspended on a
 *     `Channel.receive()`, an infinite flow collector) would otherwise
 *     trigger `UncompletedCoroutinesError` even when the assertions all pass.
 *
 * Default dispatcher is [UnconfinedTestDispatcher] — continuations run
 * eagerly, which mirrors how `viewModelScope` behaves under
 * `Dispatchers.Main.immediate` and avoids the constant
 * `runCurrent()` calls that `StandardTestDispatcher` would force. Override
 * [testDispatcher] in a subclass for the rare test that needs an explicit
 * dispatch-then-advance cadence.
 *
 * ```
 * class MyViewModelTest : CoroutineTest() {
 *     @Test
 *     fun loadsData() = runUnitTest {
 *         val vm = MyViewModel(repo, dispatchers)
 *         repo.emit(Foo(...))
 *         assertEquals(Foo(...), vm.state.foo)
 *     }
 * }
 * ```
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class CoroutineTest {

    /**
     * The single [TestDispatcher] every dispatch in the test routes through.
     * Override to swap in `StandardTestDispatcher()` when you need explicit
     * `runCurrent` / `advanceUntilIdle` control.
     */
    protected open val testDispatcher: TestDispatcher = UnconfinedTestDispatcher()

    /** [DispatcherProvider] for injection into production code under test. */
    protected val dispatchers: DispatcherProvider by lazy {
        TestDispatcherProvider(testDispatcher)
    }

    @BeforeTest
    fun installMainDispatcher() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    /**
     * Run a test body inside a [TestScope] backed by [testDispatcher].
     *
     * On exit (success OR failure), all child coroutines that were launched
     * into the test scope are cancelled and joined. This makes tests that
     * spin up long-lived workers (bot loops, infinite-flow collectors, retry
     * coroutines) safe by default — without it, `runTest` reports them as
     * `UncompletedCoroutinesError` and masks the real assertion result.
     *
     * Prefer [TestScope.backgroundScope] inside the block when you want
     * those semantics for a specific launch; this helper handles the
     * common case where production code itself owns the launches.
     */
    protected fun runUnitTest(
        timeout: Duration = DEFAULT_TIMEOUT,
        block: suspend TestScope.() -> Unit,
    ) = runTest(context = testDispatcher, timeout = timeout) {
        try {
            block()
        } finally {
            coroutineContext.job.children.toList().forEach { it.cancelAndJoin() }
        }
    }

    private companion object {
        val DEFAULT_TIMEOUT: Duration = 10.seconds
    }
}
