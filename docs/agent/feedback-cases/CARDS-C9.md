# CARDS-C9 — ANR on the multiplayer play screen, RenderThread in the glyph cache

**Triaged:** 2026-09-21 · **Disposition:** no new todo — belongs to **ENG-49**, whose fix is written and merged but has never shipped.

| | |
|---|---|
| Issue | https://elijah-dangerfield.sentry.io/issues/7744693593/ (`CARDS-C9`) |
| Level | `fatal`, `ApplicationNotResponding`, mechanism `AppExitInfo` |
| Scale | 1 event, 1 user, first and last seen 2026-09-20 22:35 UTC |
| Release | `cards@0.1.0+1135` · `store-android-release` — **a real Play user** |
| Route | `PlayMultiplayerRoute` |
| Device | ABR-NX1, Android 16, not rooted |
| Correlation | `session_id=f00fb301-e5e7-44b7-a02f-20f8eb89b771` · `install_id=967396bd-79d0-49f6-880e-b1052e0a709b` · `user.id=323a6ae7-4a00-4b3e-baf7-598c6aa489e1` |

## What the threads actually say

Sentry's default view shows `main`, whose culprit is `syscall` and whose `in_app_frame_mix` is `system-only`. Read alone it says nothing useful, and read carelessly it says "dismissing a dialog hangs the app", which is the same wrong answer CARDS-C1 produced.

**`main` (marked crashed) is the victim.** Innermost frames outward:

```
syscall → __futex_wait_ex → pthread_cond_wait
  → std::__1::future<T>::get
  → android::uirenderer::renderthread::RenderProxy::destroy
  → HardwareRenderer.destroy → ThreadedRenderer.destroy
  → ViewRootImpl.destroyHardwareRenderer → dispatchDetachedFromWindow → doDie → die
  → WindowManagerGlobal.removeViewLocked → removeViewImmediate
  → Dialog.dismissDialog → Dialog.dismiss
  → Effects.kt:70 dispose → Effects.kt:91 onForgotten
  → RememberEventDispatcher.dispatchRememberObservers
  → Composition.applyChangesInLocked → Recomposer.kt:733
```

A Compose `DisposableEffect` tore down a dialog, which synchronously destroys the hardware renderer and **blocks on a `future` until the RenderThread finishes**. `main` is asleep waiting. It is not the cause.

**`RenderThread` (67 threads in the event; this one has 77 frames) is the cause.** Innermost:

```
sktext::gpu::TextBlob::Key::operator==
sktext::gpu::TextBlobRedrawCoordinator::internalRemove
sktext::gpu::TextBlobRedrawCoordinator::addOrReturnExisting
  → repeated DisplayListData::draw / RenderNodeDrawable::drawContent nesting, ~6 levels
```

Skia's glyph cache, churning text blobs, under a deeply nested draw tree. **This is the ENG-49 signature**, the same class as CARDS-C1, CARDS-BZ and CARDS-C3.

## Why this is not a regression, and what it actually proves

The obvious read — "ENG-49's fix failed, the ANR is back" — is wrong, and the dates are the whole argument. All times UTC:

| Event | When |
|---|---|
| Build `1135` created | 2026-09-03 **15:37** |
| `main` tip at that moment | `de144c9e` (2026-09-03 14:40) |
| `68cfb53b` turn pulse off composition | 2026-09-03 **17:50** |
| `d5bc323c` ENG-49 sweep, 4 more reads | 2026-09-03 **19:07** |
| `a5a634a2` card animations off composition | 2026-09-03 **20:22** |
| PR #152 merged to `main` | 2026-09-04 **21:43** |
| ANR occurred | 2026-09-20 **22:35** |

`PlayerArea.kt` on `main` at the moment 1135 was built had last been touched **2026-07-18**. Build 1135 contains none of the fix.

So CARDS-C9 does not disprove the fix. **It proves the bug is still live for real users, on a build from before the fix, seventeen days after the fix merged.**

## The actual finding: nothing has shipped since 2026-09-03

- Last `release.yml` run: **2026-09-03 14:40**, before PR #152 merged.
- Newest `cards` release Sentry has seen with sustained traffic: `cards@0.1.0+1135`, still receiving events as of 2026-09-20.
- `cards@0.2.0+1` exists (created 2026-09-03 16:37) but its last event is 2026-09-04 — one day of traffic, then silence. It also predates the three fix commits, so it would not have helped either.
- **72 commits sit on `main` since the `v0.2.0` tag**, unshipped. The ENG-49 fix is one of them; so are R8, the baseline profiles, the Sentry mapping upload and the play-screen crash fix.

The fix was never the blocker. Cutting a release is.

## Proven vs inferred

**Proven:** the thread topology above, the release/commit chronology, and that the affected user was on a retail Play build with no fix in it.

**Inferred:** that this is the *same* root cause as CARDS-C1 rather than merely the same symptom. The Skia frames are identical and the route is the play screen, but the event carries no composable-level attribution, so the specific animation is not named here.

**Not ruled out:** that a composable outside the three ENG-49 fixed still reads animated state during composition, in which case shipping helps but does not fully close it. This is less likely than it was — `AnimatedStateReadInComposition` now runs (ENG-54) and cleared 19 instances — but "the linter is clean" is not the same as "no composable recomposes per frame", since the rule only catches the `by animateFloatAsState(...)` shape.

## What to do

Ship. ENG-49's acceptance ("no new ANR with this stack for four weeks") cannot begin to be measured until a build containing the fix reaches users, and that clock has not started. If the ANR recurs on a post-fix build, that is the real signal, and `scripts/compose-trace.sh` is the next step.
