# MP integration-test gap matrix

**Date:** 2026-06-24
**Scope:** scenarios to add to `apps/integration` (real in-process server + real `TestClient`s + real WS sockets)
**Out of scope:** UI / navigation tests — see [spike-compose-ui-tests.md](spike-compose-ui-tests.md) for that layer.

## Status: implemented (2026-06-24)

40 new integration tests landed across 13 files covering all 11 categories below
(a few P2s noted as deferred inline). The push found and fixed **three** real
server bugs, each now pinned by a regression test:

1. **Ghost host on reap/sweep** — `InMemoryRoomService.reapIfStillDisconnected`
   and `sweepDisconnected` removed a disconnected host without migrating
   `hostUserId`, stranding a private room (only the host can start). Only
   `leave()` migrated. Fixed with a shared human-preferring `nextHumanHost()`.
   (reaper category)
2. **Sticky `removedPlayerIds`** — a player who left mid-hand was filtered out of
   every future deal for the whole session, so rejoining did nothing. `queueJoiner`
   now clears the mark on re-entry. (forfeit category)
3. **Harness fidelity gap** (not prod) — the harness wired `RoomClosedListener.NoOp`,
   so the last leaver was never cashed out; the conservation test forced wiring
   the real `RoomTeardownCoordinator`. (chip-economy category)

Harness seams added: injectable `reaperGrace`, per-server wallet/escrow fakes +
`walletBalance`/`roomExists` probes, `awaitRoom`/`awaitUntil`/`startHandAwaitingAck`
helpers, `driveToCompletion(observer, actable)` N-player passive driver, and
`seatPrivate(n, maxSeats)`.

The matrix below is the original plan; the implemented files map to it by
category (MidHandJoinPlayTest, ReaperGraceExpiryPlayTest, ForfeitAndRemovePlayerTest,
ChipEconomyPlayTest, HandEndTransitionsTest, LobbyLifecycleTest, ErrorSurfacesTest,
ConcurrencyTest, WireAndSnapshotTest, DisconnectResyncTest, BotIntegrationTest,
MatchmakingGapsTest, EdgeCasesPlayTest).

## How to read this

Each row is a scenario we can write today using the existing harness (`IntegrationTest` base, `InProcessServer`, `TestClient`, `awaitState` / `awaitEvent` helpers). The "Why not covered today" column is the validity check — every row was verified against the existing test files before being listed.

Priority guide:
- **P0** — regression for a bug we hit recently OR a real-money correctness path.
- **P1** — behaviour that's plausibly broken and not currently tested.
- **P2** — defensive / unusual scenarios.

## What exists today

`apps/integration/src/androidUnitTest/kotlin/com/cards/integration/setup/` — 13 test files covering create+join+presence+start, leave bookkeeping, side pots, all-in lines, hand restart with stack carry-over, mid-submit reconnect, latency, nonce dedup, chaos resync, matchmaking with bot fallback, wire-format contract, emote broadcast.

The harness is denser than I gave the codebase credit for in earlier conversations. The gaps below are the *missing* scenarios, not "we have nothing."

## Matrix

### Two-client lifecycle
| # | Priority | Scenario | What it pins | Why not covered today |
|---|---|---|---|---|
| L1 | P0 | Host leaves, joiner promoted → joiner's `canStart` flips true | Host election triggers `canStart` recalc, not just `effectiveHostUserId` | `SetupJourneyTest` covers election, not the resulting button-enable |
| L2 | P0 | Joiner disconnects → reaper grace expires → host sees `MemberLeft` | Grace boundary actually fires; not just "we set the grace" | Tests block/unblock the reaper; never exercise expiry |
| L3 | P1 | Rejoin after grace expired → treated as fresh join, gets a new seat | Stale `disconnectedAt` doesn't resurrect the old seat | No coverage; tests only fast-reconnect inside grace |
| L4 | P1 | Two members disconnect simultaneously → reaper fires independently per member | Per-member timer, not one global sweep | Single-disconnect coverage only |
| L5 | P1 | Member flaps (disconnect → reconnect → disconnect) 3× → presence stays consistent | State machine doesn't drift under repeated transitions | Only one flap per test |
| L6 | P2 | 4 members, 2nd connects mid-Snapshot → all observers see 1/4 connected breakdown | Observer-side connection-count consistency | `fourPlayers_allConvergeInLobby` covers convergence, not partial states |

### Mid-hand join (relaxed today — Private rooms now accept)
| # | Priority | Scenario | What it pins | Why not covered today |
|---|---|---|---|---|
| J1 | P0 | New joiner arrives mid-hand → queued via `queueMidHandJoinerIfNeeded` → spectates current hand → dealt in next | The end-to-end queue path for the relaxed Private join (shipped today) | Zero coverage of the queue path on a real socket |
| J2 | P0 | Two joiners queue mid-hand → both dealt in next hand at distinct seats | Multiple pending joiners, no duplicate-seat assignment | Only single-queue path exists in matchmaking tests |
| J3 | P1 | Mid-hand joiner sees scrubbed snapshot (no hole cards) until their seat is dealt | Spectator scrubbing for queued-but-not-seated members | Non-member spectator scrubbing tested; queued-member case isn't |
| J4 | P1 | Mid-hand joiner leaves before next-hand dealt → `dequeueJoiner` fires cleanly | Pending-joiner removal path, no orphaned seat stub | `dequeueJoiner` is called on leave but never asserted on |
| J5 | P0 | Join into a `Playing` Private room (today's relaxed gate) → 200 OK, member added | The change shipped today — verify end-to-end, not just unit | `InMemoryRoomServiceTest.join_whilePlaying_seatsMember_forMidHandJoin` is unit-level only |

### Hand-end transitions
| # | Priority | Scenario | What it pins | Why not covered today |
|---|---|---|---|---|
| H1 | P1 | Hand ends by fold → status returns to Lobby → addBot succeeds | The Playing→Lobby flip after a one-action hand | Implicit via multi-hand tests; never asserted directly |
| H2 | P1 | Three streets in one hand: preflop call → flop check → turn bet → river fold | Per-street advance, not just open-and-shut hands | Tests are either preflop-fold OR full-showdown; no mid-hand-fold-on-flop |
| H3 | P1 | Final stack of hand N = starting stack of hand N+1 (no escrow involved) | Stack carry-over without rebuy/cashout muddying | Implicit in MultiHandWireTest's three-hand chain; isolatable |
| H4 | P2 | `RequestNextHand` with no pending joiners + 1 human + 0 bots → start blocked | Single-seat start gating | UI side covered; server-side rejection isn't |

### Disconnect / reconnect
| # | Priority | Scenario | What it pins | Why not covered today |
|---|---|---|---|---|
| D1 | P1 | Drop *before* socket ever opens (failed handshake retry path) → reconnect succeeds | Reconnect from never-connected | Tests drop post-connect only |
| D2 | P1 | Drop between Create→Join (socket not yet opened) → re-open → state sync on first frame | Race: join is HTTP, socket is separate | No test for this race |
| D3 | P1 | Drop *mid-frame-send* (writer-side, partial outbound) → reconnect → frame redelivered or queued | Outbound buffer survives reconnect | `InFlightSubmitReconnectTest` covers ack-side; not write-side |
| D4 | P1 | Drop mid-hand → opponent CALLS / CHECKS through (no fold) → reconnect → see correct final stacks | Resync under non-fold opponent action | `ChaosPlayTest` covers fold; not call/check sequences |

### Reaper / grace expiry
| # | Priority | Scenario | What it pins | Why not covered today |
|---|---|---|---|---|
| R1 | P0 | Member disconnected past grace → sweep fires → `MemberLeft` broadcast + seat freed | The actual grace boundary | Reaper-block tests skip the timer; real expiry untested |
| R2 | P0 | Two members past grace simultaneously → both sweeps fire, both seats freed | Independent per-member timers | Only single-member sweeps |
| R3 | P1 | Member past grace then reconnects → assigned NEW seat, old seat already freed | Race between sweep + reconnect | No coverage |
| R4 | P2 | Sole-survivor host disconnects past grace → room reaped, listener fires | Empty-room sweep cascade | Listener-firing tested in isolation, not via grace |

### Bot integration
| # | Priority | Scenario | What it pins | Why not covered today |
|---|---|---|---|---|
| B1 | P1 | Host adds bot mid-game → server returns 409 `NotJoinable` (intentional) → client surfaces cleanly | The known 409 from today's bug repro — client handles it gracefully | We left this as backlog; the path isn't pinned |
| B2 | P1 | Host adds bot in Lobby → bot appears in member list for all clients | AddBot path end-to-end | Tests use pre-dealt bots; no mid-test addBot |
| B3 | P1 | Bot's turn → server auto-acts → other clients see hand advance with no human SubmitIntent | Bot driver autonomy | All-fold/all-call tricks could fake it; no real bot-driver coverage |
| B4 | P2 | Bot busts mid-hand → auto-removed → seat freed before next deal | Bot removal edge case | No bot removal coverage |
| B5 | P1 | `fillBotsUpTo` consent flow on a lone-searcher table → bots arrive, hand auto-starts | Public bot fallback (matchmaking) | `MatchmakingPlayTest` references this but doesn't exercise the consent endpoint directly |

### Matchmaking
| # | Priority | Scenario | What it pins | Why not covered today |
|---|---|---|---|---|
| M1 | P1 | Two searchers at non-overlapping tiers (1k vs 10k) → not matched, each gets fresh table | Range-overlap gating in candidate logic | Only same-tier coverage |
| M2 | P1 | Three candidates with 1/2/1 humans → searcher matched into the densest (2-human) | Candidate ordering by human density | One-candidate scenario only |
| M3 | P1 | Two searchers race for the same candidate (one wins the join, other gets next) | `MatchmakingResult.Joined` vs. `Created` race | Race path untested |
| M4 | P1 | Chooser shows multiple candidates → user picks the second one → joins by code | UI re-poll + non-first selection | Tests always join the first candidate |
| M5 | P2 | Searcher in range that matches a Public table mid-Playing → joins as queued mid-hand joiner | Public + relaxed-mid-hand-join integration | Two-feature combo never exercised |

### Chip economy / escrow
| # | Priority | Scenario | What it pins | Why not covered today |
|---|---|---|---|---|
| C1 | P0 | Player joins → wallet debited by buy-in (escrow); leaves → wallet credited back by stack | Full escrow round-trip | `cashOut` is called but not verified against wallet state |
| C2 | P0 | Player busted (stack=0) at end of hand → next-hand request excludes them OR allows rebuy | Bust handling — both branches | No coverage of busted-then-next-hand |
| C3 | P1 | Player cashes out their leftover stack on leave, no double-credit if leave is sent twice | Idempotency on cashOut | Tests leave once; never assert double-leave |
| C4 | P1 | Buy-in escrow rejected if wallet < buy-in → join fails with `OverBalance` outcome | Anti-smurf / sufficient-funds gate | Outcome enum exists; integration path untested |
| C5 | P2 | Starting stack on hand-1 matches the room's `buyIn` exactly | Stack initialization aligns with room settings | Tests use fixtures; not asserted post-deal |

### Wire correctness
| # | Priority | Scenario | What it pins | Why not covered today |
|---|---|---|---|---|
| W1 | P1 | IntentAck carries the exact `clientNonce` from the submit → sender correlates | Nonce round-trip identity | Tests check accept/reject; not nonce echo |
| W2 | P1 | Three-seat snapshot: each viewer sees own hole cards, others scrubbed → cross-check | Per-recipient scrubbing across multiple viewers | `InHandPlayTest` covers heads-up; not 3-way scrubbing |
| W3 | P1 | `MemberLeft` is idempotent across multiple subscribers (no double cash-out / double forfeit) | Per-subscriber duplicate-call guard | Tests observe leave; never assert idempotency under N subscribers |
| W4 | P2 | Snapshot always precedes deltas in the same socket frame batch | Frame ordering guarantee | Implicit in `awaitState`; never explicitly ordered |

### Concurrency
| # | Priority | Scenario | What it pins | Why not covered today |
|---|---|---|---|---|
| X1 | P0 | Two clients submit DIFFERENT intents same seat (one fold, one call) → exactly one accepted | Mutex correctness across distinct intent types | `ConcurrentIntentTest` races same intent (fold+fold) only |
| X2 | P1 | Host calls `StartHand` while joiner's join-HTTP is still in flight → start queues the joiner | Start vs. join race | Tests join before start; race never exercised |
| X3 | P1 | Joiner connects socket and SubmitsIntent on the same tick → handled in order | Client-side queue under concurrent first-action | No mid-connect intent coverage |

### Error surfaces
| # | Priority | Scenario | What it pins | Why not covered today |
|---|---|---|---|---|
| E1 | P1 | Server 5xx during gameplay → client surfaces error, can leave and rejoin cleanly | Server-error resilience | Chaos/reconnect tests don't inject 5xx |
| E2 | P1 | Invalid `PlayerIntent` (raise amount above stack) → rejected with structured reason | Intent-validation boundary | Out-of-turn rejection tested; amount bounds aren't |
| E3 | P1 | Spectator-mode submit attempt on a Private room → rejected (non-member can't act) | Non-member intent gate | Spectator path tested for Public; Private rejection untested |
| E4 | P1 | Host issues StartHand when alone → server rejects with structured response | Start gate at server (not just UI) | UI side covered; server side isn't |

### Forfeit / removePlayer
| # | Priority | Scenario | What it pins | Why not covered today |
|---|---|---|---|---|
| F1 | P0 | Player forfeits mid-hand on an active seat → folds + hand continues correctly | `forfeitSeat` end-to-end on a live hand | Leave triggers it, but never asserted in isolation |
| F2 | P1 | Forfeited player attempts rejoin after leaving → gets a new seat (not blocked) | Rejoin allowed after forfeit (verify the design) | No rejoin-after-forfeit test |
| F3 | P1 | Player removed mid-hand via `removePlayer` → seat stub stays for current hand, gone next | `removePlayer` vs. `forfeitSeat` distinction | `removePlayer` is called on leave; never asserted |

### Snapshot / replay
| # | Priority | Scenario | What it pins | Why not covered today |
|---|---|---|---|---|
| S1 | P1 | Late VM mount after `Complete` hand → sees final stacks + button position | Retained state replay post-hand | All mount-after tests mount mid-hand or pre-hand |
| S2 | P1 | Gameplay event buffer (replay=16) replays the opening burst (HandStarted → Blinds → Deal) | SharedFlow replay window | Implicit via burn handoff; never asserted explicitly |

### Edge cases
| # | Priority | Scenario | What it pins | Why not covered today |
|---|---|---|---|---|
| Z1 | P1 | 2-seat all-in preflop → runs out instantly to showdown (no street pauses) | All-in fast-path with no remaining streets | `WireAllInTest` covers all-in but with later-street raises possible |
| Z2 | P2 | 4-seat: three players bust on the same hand → last human plays bot-fill next | Cascading busts | Single-bust only |
| Z3 | P2 | Public auto-dealt table: dealer button assigned correctly on first hand (not always seat 0) | Initial button placement | Rotation tested; initial assignment isn't |

---

## Totals

- **66 scenarios** proposed across 11 categories.
- **15 P0** (recent bug regressions + real-money correctness).
- **41 P1** (untested behaviour likely to break).
- **10 P2** (defensive / unusual).

## Recommended sequencing

Don't pick up 66 at once. Tier-1 burst:

1. **All P0s** (15 scenarios, ~2 days). Highest concentration of "did the bug we just shipped get pinned" — L1, L2, R1, R2, X1, C1, C2, F1, B1 specifically, plus the J5 + J1 pair to lock in today's mid-hand-join relax.
2. **P1s in J/H/D categories** (mid-hand join + hand-end + disconnect): ~12 scenarios. These cover the feature shipped today and the highest-traffic flows.
3. **P1s in C/W/X** (chip + wire + concurrency): ~10 scenarios. Real-money correctness backbone.
4. **Remaining P1s + P2s** as opportunistic fills.

## Notes / caveats

- Every scenario in this matrix is expressible in the *existing* harness — no new test infrastructure needed. The harness pattern is: `integration { val a = client(); val b = client(); ...; a.lobbyVm(...); ... awaitState { ... } }`.
- Categories like *bot driver autonomy* (B3) may need a small harness extension (a "play N intents then assert" helper) to be ergonomic, but no fundamental new plumbing.
- The matrix does **not** cover navigation/composable scenarios. That's a separate decision — see the spike report.
