package com.dangerfield.cards.libraries.flowroutines.testing

import com.dangerfield.cards.libraries.flowroutines.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher

/**
 * Drop-in [DispatcherProvider] for tests. All four dispatchers route to a
 * single [TestDispatcher] so the test scheduler controls every coroutine the
 * production code launches — including CPU-bound work that production routes
 * through `default` and IO work routed through `io`.
 *
 * Instantiated and exposed for free by [CoroutineTest]. Tests rarely construct
 * this directly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TestDispatcherProvider(
    val testDispatcher: TestDispatcher,
) : DispatcherProvider {
    override val io: CoroutineDispatcher get() = testDispatcher
    override val main: CoroutineDispatcher get() = testDispatcher
    override val mainImmediate: CoroutineDispatcher get() = testDispatcher
    override val default: CoroutineDispatcher get() = testDispatcher
    override val unconfined: CoroutineDispatcher get() = testDispatcher
}
