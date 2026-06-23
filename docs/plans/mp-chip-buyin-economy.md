# MP Chip Buy-In Economy — Implementation Plan

**Status:** engine salvaged (dormant) · **Owner:** Phase 4 dev · **Created:** 2026-06-21 · **Updated:** 2026-06-23

> **⚠️ READ FIRST (2026-06-23) — branch state changed; do NOT merge `feat/mp-chip-economy`.**
> The server **escrow engine** from `feat/mp-chip-economy` has been **salvaged onto
> `feat/public-matchmaking`** as a dormant, compiling, test-green slice (commit
> `a8a0527b`). The original branch was stale (forked pre-room-refactor, never
> merged, local-only until backed up to origin on 2026-06-23) and a blind merge
> would explode on the room-module refactor + a `V60` migration collision.
> **Build Phase 4 on the salvaged engine, not the branch.** The branch stays on
> origin for reference only.
>
> **Already on `feat/public-matchmaking` (salvaged, dormant, green):**
> `TableSessionService`/`Repository` (+Postgres), `DefaultTableSessionService`,
> `WalletLedger`, `SeatStack`, the recovery sweep + their Testcontainers tests;
> `table_sessions` schema as **`V67`** (renumbered from V60); `PostgresWalletRepository`
> refactored to route balance moves through `WalletLedger`. The engine is bound
> in DI but **unconsumed** — it ships inert until wired.
>
> **Still to do for Phase 4 (deliberately left out of the salvage — it collides
> with matchmaking and is your job):**
> - `StakeTier` on `Room` (the type already exists in `:libraries:gameplay`; carry it on the room so a seated game runs at its stakes).
> - `SitDown` / `Rebuy` socket frames + `RoomSocketRoutes.handleSitDown()/handleRebuy()` + DI/`Application` wiring.
> - Cash-out on leave / disconnect / room-teardown + the boot recovery sweep wiring.
> - **Reconcile with develop's own rebuy:** develop independently shipped a rebuy UX (`85a4a455`, `9c7db005`) that overlaps the chip branch's client rebuy. Use develop's client; don't re-import the branch's.
> - The matchmaking **disclosed-bot subsidy** (deferred from Phase 3): special-case `MultiplayerCredit` for public bot tables + capped real-coin payout + per-user daily cap + anomaly telemetry. See `MatchmakingRoutes` KDoc.
> - Two MP correctness fixes that never reached develop and may still be live: `fix(mp): show showdown winner`, `fix(mp): late-subscriber deal drop` (on the chip branch; verify against develop's refactored client before porting).

**Status:** ~~planned~~ superseded by the salvage above · **Created:** 2026-06-21

## Context & the product decision

Today multiplayer table stacks are ephemeral — a player who wins hands sees **no change** in their real chip balance, and the create-screen buy-in (shipped in PR #61) only sets the table's starting stack + blinds; it doesn't cost or pay anything. This plan makes the buy-in real.

The model is the **cash-game (ring) escrow**, which is what poker players expect:

- **Sit down →** the buy-in moves from wallet to stack. Those chips are still *yours*, just locked into the table.
- **Play →** stack goes up and down.
- **Leave →** your **current stack** moves back to your wallet (cash out at whatever you're sitting on).

Net wallet change = `stack_when_you_leave − buy_in`. You keep winnings, you eat losses, and **leaving never costs you the buy-in.** It is an escrow/conversion, *not* a fee. The wrong model — buy-in spent and gone unless you win (tournament/entry-fee) — would feel like theft the moment someone leaves while up, so we explicitly do **not** do that.

Leave is framed as **cash-out, not penalty** (see UX below). Re-buy on bust. Settle on **every** exit path.

## The key fact: the engine is already salvaged onto `feat/public-matchmaking`

**(Superseded 2026-06-23 — see the banner at the top.)** The economy's server
engine no longer needs a branch merge: it has been salvaged onto
`feat/public-matchmaking` as a dormant, compiling, test-green slice. Do **not**
merge `feat/mp-chip-economy` (stale, pre-refactor, migration-colliding). The
sections below describe what the engine provides and what's left to wire — read
them as "what's on your branch now (server)" + "what you still build (wiring +
client + subsidy)", per the banner.

### What the salvaged engine provides (already on your branch, reuse as-is)
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
