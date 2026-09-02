# CARDS-C1 — a foreground ANR: the RenderThread wedged on text, everything queued behind it

**Sentry:** [CARDS-C1](https://elijah-dangerfield.sentry.io/issues/CARDS-C1) ·
`ApplicationNotResponding: ANR`, level `fatal`, mechanism `AppExitInfo` ·
1 event, 1 user, **2026-09-02T20:14:00Z**.

> **Corrected 2026-09-02.** This file first concluded that mounting a `ModalBottomSheet` was the
> cause, reasoning only from the crashed thread. That was wrong. The main thread was *waiting*;
> reading the other 54 threads in the same event showed the RenderThread wedged in Skia's glyph
> cache. The original reasoning is kept below under "The wrong turn" because the mistake is more
> instructive than the finding.

The event carries **55 thread stacks**. The crashed one names where the app stopped. The
RenderThread's names why.

## Not the benign class, again

The wiki exempts one ANR shape (`CARDS-BR`): **no** first-party frames **and** an emulator or
side-load fingerprint. The gate needs both, and this event fails the second outright.

| Gate criterion | CARDS-BR (benign) | CARDS-C1 (this) |
|---|---|---|
| Build image | `sdk_phone_arm64` / `test-keys` | `MyOS13.0.1_Z2356_UA`, a real nubia ROM ✗ |
| Install source | side-loaded | `com.android.vending`, `isSideLoaded=false` ✗ |
| `device.simulator` | true | **False** ✗ |
| PairIP `LicenseActivity` | foregrounded | not present ✗ |

## Who

| | |
|---|---|
| device | ZTE **nubia Z2356**, Android 13, **8.17 GB RAM**, 8 cores @ 1.95 GHz |
| release | `cards@0.1.0+1026`, `store-android-release`, `commit_sha 4ea79519ef9c` |
| install / user | `35bd58cd-a830-4f12-a74b-6bf0ca99e8e5` / `f2ff4c6e-47b8-4a77-983f-604f02465ef3` |
| where | `route = PlayMultiplayerRoute`, `room_code = QXZCTS`, `seat_index = 1`, `hand_number = 9` |
| opponents | two real user ids |
| **foreground** | **`app.in_foreground = True`** |

Two things separate this from CARDS-BZ and both make it worse.

**It was in the foreground.** CARDS-BZ died in the background, so the player didn't watch it
happen. This player was looking at a frozen poker table, mid-hand, against two other humans.

**The device is not memory-starved.** 8.17 GB and 8 cores. Sentry tags it `device.class = low`,
but that classification is not carrying its weight here — this is not the low-RAM story ENG-49 was
built around. Whatever blocked the main thread was not the phone being small.

## The main thread's stack (the victim)

44 frames. The interesting run, reading inward:

```
androidx.compose.runtime.CompositionImpl.applyChanges
  → RememberEventDispatcher.dispatchRememberObservers
  → DisposableEffectImpl.onRemembered
  → ModalBottomSheet_androidKt.ModalBottomSheetDialog_sW7UJKQ$lambda$8$lambda$7
  → android.app.Dialog.show
  → WindowManagerImpl.addView → WindowManagerGlobal.addView → ViewRootImpl.setView
  → ViewRootImpl.enableHardwareAcceleration
  → ThreadedRenderer.create → HardwareRenderer.<init> → nCreateProxy
  → RenderProxy::RenderProxy
  → pthread_cond_wait → __futex_wait_ex → syscall
```

Read that backwards and it is one sentence: **Compose committed a composition that mounted a
`ModalBottomSheet`, and showing it blocked the main thread waiting for the render thread to build
a renderer for a brand-new window.**

That cost is structural, not incidental. Material3's `ModalBottomSheet` on Android is not drawn
into the existing window — it is a real `Dialog`, so every time one enters composition Android
allocates a window, a `ViewRootImpl`, and its own `ThreadedRenderer` backed by a fresh
`RenderProxy`. Constructing that proxy synchronises with the render thread. When the render
thread is already saturated the main thread simply waits, and past ~5s the platform calls it an
ANR.

Note that CARDS-BZ ends in the same two frames (`pthread_cond_wait` → `__futex_wait_ex`) via
`syncAndDrawFrame`. **Both ANRs on record are the main thread blocked on the render thread.**
CARDS-C1 is the one that names what asked it to block.

## The RenderThread's stack (the cause)

79 frames, mid-frame, not idle:

```
CanvasContext::draw → SkiaOpenGLPipeline::draw → SkiaPipeline::renderFrame → renderFrameImpl
  → RenderNodeDrawable::forceDraw / drawContent / DisplayListData::draw   ← ~17 nested levels
  → SkCanvas::drawTextBlob
  → GrTextBlobRedrawCoordinator::drawGlyphRunList
  → GrTextBlobRedrawCoordinator::internalRemove
  → GrTextBlob::Key::operator==
```

The RenderThread was **drawing text with the GPU glyph cache in eviction**. `internalRemove`
inside `drawGlyphRunList` is Skia throwing cached text away while trying to draw more of it, and
`Key::operator==` is the linear scan it runs while doing so. A cache that spends its time evicting
is a cache that is never hitting, which means something is producing text it cannot reuse.

The tree is also ~17 `RenderNodeDrawable` levels deep, which multiplies the cost of every frame.

Corroboration from a third thread: `binder:32032_6` was blocked in
`CanvasContext::onSurfaceStatsAvailable` → `pthread_mutex_lock`, waiting on a mutex the
RenderThread holds. Two threads stacked up behind it, not one.

So the main thread's `Dialog.show` is where the freeze surfaced, not what caused it. Any caller
needing the RenderThread would have hung the same way, which is exactly what CARDS-BZ did through
`syncAndDrawFrame`.

**Leading hypothesis, not proven:** text rastered under a continuously changing transform, since a
new scale or rotation per frame means a new glyph raster per frame. `BoardArea.kt:211` and
`PlayerArea.kt` (~:473, ~:779) wrap `PlayingCard`, whose rank and suit are text, in a
`graphicsLayer` animating `scaleX`/`scaleY`/`rotationY`. Plan to confirm or kill it:
`docs/plans/renderthread-text-stall.md`.

## The wrong turn

Kept deliberately. This is what the case concluded from the crashed thread alone, before anyone
read the other 54. Everything asserted below is individually true and the conclusion is still
wrong, which is the point.

### Which sheet

Not provable from the stack — it names `ModalBottomSheet_androidKt`, not our call site. The
candidates reachable on `PlayMultiplayerRoute` are in `PlayPokerScreen.kt`:
`PlayerActionSheet` (:425), `HandRankingsCheatSheet` (:442), `HowToPlaySheet` (:462),
`PlayerProfileSheet` (:581, :616), `BadgeDetailSheet` (:630), `ReportPlayerSheet` (:634),
`QuickBuyChipsSheet` (:783).

**`PlayerActionSheet` is much the most likely**, on frequency alone. It is the betting sheet, and
it is composed conditionally:

```kotlin
if (actionSheetOpen && active?.isHumanTurn == true && legal != null) {
    BottomSheet(onDismissRequest = { actionSheetOpen = false }, ...) { PlayerActionSheet(...) }
}
```

`actionSheetOpen` is a plain `remember { mutableStateOf(false) }` flipped true by
`onExpandRaise` (:392), so the sheet mounts fresh **every time the player opens the raise sizer**.
Not every decision — fold, call and check go straight through `onIntent` without a sheet. Still
repeated many times across nine hands, and each mount is a new window and a new render proxy.

### Corrects a hypothesis in CARDS-BZ

The CARDS-BZ case file named the achievement celebration overlay as the prime suspect, on the
grounds that a dozen fired during that game. **That was wrong, and ENG-49's "profile a long bots
session with the achievement queue firing" would have chased it into the ground.**

`AchievementCelebrationSheet` is gated on `celebrationActive`, and `PlayPokerScreen.kt:664,693`
only ever set that flag when `isBots`. CARDS-BZ was a multiplayer game against a real human, as is
this one, so no celebration sheet was ever mounted in either. This session logged 11
`achievement.celebration_shown` events, which looks damning until you read
`PlayPokerViewModel.kt:636` — that event is emitted for every earned achievement *before* the mode
check, so it fires whether or not anything is displayed. **The event name overstates what it
records.** Worth renaming, or adding the mode to the payload.

## What else was happening at 20:14

From the client stream, `session_id = c468241d-e626-4447-8825-027123425f20`:

| time | |
|---|---|
| 20:00:41 | cold launch, `previous_exit=unknown`; brand-new install, onboarding |
| 20:03:09 | `onboarding.completed` (abandoned twice first, at 20:02:47 and 20:03:00) |
| 20:06:18 | `matchmaking.search_started` → `room.joined` → `game.started` 20:06:34 |
| 20:06:52 → 20:12:11 | hands 1 through 8, roughly one every 45 seconds |
| **20:13:52** | `conn.reconnecting`, `Software caused connection abort` |
| **20:13:53** | `conn.reconnecting`, `net.offline_banner`, `Unable to resolve host "cards-server-prod.fly.dev"` |
| **20:14:00** | **ANR**, `hand_number = 9` |

So the network dropped **eight seconds before** the freeze. That is very likely the second
stressor rather than a coincidence: reconnect churn and recomposition on top of the window
allocation. CARDS-BZ also had a second stressor (an OOM kill 33 minutes earlier). Neither ANR
happened on a quiet table.

Also worth noting this is a **brand-new user's first session** — installed, onboarded, went
straight into a real multiplayer game, and the app froze on hand 9.

## Population rate (29 days, whole prod population)

```
previous_exit=anr    2
previous_exit=oom    9
previous_exit=crash  0
```

Both ANRs and **7 of the 9 OOMs** fall in the last 7 days. That skew is expected and is mostly
already dealt with: the OOM cluster tracks the ENG-45 / ENG-47 sync wedge, whose server halves
only landed 2026-09-01 and 2026-09-02. Re-measure in a week before drawing anything from the OOM
number.

## Recommendation

**Not a release blocker, and not urgent.**

- Not a regression. Already live in build 1026, so shipping or holding a release changes nothing.
- 2 ANRs in 29 days population-wide, and Play Console vitals shows nothing at ~25 users.
- Both events needed a second stressor. CARDS-BZ followed an OOM kill 33 minutes earlier; this one
  landed inside a 6.6s network outage on a 14 kbps cellular link.

It is still a foreground freeze on a new user's first session against real opponents, which is the
worst-feeling failure the app has. Worth doing once, properly.

## What would settle it

Full plan in `docs/plans/renderthread-text-stall.md`. In short:

1. **Confirm the RenderThread is the bottleneck during a deal.** On-device GPU profiling bars
   first, then a Perfetto trace of the `DrawFrame` slices on a mid-range device.
2. **Find the text load.** Test the leading suspect by rendering card faces as vectors or images
   instead of text, or by dropping the flip animation, and re-measuring. Then look for text bound
   to per-frame values (chip stacks, pot, odds, countdowns).
3. **Flatten the render tree.** ~17 nested layers is the other half of the cost and helps every
   frame, not just the pathological ones.
4. Chart ANR/OOM by `device.class` and platform so this is a rate rather than two anecdotes.
5. Fix the misleading `achievement.celebration_shown` event (`PlayPokerViewModel.kt:636`), which
   fires when no celebration is shown and sent the first investigation down the wrong path.

**Do not** start with the bottom sheets.
