# CARDS-BA — offline device floods Sentry with handled DarwinHttpRequestException errors

**Signal:** Sentry [CARDS-BA](https://elijah-dangerfield.sentry.io/issues/CARDS-BA) —
`DarwinHttpRequestException: NSURLErrorDomain Code=-1009 "The Internet connection appears to
be offline."` 59 events in 43 minutes (2026-07-22 19:14–19:57Z), substatus escalating.
Surfaced via the Pulse "Client errors (7d)" panel reading 85 — this issue is ~59 of that.

**Scope:** ONE device. Both sessions belong to install `91628081-01d8-4964-91c3-a03849d01886`
(QuietJack51, user 6d995d28, iPhone14,2, iOS 26.5, beta-ios-release cards@1.0+968 @ 0e1ec568,
dist 202607220924) — the owner's own 07-22 dogfood phone; session `52bf4517…` is the same
session that filed today's ROOM-20/ROOM-21 feedback. Sessions: 52bf4517 (44 events),
1d2a0add (15 events). Route HomeRoute, `handled=yes`, mechanism generic.

**What actually happened:** the device lost its network route
(`_NSURLErrorNWPathKey=unsatisfied (No network route)`, DNS resolved 0 endpoints from cache) —
airplane mode / dead spot — and background sync calls (seen: `POST
https://cards-server-prod.fly.dev/v1/equipment/sync`) kept firing and failing. Each expected
offline failure was captured to Sentry at error level, ~1.4 events/min sustained.

**Not an outage:** cards-server prod Loki shows 0 warn/error/fatal lines in 24h (444 scanned,
base stream live); no Grafana alert firing or pending (A1–A7, incl. A2 server-down and A4
clients-can't-reach-backend); Pulse crash-free 100%, Server UP. Nobody else affected.

**Working theory (defect worth fixing):** expected-offline network failures are treated as
reportable errors. Same signal-hygiene class as ENG-29 (AuthUnready noise, fixed on develop
1d70488d) — but this build post-dates that fix, so connectivity errors are a separate
unfiltered path. One phone in a tunnel should not be able to inflate the error panel and
create an "escalating" Sentry issue; it also risks masking a real spike behind noise, and the
retry cadence (~1.4/min from HomeRoute sync) may itself deserve backoff while offline.

**Fix direction:** classify NSURLError -1009 / connectivity-class `DarwinHttpRequestException`
(and the Android `UnknownHostException`/`ConnectException` equivalents) as offline, not error:
demote to breadcrumb/info before the SentryLogTree error path, and/or gate background sync
retries on reachability with backoff. Keep genuine unexpected network failures reportable.

**Disposition:** todo ENG-34 `[P2]` (2026-07-22). Sentry issue left unresolved until the fix
ships.
