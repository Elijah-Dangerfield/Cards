# In flight

## perf(progression): batch the XP sync write and page the client flush (ENG-45)

**Problem:** `POST /v1/me/progression/sync` cost one transaction per event server-side and the client posted its whole unsynced outbox in one request, so a player's backlog grew until every sync took 5 to 8 minutes and timed out at 30s with nothing marked synced.

**Approach:** Both halves. Server: `applyXpBatch` is now the one abstract write on `ProgressionRepository` (single-event `applyXp` survives as a default wrapping a one-element batch), doing the whole payload in one transaction via chunked `INSERT … ON CONFLICT DO NOTHING RETURNING idempotency_key` plus one total `UPDATE`. Client: `drainOutbox` pages the flush at 200 rows and loops until drained, and the XP / play-style / player-stats DAOs now require a `limit`. Chose `RETURNING` over deriving the total as `SUM(delta_xp)`: the derived version is cleaner and self-healing, but on deploy it could jump a user's total and mint level-reward chips as a side effect of a perf fix. Rationale in `docs/decisions.md`.

**Reviewer notes:** The insert is raw SQL (Exposed's batch insert can't report which rows landed, and that set is what the total moves by). It's covered by the Testcontainer suite, which is green locally against real Postgres. Measured red-first: 2,000 events cost 2,000 commits / 6,002 statements / 3.2s before, 1 commit / ≤12 statements / 71ms after. `XpEventResultDto.totalXp` still carries a per-key running total, reconstructed in the route; no client reads that field, but the wire meaning is unchanged.

**Deferred:**
- `AchievementDao.getUnsyncedEarned()` left unbounded on purpose (an achievement is earned once, so the outbox is capped by the catalog). Reasoning is a KDoc on the method and a "Not done" note in the decisions entry.
- ENG-46 (alerting on slow-but-successful requests) is what made this invisible for 8 days. Already a separate todo item, untouched here.

## Review follow-up (`bb9afcae`)

A staff review of `c86cb4e4` returned "safe as-is" plus six non-blocking findings. Applied all of them, and one turned out to be a real defect the review had cleared.

- **The total update was absolute, and lost credit.** `read + gained` written absolutely means two overlapping flushes both succeed and the later write erases the earlier one's credit. At 24 concurrent flushes the caller was told 24 landed while the total reflected 10. Pre-existing — the per-event write had the same shape — so not an ENG-45 regression. `addToTotal` now issues a relative `total_xp = total_xp + ? … RETURNING`, which Postgres evaluates against the committed row.
- **The review's stated safety argument did not hold up.** It reported, from a two-transaction probe, that REPEATABLE READ plus Exposed's retry made the absolute write safe. At 6 and 8 concurrent flushes both write shapes fail, and at 24 the absolute one is silently wrong. Filed the availability half as ENG-48; the correctness half is fixed here.
- **No concurrency regression test.** Tried and dropped one: an N-way concurrent test is scheduling-dependent and passed against the buggy write on some runs. A real guard needs explicit transaction interleaving. Left the deterministic same-key idempotency test and did not leave behind a test claiming more than it proves.
- Local XP total no longer regresses mid-drain; drain progress is judged on the outbox shrinking; a drain that gives up logs at warn; `SqlActivity`'s JVM-wide counting documented.
- Play style and player stats page at 25 rows until ENG-47 batches their routes.

**Not done:** dropping `XpEventResultDto.totalXp`. The review called it dead weight, but the server deploys independently of the app release, so removing a field build-1026 clients may treat as non-nullable is a wire-compat risk for no gain.
