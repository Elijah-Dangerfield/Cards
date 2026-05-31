package com.dangerfield.cards.libraries.cards

interface Telemetry {
    fun initialize()

    fun setUser(
        email: String?,
        name: String?,
        id: String?
    )

    /**
     * Records the user's current navigation route on the crash-reporting
     * scope as both a searchable tag and a visible extra (`route`). Set it
     * eagerly on every navigation, NOT at event time.
     *
     * Why on the scope rather than in a `beforeSend` hook: the SDK persists
     * the scope to disk the moment it changes, and native crashes are turned
     * into events on the *next launch* using that persisted scope. So a route
     * written here is frozen at the instant the user was on it — a crash on
     * this route carries this route, even though it's transmitted later. A
     * `beforeSend` callback, by contrast, runs at next-launch for crashes and
     * would read a stale/empty route.
     *
     * The value sticks until the next navigation overwrites it, and any single
     * event may override it by setting its own `route` tag (a per-event local
     * scope wins over this global one). Best-effort: a no-op when crash
     * reporting is disabled.
     */
    fun setCurrentRoute(route: String)

    fun captureUserFeedback(
        message: String,
        isBugReport: Boolean,
        eventId: String?,
        errorCode: Int?,
        email: String? = null,
    )
}
