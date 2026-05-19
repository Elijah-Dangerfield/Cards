package com.dangerfield.cards.libraries.cards

sealed class AppEvent {
    data object ColdBoot : AppEvent()
    data object WarmBoot : AppEvent()
    data class OnForeground(val isColdBoot: Boolean) : AppEvent()
    data object OnBackground : AppEvent()

    /**
     * The user explicitly signed out, OR completed a delete-account flow,
     * OR the local session was forcibly invalidated (e.g. JWT revoked).
     *
     * Every repo that holds device-local user data subscribes and clears
     * its own table — the identity layer doesn't know about chips, XP,
     * inventory, equipment, etc. and shouldn't have to. The listener
     * pattern keeps the cleanup ownership where the data is.
     *
     * Listeners must run their clears synchronously (the dispatcher
     * wraps each call in Catching{} so a single slow / failing clear
     * doesn't poison the rest), and must NOT trigger network work —
     * we already have no session, so any networked call would 401.
     */
    data object SignedOut : AppEvent()
}

interface AppEventListener {
    fun onColdBoot(event: AppEvent.ColdBoot) {}
    fun onWarmBoot(event: AppEvent.WarmBoot) {}
    fun onForeground(event: AppEvent.OnForeground) {}
    fun onBackground(event: AppEvent.OnBackground) {}
    fun onSignedOut(event: AppEvent.SignedOut) {}
}

/**
 * Public dispatch surface for [AppEvent]. The concrete
 * [com.dangerfield.cards.libraries.cards.impl.AppEventDispatcher] handles
 * boot/foreground/background lifecycle implicitly; explicit events
 * (currently just [AppEvent.SignedOut]) are dispatched by callers that
 * hold this bus.
 *
 * Lives in the api module so feature impls + libraries that can't see
 * the dispatcher's impl module can still publish events.
 */
interface AppEventBus {
    fun dispatch(event: AppEvent)
}

interface AppLifecycleObserver {
    fun onEnterForeground()
    fun onEnterBackground()
}

interface AppLifecycle {
    fun addObserver(observer: AppLifecycleObserver)
    fun removeObserver(observer: AppLifecycleObserver)
}


