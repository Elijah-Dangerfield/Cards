package com.dangerfield.cards.libraries.cards

import kotlinx.coroutines.flow.Flow

sealed class AppEvent {
    data object ColdBoot : AppEvent()
    data object WarmBoot : AppEvent()
    data class OnForeground(val isColdBoot: Boolean) : AppEvent()
    data object OnBackground : AppEvent()

    /**
     * The active user identity changed. Fired by the auth layer at the single
     * point it knows the current user id moved — covering every transition:
     *
     *  - `null → X`  first sign-in / guest creation (nothing to clear).
     *  - `X → X`     **not fired** — claim/link keeps the same user; the guest's
     *                progress is theirs to keep.
     *  - `X → Y`     account switch — Y is now active; X's local data was already
     *                wiped (see below) before this event.
     *  - `X → null`  sign-out / delete / forced session invalidation.
     *
     * The actual wipe of the departing user's device-local data (Room tables,
     * profile mirror, account-scoped settings) is **not** a listener's job — it
     * runs through [UserScopedClearer] / [UserScopedDataReset] *before* the new
     * [com.dangerfield.cards.libraries.identity.auth.AuthState] is emitted, so a
     * reactive loader can't race the clear. This event is the *announcement*
     * after the fact, for side-effects that don't hold user-scoped storage
     * (telemetry user binding, dropping an on-screen message).
     *
     * Listeners must run synchronously and must NOT trigger network work.
     *
     * @param previous the user id that was active, or null if none.
     * @param current the user id now active, or null after sign-out / delete.
     */
    data class UserChanged(val previous: String?, val current: String?) : AppEvent() {
        /** True when this transition ended in no signed-in user (sign-out / delete). */
        val isSignedOut: Boolean get() = current == null
    }

    /**
     * Connectivity was just regained — an offline→online edge. Dispatched by
     * [com.dangerfield.cards.libraries.cards.impl.ConnectivityEdgeDispatcher],
     * which watches `AppState.isOffline`, drops the optimistic initial value,
     * debounces flapping, and only fires on the true→false transition (so an
     * online cold boot never emits one).
     *
     * This is a *data-freshness* trigger: identity self-heal, authed-call
     * retriggers, and offline outboxes hang their reconnect flush off it. Like
     * the other events, listeners must run synchronously and hand heavy work off
     * to their own scope.
     */
    data object ConnectivityRegained : AppEvent()
}

interface AppEventListener {
    fun onColdBoot(event: AppEvent.ColdBoot) {}
    fun onWarmBoot(event: AppEvent.WarmBoot) {}
    fun onForeground(event: AppEvent.OnForeground) {}
    fun onBackground(event: AppEvent.OnBackground) {}
    fun onUserChanged(event: AppEvent.UserChanged) {}
    fun onConnectivityRegained(event: AppEvent.ConnectivityRegained) {}
}

/**
 * Public dispatch surface for [AppEvent]. The concrete
 * [com.dangerfield.cards.libraries.cards.impl.AppEventDispatcher] handles
 * boot/foreground/background lifecycle implicitly; explicit events
 * (currently just [AppEvent.UserChanged]) are dispatched by callers that
 * hold this bus.
 *
 * Lives in the api module so feature impls + libraries that can't see
 * the dispatcher's impl module can still publish events.
 */
interface AppEventBus {
    fun dispatch(event: AppEvent)

    /**
     * The same events [dispatch] fans out to listeners, as a hot stream — for
     * consumers that prefer to react reactively (filter, combine, carry state
     * across events) instead of implementing [AppEventListener]. Inject [AppEvents]
     * rather than depending on the bus directly.
     */
    fun eventStream(): Flow<AppEvent>

    /**
     * Like [eventStream] but without the one-event replay: a subscriber sees
     * only events dispatched *after* it attached. This is the stream for
     * **edge** semantics (re-fire triggers) — a replayed edge is by definition
     * stale and re-acting to it double-fires. Consumers that need the no-miss
     * guarantee for state activation should key off a level (e.g.
     * `AuthRepository.observe`) instead, not off replayed edges.
     */
    fun liveEventStream(): Flow<AppEvent>
}

interface AppLifecycleObserver {
    fun onEnterForeground()
    fun onEnterBackground()
}

interface AppLifecycle {
    fun addObserver(observer: AppLifecycleObserver)
    fun removeObserver(observer: AppLifecycleObserver)
}


