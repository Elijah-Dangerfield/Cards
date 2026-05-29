package com.dangerfield.cards.libraries.flowroutines

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.coroutines.CoroutineContext

interface DispatcherProvider {
    val io: CoroutineDispatcher

    val main: CoroutineDispatcher

    /**
     * Main dispatcher in `immediate` mode — runs synchronously if the caller
     * is already on the main thread, otherwise posts. Use this when a hop
     * onto Main shouldn't burn a frame (lifecycle-aware flow collection,
     * nav-graph attachment, anything that needs to land before the next
     * frame fires).
     */
    val mainImmediate: CoroutineDispatcher

    val default: CoroutineDispatcher

    val unconfined: CoroutineDispatcher
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultDispatcherProvider @Inject constructor() : DispatcherProvider {
    override val io: CoroutineDispatcher
        get() = Dispatchers.IO

    override val main: CoroutineDispatcher
        get() = Dispatchers.Main

    override val mainImmediate: CoroutineDispatcher
        get() = Dispatchers.Main.immediate

    override val default: CoroutineDispatcher
        get() = Dispatchers.Default

    override val unconfined: CoroutineDispatcher
        get() = Dispatchers.Unconfined
}

@SingleIn(AppScope::class)
class AppCoroutineScope @Inject constructor(
    dispatcherProvider: DispatcherProvider
) : CoroutineScope {

    private val job = SupervisorJob()

    override val coroutineContext: CoroutineContext = job + dispatcherProvider.default
}
