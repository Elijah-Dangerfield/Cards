# Wallet — server-authoritative chip ledger

Chip balance is server-of-record. The client keeps a write-through cache (Room) for offline play, flushes locally-applied events to the server, and trusts the server's authoritative balance on every sync.

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
| `InsufficientChips` | Debit would dip below zero | none server-side; client logs a warning, drops the event, and silently resets to the authoritative balance (no user-facing surface yet — see backlog) |
| `RefusedServerOwned` | Positive delta with a `levelup.*` / `achievement.*` reason | none; the server mints those rewards itself, client drops the event |

A failing event does **not** abort the batch — later events still apply. The response carries the post-batch authoritative balance plus a per-event result row. The client treats all four outcomes as resolved (pending row deleted); an outcome it doesn't recognize leaves the row for a newer client to handle.

**`GET /v1/me/wallet`** — returns the current balance, lazy-creating the row with the starter grant on first contact. Useful as a cheap foreground hydrate when there are no pending events to flush.

## Rate limit

`WALLET_WRITE_LIMIT = 480 / hour / IP`. A heavy user playing ~200 hands could realistically batch ~250 syncs / hour; 480 leaves ~2× headroom while still capping sustained abuse at one batch every 7.5 seconds. Per-IP keying matches the policy on the other write endpoints.

## Trust model

Chips are play-money in V1, so the server clamps per-event deltas but doesn't *derive* them — it trusts the client's delta as long as it's within bounds and the idempotency key is fresh. When chips back something IAP-equivalent or feed ranked status, the server should derive deltas from synced facts instead. See the XP anti-cheat note in `../backlog.md`.

## Key files

- Schema: V6 migration (`wallets`, `wallet_events`), V11 (FK + cascade on `auth.users`).
- Server: `WalletRoutes.kt`, `PostgresWalletRepository.kt`.
- Client: `libraries/cards/impl/.../ChipsRepositoryImpl.kt` (sync loop lives in `sync()` / `syncLocked()`, ~lines 142-215).
