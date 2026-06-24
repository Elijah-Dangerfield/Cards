# In-flight (worker handoff log)

## feat(shop): cut XP boost to 5 min at 1000 chips (GAME-5)

**Problem:** Owner directive — the 2x XP boost ran 30 minutes for 5000 chips; it should be a 5-minute window costing 1000 chips.
**Approach:** Flipped `XP_BOOST_DEFAULT_DURATION_MS` to 5 min (all banner/badge/countdown fractions read this constant, so they follow automatically). Repriced the catalog via a new append-only migration `V71__xp_boost_5min_1000_chips.sql` (server is the source of truth for the live shop) and updated the duration copy in `subtitle/description` locales. Swept user-facing copy in strings.xml + the preview catalog + comments.
**Reviewer notes:** Price lives in the DB catalog row, not a Kotlin constant — the preview fallback catalog and the shop VM test fixture are the only client-side prices, both updated to 1000. The `profile_boost_confirm_message` string still uses `\'` escapes (pre-existing AGENTS.md violation); left untouched to keep this change scoped.

## fix(rooms): bound room-socket reconnect storm (MP-8)

**Problem:** When the sole other human left a 2-player room, the client socket wedged into an unbounded connect→drop→reconnect loop (attempt stuck at 1, no give-up) against a half-open server socket — the user could only escape by mashing Back (CARDS-37).
**Approach:** `ReconnectingRoomSocket` now only resets its reconnect counter once a session is *healthy* (delivered ≥1 decodable frame). Frame-less drops keep climbing the backoff and, after `MAX_RECONNECT_ATTEMPTS` (6), terminate with a new `ClosedReason.ReconnectFailed`. Chose "delivered a frame" over a time-based health window because it needs no injected clock and a half-open socket delivers nothing — easy to assert in the existing StandardTestDispatcher tests. Decision logged in `docs/decisions.md`.
**Reviewer notes:** New terminal `ClosedReason.ReconnectFailed` wired into the two exhaustive `when(reason)` consumers (`LobbyViewModel` → new `LobbyError.ConnectionLost` + string; `PublicSearchingViewModel` → folded into the `Rejected` connection-error branch). `RemotePokerSession` already fans any non-Cancelled close to the play-screen pop, so the play screen pops correctly. The server-side root (room status never dropping back to Lobby after the sole-human-left rebound, leaving the socket half-open) is untouched — this is the client reliability half only.
**Deferred:** (1) Ceiling on the *handshake-retry* path (5xx / transport handshake failures still retry unboundedly) — kept the existing `consecutiveFailures_incrementAttemptCounter` contract; reviewer please triage whether to unify. (2) Server-side half-open-socket fix (shared root with the backlog "$0 buy-in + 409 after sole-human-left rebound" item) — left in backlog.
