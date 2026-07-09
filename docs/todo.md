# TODO

**Last reviewed:** 2026-07-08 (todo-maintainer) · **Companion to:** [backlog.md](./backlog.md), [developer-todo.md](./developer-todo.md)

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

## ENG — engineering / structural

- **ENG-16 `[P0]` Stop sampling away release error events — profiling rate is mis-wired into `options.sampleRate`.** Problem: `AppTelemetry.initialize` assigns `config.profilesSampleRate` (0.05 in release) to `options.sampleRate`, Sentry's *error-event* sample rate, so store builds silently drop ~95% of all error/message events — crashes and feedback carriers included; the owner's first prod feedback (2026-07-09) was lost this way.
  **Acceptance:** release builds send 100% of error events (`options.sampleRate` unset or 1.0), profiling wired to a real profiles-rate option or removed; a failing-first test pins the config→options mapping.
  **Hints:** `libraries/cards/impl/src/commonMain/kotlin/com/cards/libraries/cards/impl/AppTelemetry.kt:88` + `SentryRuntimeConfig.forApp` (~line 401); case `docs/agent/feedback-cases/2026-07-09-prod-feedback-never-ingested.md`.

- **ENG-15 `[P2]` Rename Virtu-branded ObjC bridge names to Cards.** Problem: the Kotlin↔Swift bridge still exports `VirtuNativeViewFactory` / `VirtuNativeAppleSignInButtonKind` / `VirtuNativeAppleSignInButtonStyle` (`@ObjCName(..., exact = true)` in `libraries/ui/src/iosMain/.../nativeviews/NativeViewFactory.kt`, referenced from `apps/ios/iosApp/iOSApp.swift` and `Platform/IOSNativeViewFactory.swift`) — leftover branding from two template generations ago. Acceptance: prefix renamed to `Cards*` in the Kotlin annotations and all Swift references; while there, prune anything in the bridge Cards doesn't use (camera code is already gone — check nothing else is dead); verified by an iOS simulator build of the `iosApp` scheme (Swift compiles against the generated framework header — Kotlin compilation alone proves nothing) with zero `Virtu` hits left in the generated `ComposeApp.h`. Hints: the same rename shipped in KMPTemplate main as `90a9eb5` — mirror it.
