package com.dangerfield.cards.libraries.cards

sealed class AppEvent {
    data object ColdBoot : AppEvent()
    data object WarmBoot : AppEvent()
    data class OnForeground(val isColdBoot: Boolean) : AppEvent()
    data object OnBackground : AppEvent()
}

interface AppEventListener {
    fun onColdBoot(event: AppEvent.ColdBoot) {}
    fun onWarmBoot(event: AppEvent.WarmBoot) {}
    fun onForeground(event: AppEvent.OnForeground) {}
    fun onBackground(event: AppEvent.OnBackground) {}
}

interface AppLifecycleObserver {
    fun onEnterForeground()
    fun onEnterBackground()
}

interface AppLifecycle {
    fun addObserver(observer: AppLifecycleObserver)
    fun removeObserver(observer: AppLifecycleObserver)
}


