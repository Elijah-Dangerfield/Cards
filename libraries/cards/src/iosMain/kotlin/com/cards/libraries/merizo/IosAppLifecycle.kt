package com.dangerfield.cards.libraries.cards

import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.internal.SynchronizedObject
import kotlinx.coroutines.internal.synchronized
import me.tatarka.inject.annotations.Inject
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.darwin.NSObjectProtocol
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.collections.LinkedHashSet

@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
@OptIn(InternalCoroutinesApi::class)
class IosAppLifecycle : AppLifecycle {

    private val notificationCenter = NSNotificationCenter.defaultCenter
    private val observerTokens = mutableListOf<NSObjectProtocol>()
    private val observerLock = SynchronizedObject()
    private val observers = LinkedHashSet<AppLifecycleObserver>()

    override fun addObserver(observer: AppLifecycleObserver) {
        synchronized(observerLock) {
            val wasEmpty = observers.isEmpty()
            observers.add(observer)
            if (wasEmpty) {
                registerLocked()
            }
        }
    }

    override fun removeObserver(observer: AppLifecycleObserver) {
        synchronized(observerLock) {
            observers.remove(observer)
            if (observers.isEmpty()) {
                unregisterLocked()
            }
        }
    }

    private fun notifyForegroundObservers() {
        notify { it.onEnterForeground() }
    }

    private fun notifyBackgroundObservers() {
        notify { it.onEnterBackground() }
    }

    private inline fun notify(invocation: (AppLifecycleObserver) -> Unit) {
        val snapshot = synchronized(observerLock) { observers.toList() }
        snapshot.forEach(invocation)
    }

    private fun registerLocked() {
        if (observerTokens.isNotEmpty()) return
        listOf(
            UIApplicationDidBecomeActiveNotification,
        ).forEach { name ->
            observerTokens += notificationCenter.addObserverForName(
                name = name,
                `object` = null,
                queue = NSOperationQueue.mainQueue,
            ) { _: NSNotification? ->
                notifyForegroundObservers()
            }
        }

        listOf(
            UIApplicationDidEnterBackgroundNotification,
        ).forEach { name ->
            observerTokens += notificationCenter.addObserverForName(
                name = name,
                `object` = null,
                queue = NSOperationQueue.mainQueue,
            ) { _: NSNotification? ->
                notifyBackgroundObservers()
            }
        }
    }

    private fun unregisterLocked() {
        if (observerTokens.isEmpty()) return
        observerTokens.forEach { token ->
            notificationCenter.removeObserver(token)
        }
        observerTokens.clear()
    }
}
