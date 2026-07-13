# TODO

**Last reviewed:** 2026-07-13 (todo-maintainer) · **Companion to:** [backlog.md](./backlog.md), [developer-todo.md](./developer-todo.md)

The live punch list of actionable engineering work. Every item is something a worker can pick up and ship.

**Build the best thing, not the smallest change.** This is a greenfield, unshipped codebase — the goal is scalable, maintainable, production-ready systems. When you pick an item up, take a step back and ask what's genuinely best for the project and the user, then build that, even if it means restructuring or rebuilding something already here. Don't stack a minimal patch on top of what exists. Full rule: AGENTS.md → Coding Guidelines and [`docs/agent/worker-prompt.md`](agent/worker-prompt.md) → Picking work.

**Fixing a bug? Reproduce it with a failing test first.** Red (the test fails *because of the bug*), then green (the fix makes it pass). It proves you found the real cause, not a guess, and leaves a permanent regression guard. Can't reproduce it in a test? The harness is missing something — build that first. See AGENTS.md → Coding Guidelines.

**Minimum viable context.** Every item is one bold title + at most ~3 short lines (Problem / Acceptance / Hints). No status archaeology — don't narrate what already shipped, what's "locked," or which sub-part landed when. If a sub-part ships, delete that clause. A bullet a human can't skim in five seconds is too long.

**Item IDs.** Every item carries a stable ID — section prefix + number (`PROG-1`, `AUTH-3`, `MP-2`). IDs never get reused: when an item ships or moves to backlog, its number retires with it. Use the ID when referring to an item in commits, PRs, or other docs.

**ID prefixes:** `PROG` (progression / XP / stats), `AUTH` (auth + onboarding), `GAME` (gameplay + table UX), `SHOP` (consumables + rewards), `SOC` (social graph), `ROOM` (rooms UI), `MP` (multiplayer hardening), `ENG` (engineering / structural), `BILL` (billing), `ECON` (chip economy integrity), `MOD` (trust & safety / moderation), `SITE` (marketing / support static pages).

**Priority tags** (every item carries one; bias toward P0 first):

- `[P0]` — V1 ship-blocker or load-bearing for other work.
- `[P1]` — Real value, not blocking.
- `[P2]` — Lower urgency, still worker-pickable. Many need a directional call — make a recommendation, ship a slice, let the reviewer course-correct.

Everything here is worker-pickable. Human-only work (device QA, dashboard config, content, product decisions) lives in [`developer-todo.md`](./developer-todo.md). Deferred ideas live in [`backlog.md`](./backlog.md) — when an item gets descoped or doesn't fit V1, move it there, don't delete it.

---

## Trust & safety

- **[P0] MOD-1 — In-app report-a-player flow.** Google Play's UGC policy requires report **and** block for apps with user-visible content (editable display names + emotes among strangers in public rooms); we ship mute/emote-block only, no report → likely Play rejection at review.
  - *Acceptance:* a "Report" action on the player card ([`PlayerProfileSheet.kt`](../features/room/impl/src/commonMain/kotlin/com/cards/features/room/impl/ui/PlayerProfileSheet.kt), next to the existing mute toggle) that POSTs to a new server route and writes a row to a `player_reports` table (reporter id, reported user id, room/context, reason, created_at); reporter sees a confirmation; **no auto-ban** in V1.
  - *Hints:* mirror the mute wiring for the entry point; new Ktor route + Postgres migration. A moderation-review UI that pulls these reports for manual decisions, and auto-ban rules, are deferred — see [`post-launch.md`](./post-launch.md).

## Site / static pages

- **[P0] SITE-1 — Support page on GitHub Pages (FAQ + Contact us).** Both stores require a public support URL on the listing; we only host `privacy.html` + `terms.html`, so submission is gated on this.
  - *Acceptance:* `pages/support.html` in the same style as privacy/terms, with an FAQ section and a "Contact us" link (`mailto:contact@downcard.app`); linked from `pages/index.html` footer; add `SUPPORT_URL` to [`LegalUrls.kt`](../libraries/core/src/commonMain/kotlin/com/cards/libraries/core/LegalUrls.kt) and surface it in Settings alongside Privacy/Terms. Satisfies the "Support contact + public support URL" launch item in [`developer-todo.md`](./developer-todo.md).
  - *Hints:* copy `pages/privacy.html` structure + `pages/style.css`; deploy is automatic via [`pages.yml`](../.github/workflows/pages.yml) on push to `main`.

## Engineering / structural

- **[P0] ENG-30 — Bind the Darwin engine on the Supabase client (iOS signup fails at TLS).** On iOS TestFlight/release build 821 every `POST /auth/v1/signup` aborts with `TLS sessions are not supported on Native platform`, so no guest account can be minted, `creationDegraded` stays true, and the "Finishing setup" banner is permanent — surviving even account deletion (delete → re-mint → fails again). Loki shows every iOS session in the window hitting it, not just the reporter.
  - *Acceptance:* an iOS release build mints a guest account on first launch (no TLS warning in the client log; `AccountCreationState` settles to success) and the "Finishing setup" banner clears; add a regression guard that the Supabase client is built with the Darwin engine (mirror `PlatformHttpEngineTest`).
  - *Hints:* `SupabaseClientFactory.provideSupabaseClient` leaves the Ktor engine to auto-detection (its own KDoc says so) — pass `httpEngine` (Darwin, via `platformHttpEngineFactory`) into `createSupabaseClient { }`, exactly as ENG-28 did for the first-party clients (`NetworkClientImpl`, `PlatformHttpEngine.ios.kt`). Related: backlog "Audit the linked iOS klib graph for a transitive ktor-client-cio". Case `docs/agent/feedback-cases/464d30f47b994010bf3dd700f1d5d09b.md`; Sentry CARDS-9S/CARDS-9T.

---
