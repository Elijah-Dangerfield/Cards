# TODO

**Last reviewed:** 2026-07-11 (todo-maintainer) · **Companion to:** [backlog.md](./backlog.md), [developer-todo.md](./developer-todo.md)

The live punch list of actionable engineering work. Every item is something a worker can pick up and ship.

**Build the best thing, not the smallest change.** This is a greenfield, unshipped codebase — the goal is scalable, maintainable, production-ready systems. When you pick an item up, take a step back and ask what's genuinely best for the project and the user, then build that, even if it means restructuring or rebuilding something already here. Don't stack a minimal patch on top of what exists. Full rule: AGENTS.md → Coding Guidelines and [`docs/agent/worker-prompt.md`](agent/worker-prompt.md) → Picking work.

**Fixing a bug? Reproduce it with a failing test first.** Red (the test fails *because of the bug*), then green (the fix makes it pass). It proves you found the real cause, not a guess, and leaves a permanent regression guard. Can't reproduce it in a test? The harness is missing something — build that first. See AGENTS.md → Coding Guidelines.

**Minimum viable context.** Every item is one bold title + at most ~3 short lines (Problem / Acceptance / Hints). No status archaeology — don't narrate what already shipped, what's "locked," or which sub-part landed when. If a sub-part ships, delete that clause. A bullet a human can't skim in five seconds is too long.

**Item IDs.** Every item carries a stable ID — section prefix + number (`PROG-1`, `AUTH-3`, `MP-2`). IDs never get reused: when an item ships or moves to backlog, its number retires with it. Use the ID when referring to an item in commits, PRs, or other docs.

**ID prefixes:** `PROG` (progression / XP / stats), `AUTH` (auth + onboarding), `GAME` (gameplay + table UX), `SHOP` (consumables + rewards), `SOC` (social graph), `ROOM` (rooms UI), `MP` (multiplayer hardening), `ENG` (engineering / structural), `BILL` (billing), `ECON` (chip economy integrity).

**Priority tags** (every item carries one; bias toward P0 first):

- `[P0]` — V1 ship-blocker or load-bearing for other work.
- `[P1]` — Real value, not blocking.
- `[P2]` — Lower urgency, still worker-pickable. Many need a directional call — make a recommendation, ship a slice, let the reviewer course-correct.

Everything here is worker-pickable. Human-only work (device QA, dashboard config, content, product decisions) lives in [`developer-todo.md`](./developer-todo.md). Deferred ideas live in [`backlog.md`](./backlog.md) — when an item gets descoped or doesn't fit V1, move it there, don't delete it.

## ENG — engineering / structural

- **ENG-25 `[P2]` Harden the client OTel pipeline: offline durability + step back and perfect the system.** Problem: the pipe works but is V1-minimal — a failed export batch is dropped (events emitted offline are lost, verified in the 2026-07-11 drill), records carry no connectivity context, and the setup hasn't had a considered review since it was built under ship pressure.
  **Acceptance:** offline-emitted events survive to next launch (the library's `exporters-persistence` disk buffer via the existing `processorFactory` seam — or a considered rejection of it); an `is_offline` attribute (from the existing connectivity level) rides on events so reliability funnels can segment; plus a written pass over the whole setup — batch sizing/flush cadence, iOS background-flush hook, whether `appEventsEnabled` + sample rate should consolidate — adopting what's worth it and recording what's deliberately skipped in `docs/wiki/app-events.md`.
  **Hints:** `GrafanaAppEvents.kt` (`processorFactory` seam), `OtlpJsonLogRecordExporter.kt`, `GrafanaLogTree.kt`; delivery caveats documented in `docs/wiki/app-events.md`; the upstream crash bug that forced our own exporter is why we distrust library internals — keep everything contained in `Catching`. Also fold in: iOS `previous_exit` on `app.launched` is hardcoded `unknown` (`IosPreviousExitProvider`) — the real value needs a MetricKit `MXAppExitMetric` subscriber persisted across launches; Android already reports real values via `AndroidPreviousExitProvider`.

- **ENG-28 `[P2]` Refresh `docs/wiki/auth-and-network-errors.md` to AUTH-19's heal semantics. (proposed 2026-07-11)** Problem: the page (last touched 2026-07-03) still describes a stranded session as unconditionally kicking `GuestSessionHealer.heal` toward a mint — it misses the never-mint-over-a-cached-profile branch (`markSessionUnrecoverable` → SessionExpired recovery), sticky `SessionExpired` across re-resolves, and the anonymous-session `SessionMirrorStore` restore path, all shipped in 6a6696fb.
  **Acceptance:** the gate table and healer/key-file sections match `GuestSessionHealer.kt` and `SupabaseAuthRepositoryImpl.kt` as of AUTH-19.

- **ENG-29 `[P2]` Document the PROG-12 mint→re-pull contract in `docs/wiki/wallet.md`. (proposed 2026-07-11)** Problem: the sync-contract section explains `RefusedServerOwned` (server mints level/achievement chips itself) but misses the freshness half shipped in c3d38a33 — minting endpoints return `walletBalance` only when they actually minted, and the client answers non-null with a fresh `ChipsRepository.sync()` pull.
  **Acceptance:** the page covers the mint-response re-pull, including why the client re-pulls instead of applying the returned balance directly (a stale concurrent sync could stomp it).

