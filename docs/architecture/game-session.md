# Architecture — Game Session

**Last reviewed:** 2026-05-17 · **Status:** Active design · **Owner:** Engineering · **Phase:** 0.2 (foundation), 4 (full realization)

The architectural model that lets the same screen play solo against bots OR multiplayer against humans, without the screen knowing which.

**Companion docs:**
- [product-spec.md §3.1 Technical Principles](../product/product-spec.md#31-gameplay) — the product-level commitments
- [v1-mvp.md Phase 0.2](../product/v1-mvp.md) — the MP-readiness refactor
- [v1-mvp.md Phase 4](../product/v1-mvp.md) — the MP foundation

---

## 1. Context

V1 has shipped a polished solo-vs-bots play surface. V1.5 adds multiplayer. The risk: if MP is built as a separate path (separate screen, separate ViewModel, separate state machine), we end up maintaining two play experiences forever — every gameplay feature ships twice, every bug fix lands twice, polish drifts between them.

The goal of this architecture: **one play screen, one ViewModel, one set of widgets — fed by either a local game engine or a remote WebSocket connection, transparently.**

The current state is bot-coupled. `LocalBotsSession` (376 LOC) owns the game loop and is named/structured for bots. `PlayBotsScreen` (745 LOC) and `PlayBotsViewModel` (200 LOC) consume it. Nothing about the design forbids the abstraction we want — but nothing in place delivers it either.

---

## 2. The principle

> **Bots and humans speak the same protocol. The view doesn't care about source.**

Two commitments fall out of this:

### 2.1 The session is source-agnostic to the view

The `PlayPokerScreen` and `PlayPokerViewModel` take a `GameSession`. They subscribe to its state, events, and seat-occupant list. They submit player intents. They don't know — and can't tell — whether the session is backed by a local game engine running in-process or a WebSocket client mirroring a server-driven game.

### 2.2 Bots are players, not a special path

In multiplayer, when a host adds bot fill, the server spawns a **bot driver process** that connects to the same WebSocket protocol as a human client. It submits intents (`fold`, `call`, `raise`) the same way. The server validates them the same way. The game engine treats it as a player. **There is no `if (player.isBot)` branch in the server.**

Two consequences:

1. **One bot decision codebase.** `libraries/bots/` runs in two contexts — locally (driven by `LocalGameSession` in solo mode) and server-side (driven by a `ServerBotDriver` in MP mode). Same KMP common code; only the driver wrapping it differs.
2. **Bots cannot cheat by design.** They submit intents through the same channel as humans. There is no privileged "bot sees opponents' hole cards" path possible because there's no such API.

---

## 3. The abstractions

### 3.1 `GameSession`

The interface the UI consumes. A live, stateful, event-emitting session of one poker game with N seats.

```kotlin
interface GameSession {
    /** Current game state — community cards, pots, seats, whose turn it is. */
    val state: StateFlow<GameState>

    /** Events as they happen — hand started, action taken, pot awarded, hand ended. */
    val events: SharedFlow<GameEvent>

    /** Who's at each seat — bot or human, with personality info for the tap-avatar surface. */
    val occupants: StateFlow<List<SeatOccupant>>

    /** Submit the local player's intent. Local for solo; sent over wire for MP. */
    fun submit(intent: PlayerIntent)

    /** Advance to next hand (only relevant when current hand has ended). */
    fun requestNextHand()

    /** Tear down resources (WebSocket, coroutine scope, etc.). */
    fun close()
}
```

`GameState`, `GameEvent`, and `PlayerIntent` live in `libraries/gameplay/` already and are `@Serializable` (so they're WebSocket-ready). The interface reuses them as-is.

### 3.2 `SeatOccupant`

A sealed type representing who's sitting at each seat. Layered *on top of* the game-engine's `Seat` primitive (which carries gameplay state — stack, hole cards, contributed this street). `SeatOccupant` carries presentation / social state.

```kotlin
sealed interface SeatOccupant {
    val seatIndex: Int
    val displayName: String
    val personality: Personality?

    data class Bot(
        override val seatIndex: Int,
        override val displayName: String,       // "Steve"
        override val personality: Personality,  // always present for bots — fixed at construction
    ) : SeatOccupant

    data class Human(
        override val seatIndex: Int,
        override val displayName: String,
        val userId: UserId,
        override val personality: Personality?, // null until heat-map data accumulates (~50 hands)
        val level: Int,
        val leagueTier: LeagueTier?,
    ) : SeatOccupant

    data class Empty(
        override val seatIndex: Int,
    ) : SeatOccupant {
        override val displayName: String get() = ""
        override val personality: Personality? get() = null
    }
}
```

The sealed type means the UI can type-narrow against `Bot` vs `Human` only for the operations that actually differ:

- Tap-avatar profile preview: `Human` opens full profile; `Bot` shows bot info card
- Friend / block: only available on `Human`
- "Bankrupt a bot" achievement: only fires when busted seat is `Bot`

For all *gameplay-rendering* code (avatar, name, current bet, stack, fold animation), there's no branch — read the shared fields.

### 3.3 `Personality`

The shared concept that lets bots and humans both expose their play style for the tap-avatar surface. Carries the data the heat-map UI (§6.4 of product spec) needs to render.

```kotlin
data class Personality(
    val label: String,                  // "Tight Aggressive" or "Steve"
    val style: PlayStyle,
    val vpip: Double? = null,           // 0.0–1.0 — null if not enough data
    val pfr: Double? = null,            // 0.0–1.0
    val aggressionFactor: Double? = null,
)

enum class PlayStyle {
    TightAggressive,
    TightPassive,
    LooseAggressive,
    LoosePassive,
    Unknown,                            // for humans before heat-map data accumulates
}
```

Bots fill in `Personality` deterministically from their `BotPersonality` (in `libraries/bots/`). Humans fill it in from accumulated hand history (V1.x — Phase 10 of product spec, when the heat map ships).

### 3.4 `PlayMode`

A sealed type passed as a navigation argument that tells the factory which kind of session to create.

```kotlin
sealed interface PlayMode {
    data class SoloVsBots(
        val difficulty: BotDifficulty,
        val personalities: List<BotPersonality>,
    ) : PlayMode

    data class FriendGame(
        val roomCode: String,
    ) : PlayMode

    data class PublicGame(
        val stakeTier: StakeTier,
    ) : PlayMode
}
```

### 3.5 `GameSessionFactory`

The DI-injected factory that the ViewModel uses to spawn the right session for the current play mode.

```kotlin
interface GameSessionFactory {
    fun create(mode: PlayMode): GameSession
}
```

The factory implementation routes based on the mode: `SoloVsBots → LocalGameSession`, `FriendGame / PublicGame → RemoteGameSession`. The ViewModel never sees the implementation type.

---

## 4. Module layout

```
libraries/game/                          ← NEW (foundation — interfaces + sealed types)
  build.gradle.kts
  src/commonMain/kotlin/com/dangerfield/cards/libraries/game/
    GameSession.kt                       interface
    GameSessionFactory.kt                interface
    SeatOccupant.kt                      sealed type
    PlayMode.kt                          sealed type
    Personality.kt                       data class + enum

libraries/game/impl/                     ← NEW (concrete implementations)
  build.gradle.kts
  src/commonMain/kotlin/.../
    LocalGameSession.kt                  refactored from LocalBotsSession
    RemoteGameSession.kt                 Phase 4 — WebSocket-backed
    DefaultGameSessionFactory.kt         routes by PlayMode

libraries/game/ui/                       ← NEW (shared table UI)
  build.gradle.kts
  src/commonMain/kotlin/.../
    PlayPokerScreen.kt                   refactored from PlayBotsScreen
    PlayPokerViewModel.kt                refactored from PlayBotsViewModel
    BoardArea.kt, PlayerArea.kt,         (moved from features/room/impl/)
    RaiseSheet.kt, TableActionBar.kt,
    HandResultDialogs.kt, ...

features/play/                           ← RENAMED from features/room/
  impl/
    PlayFeatureEntryPoint.kt             registers PlayPokerRoute(mode: PlayMode)
    (thin — most logic lives in libraries/game/ui/)

libraries/bots/                          ← UNCHANGED (decision logic, KMP common)
  Used by LocalGameSession via injection.
  Also used by :apps:server (Phase 4) via the same common code.

:apps:server                             ← NEW WORK in Phase 4
  ServerBotDriver.kt                     spawns bot processes that connect to the
                                          game's WebSocket and submit intents.
                                          Reuses libraries/bots/ decision code.
```

### 4.1 Why a separate `:ui` module

We could put `PlayPokerScreen` in `:libraries:game:impl` or in `:features:play:impl`. Two reasons it deserves its own module:

1. **Reuse downstream.** Future surfaces like a spectator view (V2+) and the hand-history replay viewer (Phase 10) can compose the same widgets without pulling in the impl module.
2. **Test cost.** UI module tests don't need the impl dependencies (network, etc.) — keeping them in their own module makes test scoping cleaner.

### 4.2 Why ONE feature module, not `solo` + `multiplayer`

Considered: `features/play/solo/` and `features/play/multiplayer/`, each with its own `GameSession` implementation.

Rejected because: the table UI is identical between modes, and splitting forces every UI change to ripple across two modules. The cleaner separation is *at the session boundary*, not at the feature boundary. One feature module, mode passed as nav arg, factory routes to the right session. See §3.4 / §3.5.

### 4.3 What stays where

- **`libraries/gameplay/`** — engine, types (Card, Seat, GameState, GameEvent, PlayerIntent). Unchanged. Knows nothing about sessions, bots, or networking.
- **`libraries/bots/`** — bot decision logic. Unchanged. Knows nothing about sessions or networking.
- **`libraries/networking/`** — generic WebSocket / HTTP. Unchanged for the foundation phase; extended in Phase 4 for the game-table WebSocket.

---

## 5. Behavioral contracts

### 5.1 Event flow (local mode)

```
UI: submit(intent)
  → LocalGameSession.submit(intent)
    → GameEngine.process(intent) → produces events
    → emit events to events flow
    → recompute state, emit to state flow
    → if next-to-act is a bot:
        → schedule bot decision (delay 0.5–3s for "think time")
        → ask libraries/bots/ for decision
        → loop back into GameEngine.process(intent)
```

### 5.2 Event flow (remote mode, Phase 4)

```
UI: submit(intent)
  → RemoteGameSession.submit(intent)
    → serialize, send over WebSocket
  ← server validates, processes, broadcasts events
  ← RemoteGameSession receives events on WebSocket
    → emit to events flow
    → reconcile state, emit to state flow
```

The UI doesn't notice the difference. Both paths produce the same events into the same flows.

### 5.3 Achievement subscriber

Achievements are a separate subscriber, not part of the session. The achievement system collects `events: SharedFlow<GameEvent>` from any active session and matches against achievement criteria. Reads `occupants` to know whether a busted player was `Bot` (for "bankrupt a bot") or `Human` (for the planned MP-mastery achievements).

This decoupling means the session doesn't need to know about achievements, and achievements don't care whether the session is local or remote.

### 5.4 Connection state

`RemoteGameSession` exposes an additional flow not on the interface: `connectionState: StateFlow<ConnectionState>` (Connected / Reconnecting / Disconnected). The UI can observe this for "Connection lost..." UI. `LocalGameSession` doesn't expose it (always-connected).

Two options here for the interface:

(a) Add `connectionState` to `GameSession` — `LocalGameSession` returns a constant `Connected` flow.
(b) Use a sub-interface: `RemoteGameSession : GameSession, Reconnectable`; UI casts when it needs reconnect state.

Going with (a) for simplicity. Always-connected is a fine constant.

---

## 6. Server-side bot driver (Phase 4)

The piece that makes "bots are players" true in MP.

```
:apps:server
  GameRoom.kt                  one per active game; owns the GameEngine
    ↓ accepts WebSocket connections
    ↓ validates intents, broadcasts events

  ServerBotDriver.kt           when host requests bot fill:
    - spawn a coroutine-scoped process
    - this process connects to the GameRoom's WebSocket as if it were a phone
    - on its turn, calls libraries/bots/ to decide an intent
    - submits intent like any other client
    - never sees other players' hole cards (they're not broadcast)
```

Properties:

- The server has ONE code path: client connects, server processes intents, server broadcasts events. Doesn't matter who the client is.
- Bots can't cheat — they only know what the server tells them, which is exactly what humans know.
- The `libraries/bots/` decision code is the SAME code that runs in `LocalGameSession`. KMP common module. No duplication.
- Reconnect-as-bot (when a human disconnects mid-hand, per spec §5.6) is just a `ServerBotDriver` with a passive personality taking over a seat. Unified mechanism.

---

## 7. Migration plan

Order matters. Each step is independently testable and shippable.

### Phase 0.2.a — Foundation (this turn)

- Create `libraries/game/` with `GameSession`, `GameSessionFactory`, `SeatOccupant`, `PlayMode`, `Personality`.
- No behavior change. Existing `LocalBotsSession` / `PlayBotsScreen` keep working.
- New module compiles in isolation; nothing depends on it yet.

### Phase 0.2.b — Tests for existing behavior

- Add unit tests for `PlayBotsViewModel` (currently zero coverage at the feature level).
- Add tests for `LocalBotsSession` covering the game-loop, bot-turn handling, hand-end callback.
- Establish a regression safety net before any code moves.

### Phase 0.2.c — `LocalGameSession` migration

- Create `libraries/game/impl/`.
- Implement `LocalGameSession` that delegates to the existing `LocalBotsSession` internally (transitional adapter pattern).
- Existing screen still uses `LocalBotsSession` directly during this step; nothing observable changes.
- New tests cover the `LocalGameSession` interface conformance.

### Phase 0.2.d — `DefaultGameSessionFactory` + ViewModel migration

- Implement `DefaultGameSessionFactory` (only routes `SoloVsBots → LocalGameSession` for now).
- Refactor `PlayBotsViewModel` to take a `GameSessionFactory`; rename to `PlayPokerViewModel`.
- Refactor `PlayBotsScreen` to consume the new ViewModel; rename to `PlayPokerScreen`.
- `LocalBotsSession` is now used only as the internal delegate inside `LocalGameSession`.
- Tests verify the same behavior end-to-end.

### Phase 0.2.e — Move shared widgets to `libraries/game/ui/`

- Move `BoardArea`, `PlayerArea`, `RaiseSheet`, `TableActionBar`, `HandResultDialogs`, etc. into `libraries/game/ui/`.
- Update imports.
- `features/room/` becomes very thin (just the route + entry point).

### Phase 0.2.f — Rename `features/room/` → `features/play/`

- Pure rename. Updates `settings.gradle.kts`, navigation registration, all imports.
- Final cleanup pass.

### Phase 4.a — `RemoteGameSession`

- Implement against the WebSocket protocol.
- `DefaultGameSessionFactory` now routes `FriendGame / PublicGame → RemoteGameSession`.
- `LocalGameSession` is unchanged — bot fill in MP rooms uses the server-side `ServerBotDriver` (NOT a local one).

### Phase 4.b — `ServerBotDriver` in `:apps:server`

- Server-side bot process spawning, connecting via WebSocket, calling `libraries/bots/` for decisions.
- Reconnect-as-bot uses the same mechanism with a passive personality.

---

## 8. Open questions

1. **`Personality.label` vs `displayName`.** A bot's personality label is "Steve" (its name). A human's personality label might be "Tight Aggressive" (its derived style). The two are semantically different and might want separate fields — or we collapse them and let the UI choose what to render.
2. **`PlayMode` storage during session.** Where does the current `PlayMode` live after session creation? The ViewModel needs it for UI hints ("3 friends, 2 bots filling"). Two options: pass it to the ViewModel directly alongside the factory, or have the session expose it. Lean toward the former — `PlayMode` is a creation argument, not a session property.
3. **Multi-game support (V2+).** When we add Blackjack / Hearts, does `GameSession` stay poker-specific (because `GameState` is poker)? Or does it generalize? Almost certainly stays poker-specific; each game gets its own session interface. Generalizing prematurely would over-engineer.
4. **Server-side bot lifecycle.** When a host adds bot fill, who owns the bot process? The `GameRoom` does. When the game ends, the bot disconnects cleanly. Edge case: host disconnects mid-game with bots filling other seats — bots stay until the room is destroyed (which has its own lifecycle policy per spec §4.13 reconnect handling).
5. **`GameState` vs `TableUiState`.** The existing `TableUiState` (in `features/room/impl/`) is a UI-shaped projection of `GameState` + occupant info + last-actions. Should the projection logic live in `LocalGameSession` (so all sessions emit `TableUiState` directly), or in the UI layer (so sessions emit raw `GameState` and UI does its own projection)? **Recommendation: UI layer.** Sessions emit primitives; UI shapes them. Keeps the session interface lean and game-agnostic for V2+.

---

## 9. What this turn delivered

- This doc.
- The foundation module `libraries/game/` with the interface, sealed types, and factory interface.
- One updated principle in `product-spec.md §3.1` reflecting the bots-as-WebSocket-clients commitment.

Phase 0.2.b–0.2.f are *not* in this turn. Next step is your call:

- **Recommended next:** Phase 0.2.b — add ViewModel tests before any code moves. Per the original V1 roadmap, tests-before-refactor is the safe path.
- **Alternative:** skip ahead to Phase 0.2.c (transitional `LocalGameSession`) if you'd rather have the abstraction in place ASAP and write tests for both old and new in parallel.
