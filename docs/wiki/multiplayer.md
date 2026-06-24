# Multiplayer — how it works

Multiplayer is the load-bearing feature of the app. This doc explains how it works as built — the architecture, the chip economy model, the player-state model, and the glossary. For *active work* on MP see [`../todo.md`](../todo.md) (`MP-*` items). For *testing approach* see [`../practices/testing.md`](../practices/testing.md).

---

## Architecture

- **Joining a room uses normal web requests (REST).** Create / get / join / leave are simple request-response calls. ([RoomRoutes.kt](../../apps/server/src/main/kotlin/com/cards/server/routes/RoomRoutes.kt))
- **Playing uses one live connection per room (a WebSocket).** Once you're in, a single always-open socket carries the live game. ([RoomSocketRoutes.kt](../../apps/server/src/main/kotlin/com/cards/server/routes/RoomSocketRoutes.kt))
- **The server is the dealer — it owns the truth.** Clients never decide game outcomes; they send *intents* ("I want to raise to 200") and the server validates and resolves everything. This is the only safe design for a hidden-information game like poker. ([GameSession.kt](../../apps/server/src/main/kotlin/com/cards/server/game/GameSession.kt))
- **Each player only sees what they're allowed to.** The server scrubs hole cards per viewer before sending state, so you can't peek at opponents' cards by inspecting traffic.
- **The current hand is saved to the database.** Every change writes the full game state to one row (`room_sessions`), so a server restart can restore an in-progress hand.
- **Rooms persist too.** The membership / host registry is a write-through hydrated cache over durable `rooms` + `room_members` tables.
- **Reconnects are handled.** Server holds your seat for a 5-min grace; the client retries the socket with backoff. Host auto-promotes to the first still-connected member if the host drops.

**Design note:** we deliberately chose **snapshot-only** (re-send the whole game state on every change) over event-sourcing. It's bandwidth-heavier but eliminates a whole class of sync bugs. We tried the event-log path (a `game_events` table) and reverted it — see [`decisions.md`](../decisions.md).

---

## The chip economy (buy-in / stack / re-buy)

- **Wallet** = all your chips (the number on Home / Shop).
- **Stack** = the chips *in front of you at one table.* When you sit down, the **buy-in moves wallet → stack** (reserved, not spent). It grows/shrinks as you play, and **whatever's left returns to your wallet when you leave.** Buy-in is a *gate, not a fee* — chips only ever move between players, never to "the house."
- **Re-buy on bust** = when your stack hits 0, you're prompted to move another buy-in across (or drop to a lower tier, or get soft-bust protection if you're broke).
- **Anti-smurf gate** = you can't enter a stake tier whose buy-in is **more than 25% of your wallet.** This stops a rich player from sandbagging in the beginner tier and bullying new players. ("Smurf" = a strong / wealthy player slumming in a low bracket.)

---

## Player states: fold vs. sit-out vs. forfeit vs. spectator

These are four *different* things and they're easy to conflate:

| State | Have a seat? | Have a stack? | Dealt cards? | What it means |
|---|:---:|:---:|:---:|---|
| **Fold** | ✅ | ✅ | ✅ → mucks | Give up *this hand only*. Still seated. |
| **Sit-out** | ✅ | ✅ | ❌ skipped | Keep your seat + stack, dealer skips you, resume next hand. **Engine-only today** — the `SittingOut` seat state exists and the engine respects it, but no player-facing toggle is wired yet. |
| **Forfeit** | ❌ loses it | → wallet | ❌ | You lose / leave your seat entirely; remaining stack cashes back to your wallet. |
| **Spectator** | ❌ never had one | ❌ | ❌ | Just watching. Sees the scrubbed view (no hole cards), can't act. |

Key takeaways:

- **Sit-out ≠ spectator.** A sit-out player is *seated* and resting (keeps stack, instant toggle back). A spectator has *no seat or stack* and must take an open seat + buy in to play.
- **"Forfeit-then-spectator"** — when your disconnect grace expires mid-hand, the server forfeits your seat, auto-folds your hand, and (when `MP-1` ships) drops your socket to read-only spectator instead of ending everyone's game.
- **Spectator is the public-room join funnel.** You can't drop into a *seat* mid-hand, so the natural flow for public games is **join → spectate → get prompted to take an open seat + buy in at the next hand boundary.** That's why spectator matters a lot for *public* matchmaking but is lower priority for *friend* rooms (which you join straight into a seat).

---

## Testing gotchas

- **Best surface to test:** create → join → play a full hand to completion → leave, plus host-disconnect / reconnect scenarios.
- **A redeploy can disrupt active sockets** — clients reconnect under the grace window, but plan around it during dev sessions.
- **Test-ordering trap (the production-order principle):** MP tests tend to attach both clients' collectors *before* the action; the real app subscribes *after* (navigate-on-deal). Several real bugs hid behind this. Every hot flow needs a "replay reaches a late collector" test. Full guidance and the four-bug case study: [`../practices/testing.md`](../practices/testing.md).

---

## Glossary

- **Wallet** — your total chips, account-wide.
- **Stack** — the chips in front of you at one table; the reserved buy-in that returns to your wallet on leave.
- **Buy-in** — chips moved wallet → stack to take a seat. A gate, not a fee.
- **Blinds** — forced bets the two players left of the dealer post each hand (rotates every hand). No antes in V1.
- **Snapshot-only** — the server re-sends the full game state on every change (vs. an event log). Our chosen model.
- **Intent** — a client's requested action ("raise to 200"); the server validates and resolves it.
- **Grace** — the 5-min window the server holds a disconnected player's seat before sweeping it.
