# MP Chip Buy-In Economy — Implementation Plan

**Status:** planned · **Owner:** unassigned · **Created:** 2026-06-21

## Context & the product decision

Today multiplayer table stacks are ephemeral — a player who wins hands sees **no change** in their real chip balance, and the create-screen buy-in (shipped in PR #61) only sets the table's starting stack + blinds; it doesn't cost or pay anything. This plan makes the buy-in real.

The model is the **cash-game (ring) escrow**, which is what poker players expect:

- **Sit down →** the buy-in moves from wallet to stack. Those chips are still *yours*, just locked into the table.
- **Play →** stack goes up and down.
- **Leave →** your **current stack** moves back to your wallet (cash out at whatever you're sitting on).

Net wallet change = `stack_when_you_leave − buy_in`. You keep winnings, you eat losses, and **leaving never costs you the buy-in.** It is an escrow/conversion, *not* a fee. The wrong model — buy-in spent and gone unless you win (tournament/entry-fee) — would feel like theft the moment someone leaves while up, so we explicitly do **not** do that.

Leave is framed as **cash-out, not penalty** (see UX below). Re-buy on bust. Settle on **every** exit path.

## The key fact: most of this already exists on `feat/mp-chip-economy`

The `feat/mp-chip-economy` branch is a **complete, tested vertical slice** of exactly this economy. It is *dormant* (gated behind UI wiring + a "deal only funded players" enforcement flip) and sits ~16 commits behind `develop` (≈43 merge conflicts, almost all parameter-threading from the just-merged `feat/backend-bots` + `feat/configurable-buyin` PRs). **This plan is merge → reconcile → finish, not rebuild.** Reusing it beats rebuilding the atomicity + crash-recovery + test infrastructure from scratch.

### What the branch already provides (reuse as-is)
- **Server:** `TableSessionService.sitDown()/rebuy()/cashOut()`; `WalletLedger.applyInCurrentTransaction()` keyed `table:{sessionId}:{buyin|rebuy:n|cashout}`; `TableSessionRepository`; `DefaultTableSessionRecoverySweep` (boot-time cash-out of abandoned sessions from durable snapshots); `V60__table_sessions.sql` with a **partial unique index `(user_id) WHERE status <> 'closed'`** (double-spend guard) and a forward-only `open → closing → closed` status.
- **Atomicity:** buy-in runs as one `database.transaction { table_sessions insert + wallet debit }`; rebuy/cash-out are safe under retry because the wallet movement is keyed.
- **Wire:** `ClientFrame.SitDown(nonce)` / `Rebuy(nonce)`; `RoomSocketRoutes.handleSitDown()/handleRebuy()`; reuses the existing `IntentAck`/nonce routing.
- **Client:** `RemotePokerSession.rebuy(): RebuyOutcome` (ack + 10s timeout); `PlayPokerViewModel` bust → rebuy → chip-pack upsell; mode-aware bust dialogs; dormant lobby sit-down wiring.
- **Tests:** `TableSessionServiceTest` (sit/rebuy/cashout under entry-bar, double-spend, crash recovery), `TableSessionRecoverySweepTest`, chaos/latency integration seams.

### What already exists on `develop` (the wallet primitives)
- `WalletRepository.apply(userId, idempotencyKey, delta, reason): ApplyOutcome` — idempotent, transactional, rejects `InsufficientChips`, `CHECK (balance >= 0)`. The server **can debit/credit directly** (the admin grant endpoint already does). Tables: `wallets`, `wallet_events` (`V6__wallets.sql`).
- Client `ChipsRepository` write-through cache + `POST /v1/me/wallet/sync` reconciliation; next sync hydrates the authoritative post-game balance via `setBalance()`. **The client never submits buy-in/payout events — they are server-orchestrated.**
- Reason taxonomy already established (`starter_grant`, `achievement_grant:*`, `boost.*`, `shop.purchase`, `bust_protection`, …) — add `table:*` reasons in the same shape.

## Work

### 1. Merge & resolve (`feat/mp-chip-economy` → `develop`)
Resolve the ≈43 conflicts. Hot files: `Room.kt`, `GameSession.kt`, `SeatOccupant.kt`, `Tables.kt`, `RoomClientFrame.kt`/`RoomDto.kt`/`RoomRoutes.kt`/`RoomSocketRoutes.kt`, integration helpers (`TestClient`, `PlayHelpers`, `GameplaySession`), and the docs. Most are additive param threading — no structural incompatibility. Land this as its own commit with **no behavior flip** (economy stays dormant), green tests.

### 2. Reconcile with what shipped since the branch point
- **Buy-in source:** the branch assumed a `StakeTier` on `Room`; `develop` shipped **`Room.buyIn: Long`** (PR #61). **Decision: drop `StakeTier`, debit `room.buyIn`** on sit-down and derive stakes via `RoomSettings.forBuyIn(room.buyIn, room.maxSeats)`. Rewire `TableSessionService.sitDown` to read `room.buyIn`.
- **Backend bots (new on develop):** bots have **no wallet**. `sitDown`/`rebuy`/`cashOut` MUST skip bot seats (`RoomMember.bot != null` / `Seat.isBot`) — bots are funded only in the engine stack, never touch `wallet_events`. Confirm `HandOutcome.perHuman` already excludes them (it does).
- **Seat/avatar fields:** keep `develop`'s `RoomMember`/`SeatOccupant` avatar fields; drop the branch's duplicates.

### 3. Settle on **every** exit path (the dangerous part — audit exhaustively)
Any path that frees a seat without `cashOut` silently **burns chips**. Map each `develop` exit path to a keyed `cashOut`:
- **Explicit leave** — `DELETE /v1/rooms/{code}/me` → `RoomService.leave` → cash out remaining stack.
- **Disconnect → reaper** — `RoomSocketRoutes` schedules `reapIfStillDisconnected`; cash out on reap (capture the seat stack). ⚠️ today the reaper just frees the seat.
- **Room teardown / last-out** — wire `GameSessionRegistry.end()` (note: it exists but isn't called in prod yet) and cash out every remaining seated **human** before teardown.
- **Boot recovery** — `DefaultTableSessionRecoverySweep` cashes out non-closed sessions from the durable snapshot after a restart.
- **Mid-hand leave** — fold the seat in the engine first, then cash out the **remaining** stack; chips already committed to the live pot are forfeit (that was the bet).
All keyed `table:{sessionId}:cashout` so a retry / double-reap / boot sweep never double-credits.

### 4. Finish the dormant parts
- **Sit-down moment** — decide where `ClientFrame.SitDown` fires. **Recommend funding at deal/seat-take** (host "Deal hand" deals only funded players) so sitting in the lobby is free; flip the dormant **StartHand "deal only funded players"** enforcement.
- **Insufficient funds** — block sit when `buyIn > balance`; surface "not enough chips" + chip-pack upsell. Interacts with `bust_protection` grant (server grants 1,000 at zero balance) — make sure they don't fight.
- **Re-buy on bust** — bust → rebuy dialog (debit another `buyIn`) or leave; client wire already present.
- **Leave-confirmation UX (new)** — generalize today's `LeaveBotsConfirmDialog` for MP cash-out: between hands → *"Leave the table and cash out N chips?"*; mid-hand → *"Leaving folds your current hand. Cash out N?"*. Frame as cash-out, show the amount.
- **Display** — show buy-in + your live stack at the table and the cash-out amount in the confirm.

## Open product decisions (make before/while building)
- **Entry bar:** the branch enforces `buyIn ≤ 25% of wallet` (must cover ≥4 buy-ins). For private friend games this is likely too strict — **recommend relaxing/removing it for private rooms, keeping it for public matchmaking** (make it config). Decide.
- **Min buy-in vs balance:** `MIN_BUY_IN = 100`; if balance < 100 the player can't sit — route to the `bust_protection` grant / chip-pack upsell.

## Verification
- Reuse `TableSessionServiceTest` + `TableSessionRecoverySweepTest`; add: sit **debits**, leave **credits the current stack**, **every exit path settles** (leave / disconnect-reap / teardown / boot sweep), **bots never write `wallet_events`**, mid-hand leave **folds then settles the remainder**, **idempotent** (double reap/sweep ≠ double credit), insufficient-funds blocks the sit.
- **Integration:** real in-process server, two clients — buy-in → play → leave → both wallets reconcile to `start − buyin + final_stack`.
- **Manual:** create a room with a buy-in, sit (balance drops), win/lose a few hands, leave (balance reflects the stack); disconnect mid-game (reconnect keeps the seat; grace-expiry cashes out).

## Suggested PR breakdown
1. Merge branch → resolve conflicts → green, **economy still dormant**.
2. Reconcile buy-in source (`Room.buyIn`) + bot exclusion.
3. Wire sit-down + flip StartHand "funded players only" enforcement.
4. Leave-confirmation + re-buy UX.
5. Exit-path settlement audit + the new tests above.
