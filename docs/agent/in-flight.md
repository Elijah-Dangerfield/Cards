# In flight

## perf(progression): batch the XP sync write and page the client flush (ENG-45)

**Problem:** `POST /v1/me/progression/sync` cost one transaction per event server-side and the client posted its whole unsynced outbox in one request, so a player's backlog grew until every sync took 5 to 8 minutes and timed out at 30s with nothing marked synced.

**Approach:** Both halves. Server: `applyXpBatch` is now the one abstract write on `ProgressionRepository` (single-event `applyXp` survives as a default wrapping a one-element batch), doing the whole payload in one transaction via chunked `INSERT … ON CONFLICT DO NOTHING RETURNING idempotency_key` plus one total `UPDATE`. Client: `drainOutbox` pages the flush at 200 rows and loops until drained, and the XP / play-style / player-stats DAOs now require a `limit`. Chose `RETURNING` over deriving the total as `SUM(delta_xp)`: the derived version is cleaner and self-healing, but on deploy it could jump a user's total and mint level-reward chips as a side effect of a perf fix. Rationale in `docs/decisions.md`.

**Reviewer notes:** The insert is raw SQL (Exposed's batch insert can't report which rows landed, and that set is what the total moves by). It's covered by the Testcontainer suite, which is green locally against real Postgres. Measured red-first: 2,000 events cost 2,000 commits / 6,002 statements / 3.2s before, 1 commit / ≤12 statements / 71ms after. `XpEventResultDto.totalXp` still carries a per-key running total, reconstructed in the route; no client reads that field, but the wire meaning is unchanged.

**Deferred:**
- `AchievementDao.getUnsyncedEarned()` left unbounded on purpose (an achievement is earned once, so the outbox is capped by the catalog). Reasoning is a KDoc on the method and a "Not done" note in the decisions entry.
- ENG-46 (alerting on slow-but-successful requests) is what made this invisible for 8 days. Already a separate todo item, untouched here.
