package com.dangerfield.cards.libraries.networking

/**
 * Globally-unique id for the current app session, attached to every request
 * via the `X-Session-Id` header (see [ClientHeaders.HEADER_SESSION_ID]).
 *
 * A "session" is a user-perceptible run of the app: it starts on cold boot and
 * rolls over after the app has been backgrounded long enough (see the
 * `SessionTracker` in `:libraries:cards`). Each session gets a fresh UUID.
 *
 * The same value is also set on the crash-reporting scope client-side, so one
 * id correlates a session's **frontend** events (Sentry) with its **backend**
 * traces and logs (the server stamps the header onto its OTel spans and log
 * records). That's the pivot for "a tester filed feedback — show me everything
 * that happened in that session."
 *
 * Unlike [InstallIdProvider] (which hydrates from disk and can be `null`
 * early in boot), the session id is in-memory and available immediately, so
 * implementations should always return a value. Implementations MUST be cheap
 * and non-suspending — they're called on every outgoing request. The
 * production impl is `SessionTrackerImpl` in `:libraries:cards:impl`.
 */
interface SessionIdProvider {
    /** The current session's correlation UUID. */
    fun current(): String
}
