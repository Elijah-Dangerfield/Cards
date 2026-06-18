# Multiplayer Architecture Evaluation

> ⚠️ **Historical — superseded 2026-05-29.** This is a step-back review written
> *before* multiplayer shipped. Its central recommendation (event sourcing,
> option B) was **not** taken: we chose **snapshot-only** state on 2026-05-29,
> shipped it (B0/B1), and even added-then-dropped a `game_events` table along the
> way (migrations V31 → V47). Keep this doc as the record of *why* the options
> were weighed the way they were — but for what's actually true today and what's
> left to build, read **[multiplayer-status.md](./multiplayer-status.md)** instead.

**Status:** Evaluation only — no implementation work committed. **Last reviewed:** 2026-05-27.

## Context

A step-back review of the multiplayer system — not because anything is on fire, but to sanity-check the shape before Phase 4.2 (server-authoritative MP gameplay) layers more onto it. The question is essentially: *if we were starting from scratch with no constraints, what's the perfect system?* And then: *how does what we have today compare?*

This document is meant to be read, argued with, and then either acted on incrementally or shelved. No code changes are proposed.

---

## What's there today (one-paragraph recap)

REST handles membership ([RoomRoutes.kt](../apps/server/src/main/kotlin/com/cards/server/routes/RoomRoutes.kt) — create / get / join / leave). A single per-room WebSocket ([RoomSocketRoutes.kt](../apps/server/src/main/kotlin/com/cards/server/routes/RoomSocketRoutes.kt)) carries live state — outbound `Snapshot`, member deltas, personalized `GameStateSnapshot`, `GameEventOccurred`, `IntentAck`; inbound `StartHand`, `SubmitIntent`, `RequestNextHand`, all nonce-deduped. State lives **in-memory only** ([InMemoryRoomService.kt](../apps/server/src/main/kotlin/com/cards/server/domain/InMemoryRoomService.kt), [GameSession.kt](../apps/server/src/main/kotlin/com/cards/server/domain/GameSession.kt)) — server restart evaporates everything. Per-room and per-session mutexes, `StateFlow`/`SharedFlow` for observation. Reconnect: 5-min seat grace server-side; client backoff 250ms→16s ([ReconnectingRoomSocket.kt](../libraries/rooms/impl/src/commonMain/kotlin/com/cards/libraries/rooms/impl/ReconnectingRoomSocket.kt)). Client uses hybrid hydration — HTTP join returns a full `RoomDto`, WS `Snapshot` reconciles. Same Supabase JWT on both transports.

## What this design gets right (don't break these)

- **Transport split matches the semantics.** Membership is request/response and idempotent — REST is correct. Game state is push-driven and ordered — WS is correct. You're not forcing one transport to do both jobs.
- **Server-authoritative gameplay.** No client prediction. For an information game like poker (hidden hole cards, cheating risk), this is the only defensible default.
- **Personalized snapshots.** Scrubbing hole cards per viewer at the server is exactly right — the alternative (encrypted-per-seat blobs or trust the client) is worse on every axis.
- **Nonce-based action dedup.** Decouples retries from semantics; the client never has to reason about "did my bet land?"
- **Snapshot-first delivery with deltas as a bonus.** `Snapshot` after every mutation is bandwidth-heavy but eliminates a whole class of reconciliation bugs. Deltas exist on the wire but the client treats them as cosmetic. This is a very forgiving design.
- **Reconnect grace + exponential backoff with terminal conditions.** The client distinguishing 4xx (don't retry) from 5xx/transport (back off) is the right call.
- **Single-binary deployment.** Ktor + Postgres + Fly is operationally cheap. Don't underrate this.

## Where the cracks are (in priority order)

1. **In-memory-only state.** Server restart drops every active room mid-hand. This is the single biggest fragility, and it caps every other ambition (scale-out, replay, history, spectator-from-cold-start).
2. **No event log.** Reconnect re-sends a full snapshot; there's no "events since cursor N" channel. Means: no spectator-mid-hand replay, no hand history, no straightforward way to recover after a long disconnect.
3. **Single-process implicit assumption.** Room registry is a process-local map. Scaling beyond one Fly machine requires either sticky routing or a shared registry — neither exists today.
4. **Two transports duplicating "room state."** REST `GET /v1/rooms/{code}` returns the same shape the WS `Snapshot` does. Mostly harmless, but it's a place where they could drift, and the client doesn't really need both (hybrid hydration is a micro-optimization for one frame of latency).
5. **Room codes only — no discovery.** Fine for V1 friend games; a real ceiling for anything social later.
6. **Spectator is not a first-class concept.** Today, "in a room" = "has a seat." A spectator type would require touching auth checks, snapshot scrubbing, and the WS subscriber model.

None of these are emergencies. #1 and #2 are the ones that constrain the most future moves.

---

## Alternative shapes (and their trade-offs)

### A. Status quo, polished
Keep current design; persist rooms + game state to Postgres on every mutation so restarts don't evaporate state.

- **Pros:** Smallest change. Fixes the worst fragility (restart eats rooms). Stays single-process simple.
- **Cons:** Doesn't enable replay/spectator/scale-out. Every mutation now writes to Postgres (~5–10ms — fine for poker pace, but adds up).
- **Verdict:** A floor, not a ceiling. Worth doing as a stepping stone if you don't go further.

### B. Event-sourced game state + WS as event delivery
Append-only event log per game session, persisted to Postgres. Server still streams `GameEventOccurred` over WS but each event carries a sequence number. Reconnect protocol becomes "send me events since cursor N" or "snapshot + cursor." Periodic snapshot compaction bounds replay cost.

- **Pros:** Solves persistence, reconnect, replay, spectator, and hand history *with one model*. Eventing aligns with the existing `GameEvent` type ([Events.kt](../libraries/gameplay/src/commonMain/kotlin/com/cards/libraries/gameplay/Events.kt)) — you'd be making it durable rather than inventing a new abstraction. Audit trail is free.
- **Cons:** Schema evolution becomes a forever-concern (event versions must be readable indefinitely or migrated). Snapshot/compaction adds ops complexity. Writing two things (event log + materialized state) needs care to stay consistent.
- **Verdict:** This is the highest-leverage architectural move. Most of the other "future stuff" (replay, spectator, social hand-share) collapses to "subscribe to the event stream from cursor X."

### C. Move actions to REST, keep WS as a notification channel
`POST /v1/rooms/{code}/intents` for actions. WS becomes pure server-push of state/events.

- **Pros:** Action/response semantics are crisper (HTTP status, body, retry middleware all free). Easier to debug via standard tools. Easier to reason about idempotency.
- **Cons:** Extra latency per action (TLS round-trip vs. already-open socket). Two paths for the same conceptual operation. Action ordering relative to incoming state events becomes more subtle. The win over a nonce'd WS frame is mostly aesthetic.
- **Verdict:** Don't. The current frame model with nonces does the same job with less ceremony, and the socket is already open.

### D. Managed realtime layer for everything (Supabase Realtime / Liveblocks / Ably / PartyKit)
Replace the custom WS with a managed pub/sub or document-sync service.

- **Supabase Realtime:** You're already on Supabase. Subscribe to Postgres row changes; presence channels are built-in. Tempting.
  - *But:* Game enforcement must be server-side (validate "is this player's turn? does this bet meet min-raise?"), and Supabase Realtime is happiest when clients write directly to tables with RLS. Putting a Ktor server in front of the writes — which you must — defeats most of the simplification. You'd be using it as a pub/sub bus, not its sweet spot.
  - *Where it shines:* low-stakes broadcast channels — lobby activity, friend presence, "X started a game" toasts. Worth adopting for *those*, not for the game itself.
- **Liveblocks / Ably / PartyKit:** Excellent presence + document/state primitives, but the game engine (deal, evaluate, payout, antiCheat) still lives on your server. They'd swap your WS for theirs, not eliminate work. Vendor lock-in and per-MAU pricing get awkward at scale.
- **Convex:** A full backend rethink — game logic in their functions, realtime included. Too disruptive at this stage and pulls you off Ktor + Postgres + Fly entirely.
- **Verdict:** Don't put the game on a managed layer. Do consider Supabase Realtime for ambient social/lobby channels where eventual consistency is fine and you don't need server validation.

### E. Sticky-routed multi-process, shared room registry
Multiple Ktor processes; each room pinned to one machine (Fly app fabric, consistent-hash router, or Redis-backed registry).

- **Pros:** Horizontal scale-out without a shared mutable state.
- **Cons:** Real operational complexity. Premature for V1 / friend-game scale.
- **Verdict:** Park it. Easy to retrofit on top of (B). Don't pay this cost until you have load.

---

## Recommended target

**Keep the transport split. Move game state to an event-sourced model. Persist rooms. Leave managed realtime alone for now (except possibly for ambient social channels later).**

Concretely, the target system looks like:

1. **REST stays for membership.** `POST /v1/rooms`, `GET /v1/rooms/{code}`, `POST .../join`, `DELETE .../me` — unchanged. (Eventually expand with `GET /v1/rooms/{code}/history` and `GET /v1/rooms/{code}/replay?from=N` once event log exists.)
2. **One WS per room, unchanged shape on the wire.** Outbound still carries `GameEventOccurred` and snapshots; inbound still carries nonce'd action frames.
3. **Game session = per-room actor that owns a state machine + writes an append-only event log to Postgres.** Every accepted action produces one or more `GameEvent`s; they're written transactionally then broadcast. The in-memory `StateFlow` becomes a derived view of the log, not the source of truth.
4. **Sequence numbers on every event.** The WS protocol gains a `seq` field on `GameEventOccurred` and a client→server `RequestEventsSince(cursor)` frame. Reconnect short-circuits the "send full snapshot" path when the gap is small.
5. **Periodic snapshot compaction.** Every hand end (natural checkpoint) writes a materialized state row. Cold start = "load latest snapshot + replay events after its cursor."
6. **Room membership persisted to Postgres.** Survives restart. Cold reconnect within grace window works.
7. **Spectator = WS subscriber without a seat.** Auth check loosens for spectator-eligible rooms (private friend games stay closed; future public games can open). Snapshot scrubbing already handles "show hole cards only to seat owners."
8. **Single process for now.** Don't pay scale-out tax until volume demands it. (B) doesn't preclude (E) later — it makes (E) easier.

**Why this and not something more ambitious:** the current shape is correct in its bones; what's missing is *durability and replayability*. Event sourcing addresses both with one mechanism, aligns with the existing `GameEvent` abstraction, and unlocks four future features (replay, spectator, history, scale-out path) without committing to any of them today.

**Why not managed realtime for the game:** the hard work is game enforcement and personalized state delivery, both of which stay on your server regardless of transport. Managed services would swap a working WS for a different working WS and add a vendor.

---

## Trade-offs worth being explicit about

- **Event-log write latency** sits on the critical path of every action. Postgres on Fly is fast (~2–8ms typical), well inside poker's interaction budget, but it's not free — and a Postgres degradation now blocks gameplay rather than just persistence.
- **Schema evolution on events is forever.** Once a `GameEvent` shape ships, you either keep reading it or you write a migration. Plan for `version` fields from day one.
- **Two sources of truth (snapshot table + event log) need invariants.** A periodic consistency check (snapshot at cursor N must equal replay-from-0-to-N) is worth building early.
- **Sticky routing isn't free when you adopt it.** Postponing (E) is the right call but it'll be a real chunk of work when the time comes.
- **Spectator support changes auth surface area.** Today "member of room ⇒ trusted recipient of snapshot." Spectator splits that into "may receive scrubbed snapshot" vs. "may receive personalized snapshot." Worth designing the auth check around viewer-role rather than membership.

## How to validate the recommendation (if you wanted to pilot it)

Read [GameSession.kt](../apps/server/src/main/kotlin/com/cards/server/domain/GameSession.kt) and [GameSessionRegistry.kt](../apps/server/src/main/kotlin/com/cards/server/domain/GameSessionRegistry.kt) closely — those are the surfaces an event-sourced rewrite would touch. The fact that `GameSession` already exposes a `SharedFlow<GameEvent>` separate from `StateFlow<GameState>` suggests this evolution was already half-anticipated. Confirm that suspicion before committing.

A minimal proof-of-concept would: (a) add a `game_events` table with `(session_id, seq, event_jsonb)`, (b) write to it inside the existing per-session mutex before emitting on the SharedFlow, (c) add a single `GET /v1/rooms/{code}/history` REST endpoint that reads from it. If that lands cleanly, the rest of the migration is incremental.
