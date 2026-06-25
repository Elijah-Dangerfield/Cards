# In-flight

## fix(profile): use Catching over runCatching in highlight pulse (ENG-3)

**Problem:** `MyItemsShelves`'s highlight-pulse `LaunchedEffect` wrapped `highlightRequester.bringIntoView()` in `runCatching`, the one client-side `runCatching` left in a main source set — it swallows `CancellationException` so a recompose that cancels the effect mid-call is silently eaten.
**Approach:** Swapped to `Catching {}` from `:libraries:core` (rethrows `CancellationException`). `rg runCatching` over client commonMain now only matches the `Catching` definition itself.
**Reviewer notes:** None.

## feat(server): server-authoritative player stats endpoints (PROG-1)

**Problem:** Hand counters, the no-bust streak, and per-bot wins lived only on the device, so account-switch / reinstall reset them and the stats screen + achievement bars read wrong on a second device.
**Approach:** Shipped the **server slice** of PROG-1 only, mirroring the play_style stack exactly: migration `V72__player_stats.sql` (aggregate + idempotent `(user_id, idempotency_key)` ledger), `PlayerStatsRepository` / `PostgresPlayerStatsRepository`, `PlayerStatsDto` + `PlayerStatsRoutes` (`GET /v1/me/stats`, `POST /v1/me/stats/sync`), DI in `ServerComponent`, registration in `Application.kt`, delete cascade in `MeRoutes`, and tests (schema + repo dedup/streak/per-bot + MeRoutes fake). The order-dependent streak is carried as a per-event snapshot and folded latest-current / running-max-best rather than re-derived from the ledger — the one non-mechanical call, written up in `decisions.md`. Sliced because the client write-ahead repo + the achievements-as-predicates refactor is the riskier, larger half; PROG-1 rewritten to describe that remaining client work.
**Reviewer notes:** `:apps:server:test` green for the three targeted suites (real Postgres via testcontainers). The streak-snapshot decision is the part to sanity-check — if the reviewer wants server-side streak recomputation instead, the event already carries enough to do it, but the fold is simpler and idempotent. No client code touched this commit.
**Deferred:** Client half stays under PROG-1 in `docs/todo.md` (next worker / cycle).
