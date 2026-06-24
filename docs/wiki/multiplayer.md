# Multiplayer — Status & What's Left

**This is the living source-of-truth for multiplayer.** Read this first.
**Last updated:** 2026-06-18.

> The older [multiplayer-architecture-eval.md](./multiplayer-architecture-eval.md) is a *historical* step-back review from before MP shipped. Its main recommendation (event sourcing) was **not** taken — we chose snapshot-only on 2026-05-29. Keep it for the reasoning record; use *this* doc for what's actually true.

---

## TL;DR

Multiplayer is **playable end-to-end today.** Two humans can create a room, join, see each other, and play a full hand against each other — the server runs the whole game and tells the clients what happened. Host auto-promotes if the host drops.

It is **not finished.** It's at the "playable, pre-hardening" stage. The big missing pieces:

- The **chip economy isn't wired** — MP is currently *free-play* (you can play hands, but buy-ins don't move chips in/out of your real wallet).
- **Rooms live only in server memory** — a redeploy/restart wipes the room (the in-progress *hand* can survive, the *room* can't).
- A few **safety features aren't built** (turn timer, spectator, graceful room-death messaging).

**You can and should start hands-on testing the happy path now** — just know which rough edges are expected vs. real bugs (see [What's NOT built yet](#whats-not-built-yet-dont-file-these-as-bugs)).

---

## How it works today (as-built)

Plain version of the architecture:

- **Joining a room uses normal web requests (REST).** Create / get / join / leave are simple request-response calls. ([RoomRoutes.kt](../apps/server/src/main/kotlin/com/cards/server/routes/RoomRoutes.kt))
- **Playing uses one live connection per room (a WebSocket).** Once you're in, a single always-open socket carries the live game. ([RoomSocketRoutes.kt](../apps/server/src/main/kotlin/com/cards/server/routes/RoomSocketRoutes.kt))
- **The server is the dealer — it owns the truth.** Clients never decide game outcomes; they send *intents* ("I want to raise to 200") and the server validates and resolves everything. This is the only safe design for a hidden-information game like poker. ([GameSession.kt](../apps/server/src/main/kotlin/com/cards/server/game/GameSession.kt))
- **Each player only sees what they're allowed to.** The server scrubs hole cards per viewer before sending state, so you can't peek at opponents' cards by inspecting traffic.
- **The current hand is saved to the database.** Every change writes the full game state to one row (`room_sessions`), so a server restart *can* restore an in-progress hand. ([V48 migration](../apps/server/src/main/resources/db/migration/V48__room_sessions.sql))
- **The room itself is NOT saved — it lives only in memory.** Who's seated, who's host, that the room exists at all — that's in `InMemoryRoomService` and disappears on restart. (This is the [B2](#b2--persist-the-room-itself) gap.)
- **Reconnects are handled.** Server holds your seat for a 5-min grace; the client retries the socket with backoff. Host auto-promotes to the first still-connected member if the host drops.

**Design note:** we deliberately chose **snapshot-only** (re-send the whole game state on every change) over event-sourcing. It's bandwidth-heavier but eliminates a whole class of sync bugs. We tried the event-log path (a `game_events` table) and reverted it — see migrations V31 → V47.

---

## The chip economy (buy-in / stack / re-buy) — *spec'd, not built*

This is the most important "playable but not real yet" gap. Full spec: [product-spec §4.1](./product/product-spec.md). The model:

- **Wallet** = all your chips (the number on Home/Shop).
- **Stack** = the chips *in front of you at one table.* When you sit down, the **buy-in moves wallet → stack** (reserved, not spent). It grows/shrinks as you play, and **whatever's left returns to your wallet when you leave.** Buy-in is a *gate, not a fee* — chips only ever move between players, never to "the house."
- **Re-buy on bust** = when your stack hits 0, you're prompted to move another buy-in across (or drop to a lower tier, or get soft-bust protection if you're broke).
- **Anti-smurf gate** = you can't enter a stake tier whose buy-in is **more than 25% of your wallet.** This stops a rich player from sandbagging in the beginner tier and bullying new players. ("Smurf" = a strong/wealthy player slumming in a low bracket.)

**What "not built" means in practice:** the game engine already deals, bets, and resolves hands with per-seat stacks — but none of that is connected to your real chip wallet. Sitting down doesn't reserve a buy-in; leaving doesn't cash out; busting doesn't prompt a re-buy. So today MP is mechanically **free-play.** Wiring this is [B3](#b3--gameplay--economy-mechanics).

---

## Player states: fold vs. sit-out vs. forfeit vs. spectator

These are four *different* things and they're easy to conflate:

| State | Have a seat? | Have a stack? | Dealt cards? | What it means |
|---|:---:|:---:|:---:|---|
| **Fold** | ✅ | ✅ | ✅ → mucks | Give up *this hand only*. Still seated. |
| **Sit-out** | ✅ | ✅ | ❌ skipped | "I'm out for now" toggle. Keep your seat + stack, dealer skips you, toggle back next hand. |
| **Forfeit** | ❌ loses it | → wallet | ❌ | You lose/leave your seat entirely; remaining stack cashes back to your wallet. |
| **Spectator** | ❌ never had one | ❌ | ❌ | Just watching. Sees the scrubbed view (no hole cards), can't act. |

Key takeaways:

- **Sit-out ≠ spectator.** A sit-out player is *seated* and resting (keeps stack, instant toggle back). A spectator has *no seat or stack* and must take an open seat + buy in to play.
- **"Forfeit-then-spectator" ([B4](#b4--spectator--graceful-room-death))** fixes a current ugliness: today, the **last human leaving kills the whole room.** The fix is — when your disconnect grace expires mid-hand, *forfeit your seat, auto-fold your hand, and drop your socket to read-only (spectator)* instead of ending everyone's game.
- **Spectator is the public-room join funnel.** You can't drop into a *seat* mid-hand, so the natural flow for public games is **join → spectate → get prompted to take an open seat + buy in at the next hand boundary.** That's why spectator matters a lot for *public* matchmaking but is lower priority for *friend* rooms (which you join straight into a seat).

---

## What works today (go test these)

- ✅ Create / join / leave a room, seat allocation, presence
- ✅ Reconnect with 5-min seat grace; client retries with backoff
- ✅ Host disconnects → effective host auto-promotes
- ✅ "Start Hand" → both clients enter the game, server deals & resolves authoritatively
- ✅ Bet / fold / check / call / raise via the live socket, with dedupe so a retried action can't double-fire
- ✅ A mid-hand server restart *can* restore the hand from `room_sessions`
- ✅ Strong automated coverage already exists: the `:apps:integration` module spins up a **real in-process server with real clients** (happy path, in-hand play, reconnect-mid-hand, restart hydration)
- ✅ Opponents render their real **avatar (emoji + background color)** from their profile — shipped 2026-06-18 (was rendering initials)

### Recently fixed (2026-06-18)

- **"Stuck on dealing in"** — the non-host joined after the host's `StartHand`, and the client's gameplay-frame flow was `replay = 0`, so the play screen (subscribing on navigation, *after* the deal) missed the `GameStateSnapshot`. Now the latest snapshot is retained replay-1, mirroring the server. *Root-cause lesson:* every MP test subscribed both clients *before* `startHand`, so the real subscribe-after-action ordering was never tested — see [todo.md §B6](./todo.md).
- **Opponent avatar showed as initials** — the avatar never crossed the wire. Now snapshotted from the profile at join → engine seat → `GameStateSnapshot` (same path as `badgeProductIds`).

---

## What's NOT built yet (don't file these as bugs)

- ❌ **Chip economy** — no buy-in reservation, cash-out, re-buy, or sit-out (it's free-play). [B3]
- ❌ **Per-turn timer** — a player can stall the table forever. The `RoomSettings.turnTimerSeconds` field exists but isn't enforced. [B3]
- ❌ **Spectator role** — no way to watch without a seat. [B4]
- ❌ **Graceful room death** — last human leaving still kills the room; no "you were removed, here's your stack" message. [B4]
- ❌ **Room durability** — a redeploy/restart wipes the room (membership/host/existence). [B2]
- ⚠️ **Silent action rejection** — if the server rejects a move, the user currently gets *no on-screen reason* (it's only logged). The reason is already on the wire; we just don't show it. [Easy fix — see open questions]
- ❌ **Opponent badges/titles on the seat** and **player stats / scouting** — V1.x / Phase 3. (Avatar emoji + color now *do* cross the wire — see Recently fixed; equipped badges/titles are the remaining cosmetics.)

---

## What's left to build

Ordered roughly by priority. Detailed task entries live in [todo.md §B](./todo.md).

### B6 — Test hardening *(stated top priority; mostly done)*
MP is the load-bearing feature; it shipped with test gaps. Plan: [testing-plan.md](./testing-plan.md).
- [x] Round 1 — close silent-failure gaps on new MP code
- [x] Round 2 — `:apps:integration` real-server end-to-end tests
- [ ] Round 3 — property-based engine invariants (pot/stack conservation, betting math)
- [ ] Round 5 — harder chaos (side-pots, all-in run-outs, backgrounding)
- [ ] Round 6 — Compose UI tests for `PlayPokerScreen` *(not started)*

### B2 — Persist the room itself
- [ ] Move `InMemoryRoomService` onto durable `rooms` + `room_members` tables (it becomes a cache); create/join/leave write through before responding.
- **Why it matters:** today room codes don't survive a restart. This is the fix for "rooms vanish on redeploy."
- **Note:** the *game state* already persists (`room_sessions`); B2 is specifically the *membership/registry* half.

### B3 — Gameplay & economy mechanics
- [ ] **Buy-in / stack / re-buy** + **anti-smurf gate** — wire the chip economy described above.
- [ ] **Per-turn timer** — deadline per turn; on expiry auto-check (if legal) else auto-fold; show a countdown.
- [ ] **Sit-out toggle** — keep seat + stack, dealer skips you.

### B4 — Spectator & graceful room death
- [ ] **Forfeit-then-spectator** — grace expiry mid-hand forfeits the seat + auto-folds instead of killing the room.
- [ ] **Spectator role** — WS subscriber with no seat; scrubbed view; server rejects actions from them. (The public-room join funnel.)

---

## Open design questions

Decisions we still need to make, captured from discussion. Each notes the current state.

### Room-loss detection + chip return
- **Want:** when a room dies (host kills it, backend restart, etc.), the client should say *"this room no longer exists — any chips in play were returned"* instead of silently popping the user out.
- **State:** partly spec'd ([§4.1 / §5.6](./product/product-spec.md) call for a "you were removed — here's your stack" toast and flag it as a gap). The *notification* can be added now; the *chip-return* part only becomes meaningful once buy-in (B3) exists.

### Intent-rejection UX
- **Want:** show the user *why* a move was rejected.
- **State:** the reason is already on the wire (`IntentAck.error`) and reaches the client as `IntentRejectedException.reason` ([RemotePokerSession.kt](../features/room/impl/src/commonMain/kotlin/com/cards/features/room/impl/RemotePokerSession.kt)) — it's just swallowed. **Small fix:** always show a brief toast ("That move isn't allowed right now"); in **debug builds**, append the raw reason for diagnosis. Good candidate to do before serious manual testing.

### Multiple server machines (sharding / routing)
- **Problem:** the room registry is a process-local map. If two players' sockets land on *different* Fly machines, they won't see the same room. The app runs `min_machines_running=1` but can auto-start a 2nd under load — so it's usually one instance, not guaranteed.
- **Options (cheapest first):**
  1. **Now:** pin to one machine until B2 lands — friend-game V1 scale doesn't need horizontal scale-out.
  2. **Real fix (B5):** sticky-route by room code using Fly's `fly-replay` header so all of a room's traffic lands on the instance that owns it.
  3. **Heavier:** fully durable rooms (B2) + a shared pub/sub backplane (Postgres `LISTEN/NOTIFY` or Redis) so any instance can serve any room.
- **State:** parked as B5; routing-by-room-id is the agreed direction when we get there.

---

## Testing notes / gotchas

- **A redeploy wipes active rooms.** Membership is in-memory (B2 not done) — don't redeploy mid-test-session and expect a room to survive.
- **Keep dev to one machine** during a test session so two players can't get split across instances (see sharding above).
- **Expected rough edges** (not bugs): no turn timer (players can stall), free-play chips (no real wallet movement), silent action rejections, no room-closed message.
- **Best surface to test:** create → join → play a full hand to completion → leave, plus host-disconnect / reconnect scenarios. Real playtest also unblocks deferred work (hand-history fixtures, forfeit policy).
- **Test-ordering trap (learned 2026-06-18):** MP tests tend to attach both clients' collectors *before* the action; the real app subscribes *after* (navigate-on-deal). Two bugs hid behind this. When adding MP tests, exercise the subscribe-after-action path, and give every hot flow a "replay reaches a late collector" test. See [todo.md §B6](./todo.md).

---

## Glossary

- **Wallet** — your total chips, account-wide.
- **Stack** — the chips in front of you at one table; the reserved buy-in that returns to your wallet on leave.
- **Buy-in** — chips moved wallet → stack to take a seat. A gate, not a fee.
- **Blinds** — forced bets the two players left of the dealer post each hand (rotates every hand). No antes in V1.
- **Snapshot-only** — the server re-sends the full game state on every change (vs. an event log). Our chosen model.
- **Intent** — a client's requested action ("raise to 200"); the server validates and resolves it.
- **Grace** — the 5-min window the server holds a disconnected player's seat before sweeping it.
