# TODO

**Last reviewed:** 2026-07-04 (todo-maintainer) · **Companion to:** [backlog.md](./backlog.md), [developer-todo.md](./developer-todo.md)

The live punch list of actionable engineering work. Every item is something a worker can pick up and ship.

**Build the best thing, not the smallest change.** This is a greenfield, unshipped codebase — the goal is scalable, maintainable, production-ready systems. When you pick an item up, take a step back and ask what's genuinely best for the project and the user, then build that, even if it means restructuring or rebuilding something already here. Don't stack a minimal patch on top of what exists. Full rule: AGENTS.md → Coding Guidelines and [`docs/agent/worker-prompt.md`](agent/worker-prompt.md) → Picking work.

**Fixing a bug? Reproduce it with a failing test first.** Red (the test fails *because of the bug*), then green (the fix makes it pass). It proves you found the real cause, not a guess, and leaves a permanent regression guard. Can't reproduce it in a test? The harness is missing something — build that first. See AGENTS.md → Coding Guidelines.

**Minimum viable context.** Every item is one bold title + at most ~3 short lines (Problem / Acceptance / Hints). No status archaeology — don't narrate what already shipped, what's "locked," or which sub-part landed when. If a sub-part ships, delete that clause. A bullet a human can't skim in five seconds is too long.

**Item IDs.** Every item carries a stable ID — section prefix + number (`PROG-1`, `AUTH-3`, `MP-2`). IDs never get reused: when an item ships or moves to backlog, its number retires with it. Use the ID when referring to an item in commits, PRs, or other docs.

**ID prefixes:** `PROG` (progression / XP / stats), `AUTH` (auth + onboarding), `GAME` (gameplay + table UX), `SHOP` (consumables + rewards), `SOC` (social graph), `ROOM` (rooms UI), `MP` (multiplayer hardening), `ENG` (engineering / structural), `BILL` (billing).

**Priority tags** (every item carries one; bias toward P0 first):

- `[P0]` — V1 ship-blocker or load-bearing for other work.
- `[P1]` — Real value, not blocking.
- `[P2]` — Lower urgency, still worker-pickable. Many need a directional call — make a recommendation, ship a slice, let the reviewer course-correct.

Everything here is worker-pickable. Human-only work (device QA, dashboard config, content, product decisions) lives in [`developer-todo.md`](./developer-todo.md). Deferred ideas live in [`backlog.md`](./backlog.md) — when an item gets descoped or doesn't fit V1, move it there, don't delete it.

## ENG

- `[P1]` **ENG-12 — Sweep em dashes out of user-facing copy in strings.xml.** (proposed 2026-07-04) 11 strings in the rank/stats explainer copy (`rank_bullet_*`, `rank_axes_note`, `stats_explainer_*`) use em dashes, which AGENTS.md bans in user-facing copy ("rephrase or use a comma/period").
  **Acceptance:** `grep — libraries/resources/.../strings.xml` comes back empty; rephrased copy passes an unslop-text pass and keeps the app's warm plain voice.
  **Hints:** `libraries/resources/src/commonMain/composeResources/values/strings.xml` (~lines 618-640).

- `[P2]` **ENG-13 — Route ImagePicker's Android image decode through `DispatcherProvider`, not `Dispatchers.Default`.** (proposed 2026-07-04) `rememberImagePicker` calls `withContext(Dispatchers.Default)` directly, violating the never-reach-for-`Dispatchers.*` rule (untestable against the test scheduler).
  **Acceptance:** no direct `Dispatchers.*` usage in `:libraries:ui` production sources; the decode still runs off the main thread.
  **Hints:** `libraries/ui/src/androidMain/.../ImagePicker.android.kt:44`; it's a composable, so take the dispatcher/provider as a parameter or a composition local rather than constructor injection.

- `[P2]` **ENG-14 — Delete the dead `CameraPreview` surface.** (proposed 2026-07-04) The `CameraPreview` expect/actual + `CaptureController` in `:libraries:ui` have zero call sites anywhere (features, apps, Swift); the Android actual is an unimplemented "Camera Ready" placeholder with a TODO.
  **Acceptance:** `CameraPreview.kt` (common/android/ios), `CaptureController`, the `NativeViewFactory` camera hooks, and the Swift `CameraPreviewHost` / `IOSNativeViewFactory` camera funcs are removed; both platforms still build.
  **Hints:** `libraries/ui/src/{commonMain,androidMain,iosMain}/.../CameraPreview*.kt`, `libraries/ui/src/iosMain/.../nativeviews/NativeViewFactory.kt:38-42`, `apps/ios/iosApp/Platform/IOSNativeViewFactory.swift:42-47`.

- `[P2]` **ENG-15 — Fix the wallet wiki's key-files list: `ChipsSync` doesn't exist.** (proposed 2026-07-04) `docs/wiki/wallet.md` lists a `ChipsSync` key file; no such file exists — the sync logic lives in `ChipsRepositoryImpl.kt`. One-line doc fix; the page's other claims verify clean.
  **Acceptance:** the key-files list points at `libraries/cards/impl/.../ChipsRepositoryImpl.kt` (sync loop ~lines 142-215) instead of `ChipsSync`.
  **Hints:** `docs/wiki/wallet.md`, key-files section.
