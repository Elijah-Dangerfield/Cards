# CARDS-BZ — a real retail low-end Android was ANR-killed mid-multiplayer-hand, 33 minutes after an OOM kill on the same device

**Sentry:** [CARDS-BZ](https://elijah-dangerfield.sentry.io/issues/CARDS-BZ) ·
`ApplicationNotResponding: Background ANR`, level `fatal`, mechanism `AppExitInfo` ·
1 event, 1 user, **2026-08-31T14:26:47Z**.

## This is not the known-benign ANR class

The wiki exempts one ANR shape (`CARDS-BR`): a stack with **zero first-party frames** *and* an
emulator / side-load fingerprint, which is Play's PairIP licensing wrapper engaging on an
unlicensed copy. The gate needs **both**. This event has the first and fails the second twice over:

| Gate criterion | CARDS-BR (benign) | CARDS-BZ (this) |
|---|---|---|
| First-party frames in the stack | none | none ✓ matches |
| Build image | `sdk_phone_arm64` / `test-keys` | `T2SES33.73-23-2-15` — real Motorola ✗ |
| Install source | side-loaded | `com.android.vending`, `isSideLoaded=false` ✗ |
| PairIP `LicenseActivity` foregrounded | yes | not present ✗ |

`device.simulator = False`, `os.rooted = no`. This is a retail phone with a Play install. The
exemption does not apply, so per the wiki's own instruction it gets triaged as a real signal.

## Who

| | |
|---|---|
| device | moto g42, Android 13, **3.59 GB RAM**, `device.class = low`, 8 cores |
| release | `cards@0.1.0+1026`, `store-android-release`, `commit_sha 4ea79519ef9c` |
| install / user | `9357d79b-a605-41dd-b259-e41d5e387162` / `36e0b4e6-bcd7-491f-8237-8b2ad1fe75df` |
| where | `route = PlayMultiplayerRoute`, `room_code = 7Y24ZE`, `seat_index = 1`, `hand_number = 22` |
| opponent | `ec231621-34c3-497a-ac5d-23589a8fcf78` (a real second human, not a bot) |
| foreground | `app.in_foreground = False` |

## The session story

From the client Loki stream, `session_id="11599941-5e49-4074-a226-bb5a7c526811"`:

1. **13:54:04** — cold launch, and it reports **`previous_exit=oom`**. The run before this one was
   already killed for memory.
2. 13:55–13:57 — joins a room by private code, starts a multiplayer game, leaves after 125 s with
   `hands_played=0`, backgrounds the app.
3. **14:01:47** — foregrounds again, joins another private-code room, game starts 14:03:17.
4. 14:03 → 14:25 — **21 hands** play out, interleaved with roughly a dozen
   `achievement.celebration_shown` events (`first_hand`, `pot_500`, `pot_1000`, `pot_5000`,
   `show_pair`, `show_two_pair`, `show_three_of_kind`, `show_straight`, `show_flush`,
   `show_full_house`, `hands_10`, `good_fold_first`).
5. **14:25:08** — `hand.completed hand_number=21`. Last log line of the session.
6. **14:26:47** — ANR, roughly 100 s later, tagged `hand_number=22`.

So the device suffered **two resource kills 33 minutes apart** — an OOM, then an ANR — across one
sitting.

## The stack

25 frames, no first-party code. The main thread is inside a draw:

```
ActivityThread.main → Looper.loop → Choreographer.doFrame → ViewRootImpl.performTraversals
  → ViewRootImpl.performDraw → ThreadedRenderer.draw → HardwareRenderer.syncAndDrawFrame
  → nSyncAndDrawFrame → DrawFrameTask::drawFrame → pthread_cond_wait → __futex_wait_ex → syscall
```

The main thread is blocked waiting on the render thread to finish a frame. That's the signature of
a frame the GPU/render thread can't complete in time, not of a first-party deadlock — which is
also why there are no Downcard frames to point at.

## Wider signal: four installs OOM-killed in 30 days

`app.launched | previous_exit=~"oom|anr|crash"` over 30 days, prod, whole population:

| install | exits |
|---|---|
| `9357d79b` (this moto g42) | 1 oom + 1 anr |
| `6a17639a` | 2 oom |
| `4aaa6495` | 1 oom |
| `8fa92e95` | 1 oom |

Four distinct installs against a population in the low tens. Worth separating, though:
**`6a17639a` is the ENG-45 wedge user**, whose client serializes a 2,703-row XP outbox into a
200 KB+ JSON body on every sync attempt, several concurrently under `RetryPolicy.idempotent()`
(see CARDS-BY, where the server rejected a 212,992-byte truncated body). Their two OOMs are very
plausibly that, and ENG-45 already fixes it. This install is not in that state — its user is not
among the top XP-backlog holders, so its outbox is small and the XP payload is not the explanation
here.

## Working theory

Sustained rendering + allocation pressure on a low-RAM device over a long multiplayer session,
not a specific first-party hang. The achievement celebration overlays are the most suspicious
first-party contributor — a dozen of them fired during this game, each an animated overlay on top
of a live table — but that is a hypothesis, not a finding. Nothing in this event proves which
allocation crossed the line.

`app.in_foreground = False` means the player wasn't watching a frozen screen. It is still real
harm: the process dies while they are seated in a live hand against another human, so they drop
the table mid-game and their opponent waits out a timeout.

## What would settle it

1. Split ANRs and OOMs by `device.class` and `platform` on a dashboard — one event is an anecdote,
   a rate on low-end devices is a verdict. This is the same gap ENG-42 has for iOS.
2. Profile a long bots session on a ~4 GB device with the achievement queue firing repeatedly, and
   watch for a monotonic heap climb across hands.
3. Re-check after ENG-45 ships. If `6a17639a`'s OOMs stop, the remaining cluster is the real
   low-end signal and is smaller than it looks today.

Filed as **ENG-49 `[P1]`**.
