# CARDS-BW — progression sync has been wedged in a self-worsening loop for one real Android player since 2026-08-21

**Sentry:** [CARDS-BW](https://elijah-dangerfield.sentry.io/issues/CARDS-BW) ·
`HttpRequestTimeoutException`, level `error`, mechanism `chained` · 26 events, 1 user, first seen
**2026-08-21T15:42:28Z**, last seen **2026-08-28T10:28:44Z**, status `unresolved`.

> `Request timeout has expired [url=https://cards-server-prod.fly.dev/v1/me/progression/sync, request_timeout=30000 ms]`

## Who

| | |
|---|---|
| release | `cards@0.1.0+1026`, dist `1026`, `commit_sha 4ea79519ef9c` (tag `v0.1.0`) |
| environment | `store-android-release` (`release_channel=store`) |
| device | Pixel 7, Android 16, `installerStore=com.android.vending`, `isSideLoaded=false`, `rooted=no` |
| install | `6a17639a-01f7-4ba3-a571-495b668034d3` — **one install, all 26 events** |
| user | `d212d310-5158-46a7-8f75-a20369f7d0e5` |
| route | `PlayBotsRoute` ×25, `PublicSearchingRoute` ×1 |

A retail Play install, not an emulator and not a side-load. This is not the wiki's known-benign
"one-off `net.backend_unreachable` from a single install": it is 26 timeouts across 14 distinct
sessions over 8 days, always the same endpoint, on a device whose other calls succeed.

## The server is not unreachable — it is answering after 5 to 8 minutes

The client blames the network (`net.backend_unreachable`, `error_kind=timeout`). The server log
says otherwise. Same install, same sessions, `{service_name="cards-server",
deployment_environment="prod"} |~ "progression"`:

```
200 OK: POST - /v1/me/progression/sync in 306407ms   install 6a17639a  2026-08-25
200 OK: POST - /v1/me/progression/sync in 441853ms   install 6a17639a  2026-08-26
200 OK: POST - /v1/me/progression/sync in 501323ms   install 6a17639a  2026-08-28
```

Every one returns **200 OK**. The work completes; the client gave up at 30 s. And the duration
climbs monotonically across the week — 306 s → 442 s → 501 s — which is the signature of a backlog
that grows every time the flush fails.

Everyone else on the same endpoint in the same window: 405 ms, 414 ms, 472 ms, 587 ms, 625 ms,
806 ms, 913 ms, 1030 ms, 1172 ms, 2918 ms — plus **16.7 s and 35.3 s** from the two next-busiest
installs. This is not a sick endpoint. It is an endpoint whose cost is linear in one user's
unsynced backlog, and the top of the population has already crossed the client's 30 s timeout.

## The numbers (prod Postgres, `xp_events`)

| | |
|---|---|
| rows in `xp_events` | 4,477 across 20 users |
| this user | **2,703** — 60% of the whole table |
| their first / last row | 2026-08-21 12:29Z / 2026-08-28 10:36Z |
| second-largest user | 669 rows |

501,323 ms ÷ 2,703 events ≈ **185 ms per event**, which is exactly one Supabase round trip per
statement. The server is doing per-event work in a loop.

## Root cause — two halves, both required

**Server: one full DB transaction per event.**
[`ProgressionRoutes.kt:74`](../../../apps/server/src/main/kotlin/com/cards/server/routes/ProgressionRoutes.kt)
does `body.events.map { repository.applyXp(...) }` — a serial `map`, one call per event. Each
`applyXp` in
[`PostgresProgressionRepository.kt:72`](../../../apps/server/src/main/kotlin/com/cards/server/data/PostgresProgressionRepository.kt)
opens its own `database.transaction { }` and issues ~4 statements (read progression, exists-check,
insert, update total). 2,703 events × 4 statements × ~46 ms ≈ 500 s. It is the same cost on a
replay: `eventExists` still runs per event, so re-sending an already-applied batch costs full
price.

**Client: the flush is unbounded and never converges.**
[`ProgressionRepositoryImpl.sync()`](../../../libraries/cards/impl/src/commonMain/kotlin/com/cards/libraries/cards/impl/ProgressionRepositoryImpl.kt)
calls `xpEventDao.getUnsynced()` — no `LIMIT`
([`XpEventDao.kt:41`](../../../libraries/cards/storage/src/commonMain/kotlin/com/cards/libraries/cards/storage/db/XpEventDao.kt))
— and posts every pending row in one request. Rows are only marked synced *after* the response
parses. The response never arrives, so nothing is marked, so the next flush sends the same rows
plus everything earned since. Worse, the call carries `RetryPolicy.idempotent()`, so a single user
action fans out into several 30 s attempts, each of which starts another multi-minute server
request — we logged 6 to 9 of them per session.

The loop: play → batch grows → sync takes longer → still times out at 30 s → nothing marked
synced → batch grows. There is no exit.

## What it costs

- **The user.** Their local XP ledger has been unreconciled for 8 days. Level-up chip rewards are
  minted server-side inside this route, but the client only re-pulls the wallet when it reads
  `walletBalance` off a response it never receives, so any reward stays invisible until an
  unrelated wallet-sync edge.
- **The server.** A single-writer Fly machine holds a request, a coroutine, and a Hikari connection
  for 5 to 8 minutes at a time, several concurrently, for one player. At 20 users this is survivable
  headroom; it is not a property that scales.
- **The population.** Crossover is somewhere around 200 events — roughly a week of steady play.
  The 669-row user already logs 35 s syncs. Every engaged player walks into this.

**Money is safe.** On a replay every event returns `AlreadyApplied`, so `lastTotal` never moves,
so `RewardChips.rewardedLevelsCrossed` finds no crossing and mints nothing. The per-level ledger
key dedupes on top of that. No XP is lost either — all 2,703 rows are committed server-side. What
is broken is convergence and cost, not correctness.

## Why nothing caught it

Every one of these requests logs `200 OK` at INFO. There is no latency alert (A1–A8 cover ledger
drift, Fly/Supabase down, backend-unreachable, purchase failures, OOM, silence, dropped SKUs — none
covers *slow but successful*), and the RED panels on `dc-infra` don't chart server-side p99 for this
route. An 8-minute request is invisible to the whole suite. That gap is filed separately as ENG-46.

## Working theory → the fix

Both halves, or it doesn't converge:

1. **Server** — do the batch in one transaction: a single batch insert with
   `ON CONFLICT (user_id, idempotency_key) DO NOTHING`, then one `UPDATE` of the total by the sum of
   the rows that actually inserted. Two statements for the whole batch instead of ~4N. The response
   still needs per-key `Applied` / `AlreadyApplied`, which the insert's returned keys give you.
2. **Client** — page the flush: `getUnsynced(limit)`, mark each page synced as it lands, loop until
   drained. That makes an existing backlog drain incrementally instead of never, and bounds any
   single request regardless of what the server does.

The same unbounded-flush shape exists in `PlayStyleEventDao.getUnsynced()`,
`PlayerStatEventDao.getUnsynced()`, and `AchievementDao.getUnsyncedEarned()`. They haven't fired
because they're lower-volume, not because they're safe. Fix the class.

**Regression guards (test-first):** a server test that a 2,000-event batch completes in one
transaction and stays fast; a client test that a backlog larger than one page fully drains across
repeated `sync()` calls and marks every row.

Filed as **ENG-45 `[P0]`**.
