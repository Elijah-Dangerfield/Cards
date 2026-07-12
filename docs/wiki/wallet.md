# Wallet — server-authoritative chip ledger

Chip balance is server-of-record. The client keeps two Room tables: the last authoritative **server snapshot** and a pending **outbox** of locally-earned/spent events. The balance the app displays is always derived — snapshot + SUM(pending outbox deltas) — so a grant that lands while a sync request is in flight stays a pending row and keeps counting until the server itself resolves it (the PROG-11 fix; the old single-mutable-balance design let the sync response stomp it). `addChips` / `subtractChips` only enqueue outbox rows; `setBalance` is a pure snapshot overwrite, so MP settlement and IAP redeems keep pending grants riding on top.

## Schema

- **`wallets(user_id PK, balance, created_at, updated_at)`** — one row per user. `CHECK (balance >= 0)` as a database-level floor. Lazy-created on first read with `Wallet.STARTER_GRANT` (10,000 chips).
- **`wallet_events(user_id, idempotency_key, delta, reason, applied_at)`** — append-only ledger keyed by `(user_id, idempotency_key)`. The dedup boundary that lets the client safely retry a sync without double-applying.

`DELETE /v1/me` cascades via the V11 FK with `ON DELETE CASCADE` on `auth.users`, so a user deletion wipes profile + wallet + ledger + inventory + equipment + messages atomically.

## Sync contract

**`POST /v1/me/wallet/sync`** — client posts a batch of locally-applied `WalletEventDto { idempotencyKey, delta, reason }`. Server iterates in order; each event has one of three outcomes:

| Outcome | Trigger | Effect |
|---|---|---|
| `Applied` | First time the server sees this key | `balance += delta`; ledger row written |
| `AlreadyApplied` | Duplicate idempotency key | none |
| `InsufficientChips` | Debit would dip below zero | none server-side; client logs a warning, drops the event, and emits a `ChipSyncRejection` that the App root surfaces as an error snackbar |
| `RefusedServerOwned` | Positive delta with a `levelup.*` / `achievement.*` reason | none; the server mints those rewards itself, client drops the event |

A failing event does **not** abort the batch — later events still apply. The response carries the post-batch authoritative balance plus a per-event result row. The client treats all four outcomes as resolved (pending row deleted); an outcome it doesn't recognize leaves the row for a newer client to handle.

**`GET /v1/me/wallet`** — returns the current balance, lazy-creating the row with the starter grant on first contact. Useful as a cheap foreground hydrate when there are no pending events to flush.

### Server mints and the freshness re-pull (PROG-12)

`RefusedServerOwned` is only half the contract: the server mints level-up and achievement chips itself, on the progression/achievements sync endpoints. But every sync loop fires on the same trigger edge, and the wallet sync usually completes *before* the sync that mints — so without a signal, the reward stays invisible until the next edge ("earned 1000 chips, stale until force-kill").

The signal: the minting endpoints return a `walletBalance` field, populated **only when the request actually minted** (an idempotent replay signals nothing). The client (`ProgressionRepositoryImpl` / `AchievementRepositoryImpl`) treats non-null as "the wallet changed server-side" and issues a fresh `ChipsRepository.sync()` pull.

It deliberately does **not** apply the returned balance via `setBalance`: a concurrent wallet sync whose server-side read predates the mint can arrive later client-side and stomp the newer value. A pull *issued after* the mint is ordering-safe — it reads post-mint state by construction and is serialized by the wallet's sync mutex. (Rejected alternatives — direct `setBalance`, coordinator-level sync ordering, versioned snapshots — in `docs/decisions.md`, 2026-07-11.)

There is no client-side starter grant: until the first sync hydrates the snapshot (and while the outbox is empty) the client's balance is `null` and the UI renders a spinner / hides the badge rather than flashing a placeholder.

## Rate limit

`WALLET_WRITE_LIMIT = 480 / hour / IP`. A heavy user playing ~200 hands could realistically batch ~250 syncs / hour; 480 leaves ~2× headroom while still capping sustained abuse at one batch every 7.5 seconds. Per-IP keying matches the policy on the other write endpoints.

## Trust model

Chips are play-money in V1, so the server clamps per-event deltas but doesn't *derive* them — it trusts the client's delta as long as it's within bounds and the idempotency key is fresh. When chips back something IAP-equivalent or feed ranked status, the server should derive deltas from synced facts instead. See the XP anti-cheat note in `../backlog.md`.

## Key files

- Schema: V6 migration (`wallets`, `wallet_events`), V11 (FK + cascade on `auth.users`).
- Server: `WalletRoutes.kt`, `PostgresWalletRepository.kt`.
- Client: `libraries/cards/impl/.../ChipsRepositoryImpl.kt` — balance derivation in `observeBalance()` / `foldBalance()`, outbox writes in `applyDeltaInternal()`, flush + per-event reconciliation in `sync()` / `syncLocked()`. Rejection snackbar wiring in `apps/compose/.../App.kt`.
