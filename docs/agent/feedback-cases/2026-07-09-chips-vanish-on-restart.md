# Feedback case 2026-07-09-chips-vanish-on-restart

- **Sentry issue:** none — the original report was eaten by the sampling bug (see `2026-07-09-prod-feedback-never-ingested.md`); content relayed by the owner in chat 2026-07-10
- **Reported:** owner's first prod/TestFlight session, 2026-07-09 ~20:00–21:15 UTC, release `cards@0.1.0+1`
- **Disposition:** todo: PROG-11 (client sync bug) + ECON-1 (ledger gap) + BILL-6 (sandbox purchases) + ENG-17 (prod telemetry dark, found during investigation)

## Bug description (owner, verbatim-ish)
> An achievement granted me 500 chips but when I killed and reopened the app the 500 disappeared. Grafana showed total economy still 10k. Then I signed in with Google and my balance was magically 11,500, and the economy became 12k.

## Evidence — prod Postgres via Grafana datasource `cards-prod-db` (ffrewas5byf40d)

Backend OTel was useless (prod ships no logs/traces — ENG-17), but the `wallet_events` ledger + `wallets` tables tell the whole story. All times UTC 2026-07-09:

| time | event | delta | notes |
|---|---|---|---|
| 20:01:18 | wallet `6b608e78-…` created, balance seeded 10,000 | — | **no ledger row for the starter grant** (ECON-1) |
| 20:01:51 / 20:27:13 | IAP SKU errors in Sentry | — | launch + relaunch markers (the kill happened between) |
| 20:36:23 | `achievement_grant:POT_5000` | +500 | applied only when Google sign-in triggered a sync |
| 20:36:29 | `levelup_grant:3` | +1,000 | same sync burst |
| 21:10:36 | `iap.chip_pack_small` | +5,000 | sandbox purchase, order 2000001202599296 |
| 21:10:51 | `iap.chip_pack_large` | +120,000 | sandbox purchase; balance now 136,500 |

Second wallet `087ac8d1-…` created 2026-07-10 13:14:57Z, `shop.cardback_marble` −500, balance 9,500 (starter again unledgered).

## Working theory (server side confirmed; client side to reproduce)
Nothing was lost and nothing was duplicated. The achievement (+500) and level-up (+1,000) grants sat **unsynced client-side**; kill+relaunch (20:27) neither flushed the outbox nor folded it into the displayed balance, so the client showed the bare server balance (10,000) — chips "vanished". The Google sign-in (20:36) fired a full sync that applied both grants: 10,000+500+1,000 = **11,500 exactly** as observed. The "10k → 12k economy" was stat-panel rounding of 10,000 → 11,500. Conservation holds **except** starter grants bypass `wallet_events`: prod SUM(deltas) = 126,000 vs supply 146,000 — the 20,000 gap is the two unledgered starter grants.

Remaining client question for PROG-11 (test-first): why doesn't app launch flush/fold the progression outbox — is sync simply not triggered on cold start, or is the fold missing on the wallet display path?

**Trigger-system investigation (2026-07-10, second subagent report) — ranked ways a sync trigger gets lost:**
1. **Boot ordering vs the replay-1 bus (prime suspect).** `AppEventDispatcher` is a SharedFlow(replay=1, DROP_OLDEST). Auth's session-restore emits `UserChanged` from its own init coroutine; `UserScopedSyncCoordinator` subscribes from a sibling init coroutine with undefined ordering. Replay=1 saves a late subscriber ONLY if no later event (cold-boot `OnForeground`, `ColdBoot`) has evicted the slot first. Fast boot → `UserChanged` evicted → coordinator sees only `OnForeground(isColdBoot=true)` → deliberately skipped → no sync.
2. **`AuthUnready` short-circuit, no retry.** If the trigger DOES fire before the auth gate opens, `authedCall` returns `AuthUnready` without touching the wire and nothing re-queues the sync.
3. **`ConnectivityRegained` isn't a coordinator trigger** — offline boot leaves the outbox parked until the next warm foreground; other services (GuestSessionHealer etc.) do listen to it.
4. **Mid-sync stomp:** `syncLocked` = `getAll()` → POST → `setBalance(server)`; a grant landing inside that window is overwritten (event stays queued, applies next sync — same vanish symptom).
5. **Rejected events silently revert balance:** `InsufficientChips`-class outcomes delete the row and let `setBalance` drop the balance with no user-facing signal (by design, but unsurfaced).

Batching answer for the owner: yes — one `sync()` posts ALL pending rows in a single request (no size limit) and deletes the resolved ones, so "pending after a successful sync" only happens via modes 4/5 above or a failed/blocked sync (1–3). Split: ENG-20 owns modes 1–3 (triggers), PROG-11 owns 4–5 (balance correctness).

**Code-read addendum (2026-07-10, subagent report):** the outbox IS persisted (`WalletEventEntity`, idempotency-keyed) and the optimistic balance IS persisted (`ChipsEntity.balance`, bumped by `addChips` at grant time). Two defects explain the symptom: (1) `ChipsRepositoryImpl.sync()` applies `setBalance(response.balance)` — server truth stomps the optimistic balance even when pending outbox rows haven't landed, and `observeBalance()` reads the raw entity with no pending-events fold; (2) `UserScopedSyncCoordinator` deliberately skips cold-boot foreground (auth-resolve `UserChanged` is supposed to own it), and in this session the relaunch produced no flush — either `UserChanged` never fired on session restore or its sync silently failed, with the displayed 10,000 implying something hydrated server truth without posting the pending events. MP leave settlement is NOT implicated: `leaveRoom` returns the settled balance in the response and the client applies it directly (MP-29 fix confirmed race-free).

Also confirmed live: TestFlight purchases are StoreKit sandbox (free) but recorded as real — 125,000 unpaid chips now dominate the prod supply and `billing_transactions` has no environment column (BILL-6).

## PROG-11 implementation plan (2026-07-10, worker — investigated via subagent, then implemented)

**Root decision: stop blending. The local `chips` row becomes a pure *server snapshot*, and the displayed balance is always derived as `snapshot + SUM(pending wallet_events deltas)`.** Modes 4 and 5 are both symptoms of one design flaw — a single mutable balance that optimistic writes bump and `setBalance` stomps. Deriving the display kills the whole class: no sync ordering can drop a pending grant, because the grant *is* a pending row until the server resolves it.

Mechanics (investigated against the code 2026-07-10):

1. `WalletEventDao` gains `observePendingDelta(): Flow<Long?>` + `sumPendingDelta(): Long?` (`SELECT SUM(delta)` — NULL on empty; no schema change).
2. `ChipsDao`: `applyDelta`/`insertIfMissing` die; `upsert` (REPLACE) added. `ChipsEntity.balance` now stores only the last authoritative server value (`setBalance` = snapshot overwrite). No Room migration: same column, new meaning; stale blended values self-correct on first sync.
3. `ChipsRepositoryImpl.observeBalance()/getBalance()` = `combine(snapshot, pendingSum)` → `snapshot + pending`, `pending` alone pre-first-sync, `null` when neither exists (preserves the documented spinner window).
4. `addChips/subtractChips` only enqueue the outbox row (IGNORE on the idempotency key). The old countByKey double-count guard is obsolete — the derivation sums each row exactly once.
5. `sync()` is unchanged in shape (post all → delete resolved → snapshot write) but is now stomp-proof by construction: an event enqueued mid-POST stays in the pending sum after `setBalance`.
6. Mode 5 surfacing: `ChipsRepository.observeSyncRejections(): Flow<ChipSyncRejection>` (SharedFlow from the impl, emitted when the server answers `InsufficientChips`); collected at App root next to the existing `observeEditRejections` snackbar precedent → error snackbar ("a recent chip spend didn't go through"). `RefusedServerOwned` stays silent by design (the server mints those itself; nothing was lost).

Failing-first tests: (a) grant lands while a sync request is in flight (gated MockEngine) → displayed balance after the sync includes the grant — red against the old stomp, green after; (b) `InsufficientChips` response → rejection emitted + row deleted + balance back to authoritative.

Callers audited (subagent): `setBalance` = MP settle (PlayPokerViewModel/LobbyViewModel, authoritative post-settle balance) + IAP redeem (`DefaultPurchaseChipPackUseCase`) — all are genuine snapshot writes and keep pending grants intact under the fold, which is strictly better than today's stomp. No production code reads `ChipsEntity` outside the repository.
