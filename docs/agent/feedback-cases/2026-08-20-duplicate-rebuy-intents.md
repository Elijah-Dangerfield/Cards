# 2026-08-20 — one Rebuy tap becomes ~13 rebuy intents, all but the first rejected

**Signal:** Grafana / Loki client warn stream. No Sentry issue, no alert, no dashboard panel —
`IntentRejectedException` is caught and logged at WARN, so nothing escalates it.

```
{service_name="cards-client", deployment_environment="prod"} | detected_level=~"warn|error|fatal"
```

Window: 14d to 2026-08-20T06:30Z. Surfaced on the first nightly sweep after real multiplayer
traffic resumed in prod (the 08-11 and 08-19 runs both recorded an idle population; the server
stream went from 23 lines/48h to 1,278 lines/7d between those runs and this one).

## What the numbers say

Every warn-or-worse client line in prod over 14d, grouped by message:

| lines | level | message |
|---|---|---|
| **131** | warn | `intent rejected: seat is not busted` (`PlayPokerViewModel`) |
| 32 | warn | `App recomposed (this should be rare)` |
| 30 | warn | `accessToken: no session — request will go unauthed` |
| 20 + 5 | error + warn | `auth unready: FinishingSetup` |
| 14 + 4 | error + warn | `auth unready: NeedAccount` |
| 13 | warn | `Software caused connection abort` (`RoomSocket`) |
| 3 | warn | `Unable to resolve host cards-server-prod.fly.dev` |
| 2 | error | `Store did not recognize 3/3 chip-pack SKU(s)` |
| 1 each | warn | `not your turn`, `no ack within 10000ms`, matchmaking 400, `room_not_found` |

One message is 57% of the entire warn+ stream. Against it, the success counter:

```
sum by (event_name) (count_over_time({service_name="cards-client", deployment_environment="prod"} | event_name!="" [14d]))
→ game.rebuy = 10
```

**10 successful rebuys produced 131 rejections.** Roughly 13 duplicate intents per real rebuy,
concentrated in 3 installs / 3 sessions (43, 40, 48 each), all Android store build 1026.

## Root cause

Reconstructed from session `ccdee73f-f74c-45fd-8127-0e40576be419`
(install `4aaa6495-3472-45e7-b0a2-da949d604287`), hand 16:

| t (epoch ms) | event |
|---|---|
| …229109 | `hand.completed` hand_number=16 |
| …229201 | `game.rebuy` — **accepted** |
| …229203 → …229304 | 13 × WARN `intent rejected: seat is not busted` |

Same shape at hands 15 and 17. The first rebuy is accepted and refills the seat; every later one is
refused by the server because the seat it wants to un-bust is no longer busted. The rejections
cluster at 1ms spacing because the acks arrive back over the socket in a batch, not because the
sends were 1ms apart.

Nothing in the client stops a second Rebuy from being dispatched:

- `PlayPokerScreen.kt:708` (`MultiplayerBustDialog`) and `:363`
  (`MatchOverCountdownBanner`) both wire `onRebuy = { onAction(PlayPokerAction.Rebuy) }` with no
  `enabled` guard and no in-flight state.
- `HandResultDialogs.kt:369-378` renders the CTA as a plain always-enabled button.
- `PlayPokerViewModel.kt:1173` handles the action with a bare
  `viewModelScope.launch { Catching { session.rebuy() } }` — **no dedupe**. Compare
  `PlayPokerAction.Submit` immediately above it (`:952`), which *does* dedupe via
  `submittedTurnToken` and explicitly logs `"Ignoring duplicate Submit"`.
- `RemotePokerSession.rebuy()` (`:510`) mints a fresh nonce per call, so the server-side nonce ring
  can't collapse them either.

The dialog also stays up until the refilled snapshot arrives from the server, so from the player's
side the tap does nothing visible for a round-trip. Tap, no feedback, tap again — thirteen times.

## The money question

`handleRebuy` (`RoomSocketRoutes.kt:731`) reads the seat with an **unlocked** `gameSessions.peek`,
rejects on `seat.stack > 0`, and only then debits the buy-in via `tableSessions.rebuy`. In every
occurrence observed the duplicates arrived *after* the first refill landed, so they were refused
before touching the wallet and no chips moved (A1 ledger-drift alert is `normal`, drift 0).

That ordering is luck, not a guarantee. Two rebuys that both clear the unlocked `peek` before
either refill lands will both debit; the second `GameSession.rebuy` then rejects under its mutex and
the route compensates with a keyed `mp_rebuy_refund`. Chips net to zero, but the code's own comment
concedes "the debit and refill aren't one transaction", and each pass bumps the table session's
rebuy counter, which feeds the public-table entry bar. A 13× mash widens that window as far as it
goes. Worth closing on the client *and* tightening the server check while in there.

## Fix direction

Gate the intent, don't just swallow the rejection.

1. Give `PlayPokerAction.Rebuy` an in-flight guard in the ViewModel, in the shape `Submit` already
   uses — one outstanding rebuy per bust, later dispatches dropped with a `logger.d`, cleared when
   the ack resolves or the seat refills.
2. Drive a `rebuyInFlight` flag into `PlayPokerState` so both CTAs (`MultiplayerBustDialog` and
   `MatchOverCountdownBanner`) disable and show progress instead of looking inert. The absent
   feedback is what provokes the mashing; a guard alone fixes the telemetry but leaves the UX.
3. On the server, move the busted-seat check inside `GameSession`'s lock (or take the lock before
   the debit) so the debit can't be issued off a stale unlocked `peek`.

Test-first per house rule: the MP scenario harness (`PokerScenarioMpTest` / `FakeRoomServer`)
already models acks, so "bust, tap Rebuy 5×, assert exactly one `ClientFrame.Rebuy` on the wire"
is a red test today.

## Disposition

todo **MP-38 `[P1]`** (2026-08-20). Not P0: nothing was lost, the compensating refund exists, and
the observed rejections all landed after the accept. Not P2 either — it's a live user-facing defect
on a real-chip path with a genuine double-debit race behind it, and it is currently drowning the
client error panel this triage reads. No Sentry issue exists to resolve.
