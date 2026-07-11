package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.core.AutoInit
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.AppEventBus
import com.dangerfield.cards.libraries.cards.AppEventListener
import com.dangerfield.cards.libraries.cards.AppLifecycle
import com.dangerfield.cards.libraries.cards.AppLifecycleObserver
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.internal.SynchronizedObject
import kotlinx.coroutines.internal.synchronized
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.concurrent.Volatile

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = AppEventBus::class)
@ContributesBinding(AppScope::class, boundType = AutoInit::class, multibinding = true)
@Inject
class AppEventDispatcher(
    private val listeners: Set<AppEventListener>,
    appLifecycle: AppLifecycle,
) : AppEventBus, AutoInit {
    private val logger = KLog.withTag("AppEventDispatcher")
    private val lifecycleObserver = object : AppLifecycleObserver {
        override fun onEnterForeground() = handleForegroundEntry()
        override fun onEnterBackground() = handleBackgroundEntry()
    }
    @OptIn(InternalCoroutinesApi::class)
    private val bootLock = SynchronizedObject()
    @Volatile
    private var hasDispatchedColdBoot = false

    // replay = 1 so a collector that subscribes during boot still catches the
    // event that fired just before it attached. Buffered + DROP_OLDEST so
    // tryEmit never blocks the dispatch thread or a publisher.
    private val events = MutableSharedFlow<AppEvent>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    // The replay-free sibling for edge semantics — a re-fire trigger reacting
    // to a replayed pre-subscribe event would spuriously double-fire.
    private val liveEvents = MutableSharedFlow<AppEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    init {
        appLifecycle.addObserver(lifecycleObserver)
    }

    override fun dispatch(event: AppEvent) {
        KLog.i("App Event: $event")
        events.tryEmit(event)
        liveEvents.tryEmit(event)
        notifyListeners(event)
    }

    override fun eventStream(): Flow<AppEvent> = events.asSharedFlow()

    override fun liveEventStream(): Flow<AppEvent> = liveEvents.asSharedFlow()

    @OptIn(InternalCoroutinesApi::class)
    private fun handleForegroundEntry() {
        val events = synchronized(bootLock) {
            val isColdBoot = !hasDispatchedColdBoot
            if (isColdBoot) {
                hasDispatchedColdBoot = true
            }
            listOf(
                if (isColdBoot) AppEvent.ColdBoot else AppEvent.WarmBoot,
                AppEvent.OnForeground(isColdBoot = isColdBoot)
            )
        }

        events.forEach { event ->
            dispatch(event)
        }
    }

    private fun handleBackgroundEntry() {
        dispatch(AppEvent.OnBackground)
    }

    private fun notifyListeners(event: AppEvent) {
        listeners.forEach { listener ->
            Catching {
                when (event) {
                    is AppEvent.ColdBoot -> listener.onColdBoot(event)
                    is AppEvent.WarmBoot -> listener.onWarmBoot(event)
                    is AppEvent.OnForeground -> listener.onForeground(event)
                    is AppEvent.OnBackground -> listener.onBackground(event)
                    is AppEvent.UserChanged -> listener.onUserChanged(event)
                    is AppEvent.ConnectivityRegained -> listener.onConnectivityRegained(event)
                }
            }.onFailure { throwable ->
                logger.e(throwable) {
                    "Listener ${listener::class.simpleName ?: listener::class} failed for ${event::class.simpleName ?: event::class}"
                }
            }
        }
    }
}