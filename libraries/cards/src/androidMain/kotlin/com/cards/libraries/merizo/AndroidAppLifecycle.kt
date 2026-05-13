package com.dangerfield.cards.libraries.cards

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.dangerfield.cards.libraries.cards.AppLifecycle
import com.dangerfield.cards.libraries.cards.AppLifecycleObserver
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@ContributesBinding(AppScope::class, boundType = AppLifecycle::class)
@SingleIn(AppScope::class)
@Inject
class AndroidAppLifecycle(
    private val lifecycle: Lifecycle = ProcessLifecycleOwner.Companion.get().lifecycle,
) : AppLifecycle, DefaultLifecycleObserver {

    private val observerLock = Any()
    private val observers = LinkedHashSet<AppLifecycleObserver>()

    override fun addObserver(observer: AppLifecycleObserver) {
        val shouldRegister = synchronized(observerLock) {
            val wasEmpty = observers.isEmpty()
            observers.add(observer)
            wasEmpty
        }

        if (shouldRegister) {
            lifecycle.addObserver(this)
        }
    }

    override fun removeObserver(observer: AppLifecycleObserver) {
        val shouldUnregister = synchronized(observerLock) {
            observers.remove(observer)
            observers.isEmpty()
        }

        if (shouldUnregister) {
            lifecycle.removeObserver(this)
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        notify { it.onEnterForeground() }
    }

    override fun onStop(owner: LifecycleOwner) {
        notify { it.onEnterBackground() }
    }

    private inline fun notify(invocation: (AppLifecycleObserver) -> Unit) {
        val snapshot = synchronized(observerLock) { observers.toList() }
        snapshot.forEach(invocation)
    }
}