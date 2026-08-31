# CARDS-BX — one 401 on `/v1/me/messages/sync` during a warm-boot token race (no action)

**Sentry:** [CARDS-BX](https://elijah-dangerfield.sentry.io/issues/CARDS-BX) ·
`ClientRequestException`, level `error` · **1 event, 1 user**, first = last =
**2026-08-25T16:06:09Z**.

> `Client request(POST .../v1/me/messages/sync) invalid: 401 . Text: {"error":{"code":"unauthorized","message":"Missing or invalid access token"}}`

| | |
|---|---|
| release | `cards@0.1.0+1026`, `store-android-release` |
| device | motorola edge 2025, Android 16, `installerStore=com.android.vending`, not side-loaded |
| install / user | `453efa77-268c-42d8-89d9-4c06f8bad3d8` / `bd143709-61c8-4d40-b0c6-e3aeba2f298c` |
| route | `ProfileRoute` |

## The session story

The breadcrumbs read as a warm-boot ordering race, not a broken auth state:

1. Five repositories in a row log `sync deferred: device offline` (inventory, equipment,
   progression, achievement, chips).
2. `SessionTracker` starts session 2 with `reason=BackgroundRollover`; `app.foregrounded`
   (`cold_start=false`); `GuestSessionHealer … SKIP_HAS_SESSION` — the session is intact.
3. `NETWORK_CAPABILITIES_CHANGED` → wifi, 30 Mbps down, signal -46 dBm. The device is back online.
4. `AuthTokenProvider` warns, then `NetworkCall` warns, then the 401.

So: the app woke from background, the repositories had already given up as offline, the network
returned, and `messages/sync` went out on the edge before the access token finished refreshing.

## Why no action

- One occurrence, one user, in a 3-day-old issue that has not repeated.
- The same install's other authenticated calls succeed either side of it — its
  `/v1/me/progression/sync` calls on 2026-08-27 returned 200 in 405 ms and 414 ms.
- Nothing is lost: message sync is a repeating pulse, and the next foreground edge re-runs it with
  a valid token.
- The session survives (`SKIP_HAS_SESSION`); this is not the AUTH-30 class where a boot destroys a
  session it can't verify.

## The one thing worth noting

This 401 reached Sentry at all, which is mildly interesting given ENG-44's `isExpectedClientError()`
filters `401`/`403` by status for every telemetry sink. It escapes because Ktor's
`DefaultResponseValidation` throws `ClientRequestException` before anything routes through the log
tree. That's the same seam the open backlog note about ENG-44's blanket 401/403 carve-out is about
(`docs/backlog.md`, 2026-08-20) — no new item; the existing note already owns the question of which
401s deserve to be loud.

**Disposition:** no action. Resolved in Sentry. Re-open if it recurs across multiple installs, or
if a 401 here is ever observed ending a session.
