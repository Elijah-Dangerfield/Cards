# The RenderThread text stall (ENG-49)

**Status:** diagnosed, not fixed. Not a release blocker.
**Sentry:** [CARDS-C1](https://elijah-dangerfield.sentry.io/issues/CARDS-C1), [CARDS-BZ](https://elijah-dangerfield.sentry.io/issues/CARDS-BZ)
**Case notes:** `docs/agent/feedback-cases/CARDS-C1.md`

## What actually happens

Two players have had the app freeze mid-hand and get killed by Android. Both times the cause
is the same: **the RenderThread gets stuck drawing, and every other thread piles up behind it.**

In CARDS-C1 the RenderThread was 79 frames deep, in the middle of a normal frame, here:

```
CanvasContext::draw
  → SkiaPipeline::renderFrame → renderFrameImpl
  → RenderNodeDrawable::forceDraw / drawContent      ← about 17 nested levels
  → SkCanvas::drawTextBlob
  → GrTextBlobRedrawCoordinator::drawGlyphRunList
  → GrTextBlobRedrawCoordinator::internalRemove
  → GrTextBlob::Key::operator==
```

Two things to take from that.

**It was drawing text, and the glyph cache was thrashing.** `internalRemove` inside
`drawGlyphRunList` means Skia is evicting cached text while trying to draw more of it, and
`Key::operator==` is the linear scan it does while evicting. A healthy text cache does not spend
its time in eviction. Something is producing text the cache cannot reuse.

**The render tree is about 17 levels deep.** Every nested `RenderNodeDrawable` pair is one layer.
That is a lot of layers for one frame, and it multiplies the cost of everything above.

Meanwhile a binder thread was blocked on `CanvasContext::onSurfaceStatsAvailable` waiting for a
mutex the RenderThread holds, which is corroboration that the RenderThread was the bottleneck and
not just slow for one frame.

## Read this before you start: the first diagnosis was wrong

The first pass at this concluded "opening a `ModalBottomSheet` blocks the main thread, so rework
the betting sheet." The main thread's stack really did end in `Dialog.show` → `ThreadedRenderer.create`
→ `pthread_cond_wait`, and every individual statement was true. The conclusion was still wrong.

The main thread was **waiting**. It wanted a render proxy for the sheet's new window, which means
a round trip to the RenderThread, and the RenderThread was wedged. Any caller needing that thread
would have hung identically. Reworking the sheet would have changed nothing.

That is why CARDS-BZ blocked in `syncAndDrawFrame` instead. Same wedge, different unlucky caller.

**So: do not start with the bottom sheets.** The lesson is now written into the
`observability-triage` skill, but it applies here directly. A stack ending in a wait names the
victim, not the cause.

## Confirmed 2026-09-03 by three Play Console ANR traces

Three more ANRs, pulled from Play Console, put this beyond hypothesis. **All three have a
byte-identical RenderThread stack**, and it is the same one CARDS-C1 had:

```
GrTextBlobRedrawCoordinator::internalRemove      ← top of stack in all four events
GrTextBlobRedrawCoordinator::drawGlyphRunList
skgpu::v1::SurfaceDrawContext::drawGlyphRunList
  ...
SkCanvas::drawTextBlob
  → 13 nested RenderNodeDrawable levels
  → DrawFrameTask::postAndWait
```

**The decisive part is the main thread, which is different in every one:**

| Trace | What `main` was blocked doing |
|---|---|
| `stacktrace.log` | Dialog **dismiss** → `destroyHardwareResources` → `nDestroyHardwareResources` |
| `stacktrace (1).log` | Dialog **show** → `ThreadedRenderer.create` → `nCreateProxy` |
| `stacktrace (2).log` | **An ordinary frame** → `ViewRootImpl.performTraversals` → `nSyncAndDrawFrame` |

Three unrelated operations — opening a window, closing a window, and drawing a normal frame — all
stalled behind the same wedged RenderThread. One of them involves no bottom sheet at all.

That closes the question the first investigation got wrong. **The bottom sheet is not the cause and
never was**; it is simply a frequent caller, so it shows up as the victim often. Anything needing
the RenderThread hangs identically, which is exactly what the third trace shows.

**Step 1 of the plan below is therefore already done.** No profiling run is needed to establish
that the RenderThread is the bottleneck; four independent production events say so. Start at
Step 2.

## What is proven and what is not

**Proven, across four independent production events:** the RenderThread is mid-frame, deep in
text rendering, with the glyph cache churning inside `internalRemove`, 13 to 17 render-node levels
deep, and whatever else needs that thread is blocked behind it.

Worth knowing what `internalRemove` inside `drawGlyphRunList` actually means: Skia is dropping a
cached text blob it cannot reuse and rebuilding it. Being caught there in every single sample is
the signature of text whose **draw parameters change every frame**. Blob reuse survives a change
in position; it does not survive a change in scale or rotation.

**Not proven:** what specifically is generating that text load. The leading suspect is text drawn
inside a continuously changing transform, because a new scale or rotation each frame means a new
glyph raster each frame and nothing can be cached. The table does exactly that shape in at least
two places:

- `BoardArea.kt:211` wraps `PlayingCard` (rank and suit are text) in a `graphicsLayer` animating
  `scaleX`, `scaleY` and `rotationY` for the deal and flip.
- `PlayerArea.kt` around :473 and :779 has the same pattern for hole cards and opponent cards.

That is a hypothesis. Confirm it before acting on it, because the last confident guess here cost a
day.

## What to do

### ~~Step 1: See it happen~~ — done, see the confirmation above

The cheapest confirmation is the on-device GPU profiler.

1. Developer options → **Profile GPU rendering** → *On screen as bars*.
2. Play a few hands against bots. Watch the bars during the deal and the card flips.
3. If the bars spike well over the green line exactly when cards animate, the hypothesis holds.

Then get the real numbers. Plug in a mid-range Android device (not a flagship, the whole point is
headroom), get to a table, and:

```bash
PKG=$(adb shell pm list packages | grep dangerfield.cards | sed 's/package://' | tr -d '\r' | head -1); echo "Using $PKG"
adb shell perfetto -o /data/misc/perfetto-traces/table.pftrace -t 20s \
  gfx view wm am sched freq input binder_driver &
echo ">>> RECORDING 20s: play through a deal and a showdown now <<<"; wait
adb pull /data/misc/perfetto-traces/table.pftrace ~/Desktop/ && echo "wrote ~/Desktop/table.pftrace"
```

Open it at [ui.perfetto.dev](https://ui.perfetto.dev) and look at the **RenderThread** track. You
want `DrawFrame` slices during a deal. If they are tens of milliseconds instead of single digits,
and they widen as more cards are on screen, that is the confirmation.

### Step 2: Find what is producing the text load

Work through these in order and stop when the numbers move.

1. **Cards animating under a transform.** The suspects above. The test is to temporarily make
   `PlayingCard` draw its rank and suit as a pre-rendered image or vector instead of text, or to
   drop the flip animation, and re-measure. If the stall goes, you have it.
2. **Text that changes every frame.** Chip stacks, the pot, win odds, countdowns. Search the room
   feature for text bound to a value that updates per frame rather than per state change.
   `displayedHumanStack` in `PlayPokerScreen.kt` is worth a look.
3. **Render-node depth.** 17 levels is the other half of the cost. Every `graphicsLayer`,
   `Modifier.shadow`, `clip`, and elevation adds one. Compose Layout Inspector will show the tree.
   Flattening the table's layer stack helps every frame, not just the pathological ones.

### Step 3: Fix, then prove it

Whatever you change, the acceptance is the same trace from step 1 showing the RenderThread inside
frame budget through a full hand on the same device. Capture before and after and keep both.

Likely shapes of fix, cheapest first:

- **Stop rastering text under animated transforms.** Render card faces as vectors or images, or
  snap the animation to fewer distinct scales, so the glyph cache can hit.
- **Flatten layers on the table.** Fewer `graphicsLayer` and shadow modifiers in the seat and
  board hierarchy.
- **Take text out of the hot path entirely** for the elements that animate every frame.

## Two things to fix while you are in here

Neither is the bug, both cost real time during the investigation.

**`achievement.celebration_shown` lies.** `PlayPokerViewModel.kt:636` logs it for every earned
achievement *before* the mode check, so it fires when nothing is shown. A multiplayer session
logged 11 of them with no celebration on screen, which sent the first investigation chasing the
celebration overlay. Rename it, or put the mode in the payload.

**The celebration overlay is unreachable in multiplayer.** `AchievementCelebrationSheet` is gated
on `celebrationActive`, only ever set when `isBots` (`PlayPokerScreen.kt:664,693`). Worth knowing
before anyone suspects it again.

## Why this is not urgent

- Already live in build 1026, so shipping or holding a release changes nothing about it.
- 2 ANRs in 29 days across the whole population, and Play Console vitals shows nothing yet at
  roughly 25 users.
- Both events needed a second stressor. CARDS-BZ followed an OOM kill 33 minutes earlier;
  CARDS-C1 landed in the middle of a 6.6 second network outage on a 14 kbps cellular connection.

That said, it is a foreground freeze on a new user's first session, against real opponents, which
is about the worst-feeling failure the app has. Worth doing properly once, not patching twice.

## Re-check first

Before spending a day on this, look at **Play Console → Android vitals → ANRs**. It has real
per-device rates that Sentry cannot give us. If it is a rounding error there, drop this to P2.
