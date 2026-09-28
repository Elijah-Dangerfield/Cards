# Bots stop acting forever when betting re-opens on the same street

**Triaged:** 2026-09-25 · **Disposition:** todo **MP-39 [P0]** · **Reported by:** the owner, from his own prod game

Both players watched a bot sit on its turn for two minutes and never act. The table never recovers — it is not slow, it is stopped.

## Root cause

`ServerBotDriver` builds a deterministic nonce for every bot action:

```kotlin
val nonce = "bot:${session.id}:${state.handNumber}:$acting:${state.street}"
```

Session, hand number, seat index, street. **Nothing that distinguishes one action from the next.**

In poker a player acts more than once on the same street whenever betting re-opens — which is exactly what a raise does. The bot's second action on that street therefore produces a nonce **byte-identical** to its first, and `GameSession.applyIntent` (`GameSession.kt:316`) treats it as a replayed retry:

```kotlin
if (clientNonce in processedNonces) return@withLock IntentResult.Accepted
```

It returns `Accepted`, mutates nothing, and returns in microseconds.

**Then the table deadlocks, and the deadlock is self-sustaining:**

1. The driver receives `Accepted` and believes it acted. It does not retry.
2. `drive()` is only ever re-entered when `session.state` emits. `state` is a `StateFlow` — conflated, emits only on change.
3. The swallowed intent changed nothing, so there is no emission.
4. No emission → `collectLatest` never re-invokes `drive()` → the bot never acts again.

The one thing that could wake the bot is a state change, and the bug's only symptom is that state never changes. It cannot self-heal.

## Why nobody saw it

Three independent silencers stacked:

- **The dedupe returns `Accepted`, not `Rejected`.** The driver's only diagnostic is `if (result is IntentResult.Rejected) log.debug(...)` — which is both the wrong branch *and* at `debug`, invisible in prod.
- **No alert covers a stalled table.** A2/A3/A6/A7 watch whether the *server* is alive. It is. It is serving happily with a frozen table inside it.
- **No error is logged anywhere.** Prod had 3390 info / 7 warn / **zero error or fatal** over the three days containing this incident.

## Evidence

Prod, room `SWGEUH`, game session `884507e9-d953-4673-b759-7f0b51bd48af`, 2026-09-24 09:05Z. Prod was running `d55a65d8` (deployed 2026-09-21), so the `bot_action` span instrumentation was live.

| Time (UTC) | Event |
|---|---|
| 09:05:06 | human socket connects |
| 09:05:08–10 | four bots added (`POST /v1/rooms/SWGEUH/bots` ×4) |
| 09:05:14, :16, :23, :25 | four healthy `bot_action` spans, 4 spans each |
| **09:05:26** | **`bot_action`, 1 span, no children, 55µs** |
| 09:05:26 → 09:06:41 | **75 seconds of nothing** |
| 09:06:41 | human leaves; `Hand 1 finished (seat … forfeited)` |

The two consecutive spans tell the whole story:

```
1790240725  bot_action  intent.type=Call  hand.number=1  session=884507e9…
            └─ validate_intent → engine.apply_intent → state_mutate      (4 spans, 0.167ms)

1790240726  bot_action  intent.type=Call                 session=884507e9…
            (no children, 0.056ms)
```

Same session, same hand, both `Call`, 1.1 seconds apart. The second has **no `hand.number` attribute at all** — that attribute is stamped inside `applyIntent` *after* the dedupe check, so its absence is direct proof the early return fired.

**Not a one-off.** The same 1-span signature ends the bot activity in at least two other prod sessions in the last week: `1790211362` (room `UV37MX`) and `1790189114`. Three known occurrences in seven days, on a feature the owner reached for immediately.

## Proven vs inferred

**Proven:** the nonce format, the dedupe returning `Accepted`, the two spans above, the 75-second silence, the absence of any error log, and that a replay is the only path producing a childless sub-100µs `bot_action`.

**Inferred:** that the specific re-opening action was the owner's raise. His account ("we both got to make our first call and raise") matches, and a raise is the ordinary way betting re-opens, but the spans do not record the human intent that preceded the freeze.

**Not ruled out:** a second, independent freeze path. Fixing the nonce removes this one; the watchdog below is what makes any other one visible instead of silent.

## The fix

**Make the nonce unique per action.** Add a monotonic per-seat action counter to the key, e.g. `bot:<session>:<hand>:<seat>:<street>:<actionSeq>`, where `actionSeq` comes from engine state that already increments on every applied action. Do not use a clock or RNG — the determinism is load-bearing for the legitimate retry case the nonce ring exists for.

**Then fix the diagnostic that hid it.** `Accepted` from a dedupe is indistinguishable from `Accepted` from real work. Either return a distinct `IntentResult.Duplicate`, or have the driver verify it actually moved the table. A bot that believes it acted while the table disagrees should be loud, not silent.

## Observability that would have caught this

None of this existed, which is why two minutes of a frozen table produced zero signal.

1. **A stalled-turn watchdog in the driver.** The cheapest and most valuable: after submitting, confirm the acting seat actually changed within a few seconds. If it did not, log at **error** with session, hand, seat, street and nonce, and re-drive. This converts a permanent freeze into a recovered hiccup plus a searchable error.
2. **A `bot.turn_stalled` event** feeding a `dc-gameplay` panel and an alert. It should fire whenever any seat holds the action longer than the turn clock allows. Humans already have a turn timer; bots have no equivalent backstop.
3. **Alert on the shape, not the name.** A `bot_action` span with zero children is, today, a perfect indicator of a swallowed intent. `{name="bot_action"} | count_over_time()` compared against applied-intent spans would have surfaced this the first time it happened.

Worth naming what dashboards could *not* have done here: every board was green throughout, because the server was healthy and the table was not. That is the same class of blind spot as ENG-45, where eight days of 500-second requests returned `200 OK` and every panel stayed green.
