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

    /**
     * Records the current app session's correlation id on the crash-
     * reporting scope as a searchable `session_id` tag. Set it whenever the
     * session rolls (cold boot, background-rollover) so every subsequent
     * event — including user feedback — carries the id. The same value is
     * sent to the backend via `X-Session-Id`, so one id pulls a session's
     * frontend events and backend traces/logs together. Best-effort: a
     * no-op when crash reporting is disabled.
     */
    fun setSession(sessionId: String)

    /**
     * Records the install id on the crash-reporting scope as an `install_id`
     * tag — stable across sessions, useful for "all of this tester's
     * sessions." Best-effort; no-op when crash reporting is disabled.
     */
    fun setInstallId(installId: String)

    /**
     * Records the multiplayer room the user is currently in as a `room_code`
     * tag, or clears it when [code] is null/blank (left the room). Mirrors the
     * backend's `room.code` gameplay span attribute, so feedback filed during a
     * game pivots straight to that room's server traces and logs. Best-effort;
     * no-op when crash reporting is disabled.
     */
    fun setRoom(code: String?)

    fun captureUserFeedback(
        message: String,
        isBugReport: Boolean,
        eventId: String?,
        errorCode: Int?,
        email: String? = null,
    )
}
