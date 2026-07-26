# TODO

**Last reviewed:** 2026-07-21 (todo-maintainer) · **Companion to:** [backlog.md](./backlog.md), [developer-todo.md](./developer-todo.md)

The live punch list of actionable engineering work. Every item is something a worker can pick up and ship.

**Build the best thing, not the smallest change.** This is a greenfield, unshipped codebase — the goal is scalable, maintainable, production-ready systems. When you pick an item up, take a step back and ask what's genuinely best for the project and the user, then build that, even if it means restructuring or rebuilding something already here. Don't stack a minimal patch on top of what exists. Full rule: AGENTS.md → Coding Guidelines and the `work-item` skill (`.claude/skills/work-item/`) → Picking work.

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

## AUTH-29 [P1] — Authed user with no `auth.users` row gets a raw 500 and a silent client retry-storm

**Problem:** When a valid JWT names a user id that's no longer in Supabase `auth.users` (account deleted/banned mid-session, or a token minted against the wrong Supabase project), every `/v1/me/*/sync` write fails the FK to `auth.users` and the server returns a raw Postgres 500. The client swallows it and retry-storms (26–30 events in seconds) with nothing shown to the user. Found 2026-07-24 by deleting test users in Supabase while their clients were live — Sentry CARDS-BF/BG/BH/BP + BJ/BK/BM/BN + BQ, all since resolved.

**Acceptance:** Server detects "valid JWT, no `auth.users` row" before the child write and returns a clean typed response (401/409 → re-auth), not a 500 leaking the FK. Client maps it to sign-out + a "your session ended, sign in again" surface and stops the sync retry loop.

**Hints:** FK is `*_user_id_fk[ey]` → `auth.users(id)` ([V11](../apps/server/src/main/kotlin/com/cards/server/db/../resources/db/migration/V11__fk_auth_users.sql)). Write path is the `findOrCreate` repos (progression/wallet/profile/player-stats/play-style) inside `Database.transaction`. Reproduce test-first with a JWT for an id absent from `auth.users`. Pairs with the backlog item "Surface a reason when a server-side no is absorbed silently."

## ENG-35 [P1] — Banned client keeps firing (and Sentry-logging) doomed requests; no circuit breaker or un-ban recovery

**Problem:** A banned user's client keeps sending normal requests that all 403 `{"reason":"banned"}` (Sentry CARDS-BG), each logged to Sentry as an error. There's no local short-circuit, and no way out of the blocking `AccessDeniedScreen` without an app relaunch. Sibling to AUTH-29 (that's the missing-`auth.users` 500 path; this is the clean-403 banned path).

**Acceptance:** While banned, non-allowlisted requests are short-circuited client-side (zero wire traffic, no error telemetry); `/v1/me` stays allowlisted and a 200 clears the banned flag and dismisses the screen without relaunch. Expected 4xx (403 banned, 401) and user-cancellations no longer reach Sentry as errors.

**Hints:** Reuse the `ExpectedControlFlow` short-circuit pattern (auth). Full plan, file list, and tests: [`docs/agent/feedback-cases/ENG-35.md`](agent/feedback-cases/ENG-35.md). Insertion point `NetworkClientImpl.applyCommonConfig`; classifier `SentryLogTree.shouldCaptureEvent`.

## ENG-36 [P1] — Diagnose the starter-grant "double-miss" and surface reveal health

**Problem:** A new prod user saw neither the onboarding grant number nor the Home welcome-dialog backup, yet got their 10k chips. The onboarding miss was a null `onboarding.starterGrant` config (now seeded, V89) plus a tight 1.5s balance-sync window; why the Home backup *also* didn't fire is unconfirmed (`accountJustCreated` / `welcomeIdentity` race?). Both paths were silent until now.

**Acceptance:** Using the new `onboarding.grant_revealed` / `grant_reveal_degraded` events, confirm the double-miss cause and close it (e.g. widen `GRANT_REVEAL_TIMEOUT`, or make the Home backup fire whenever a fresh account never got a reveal). Add a `dc-funnel` panel: reveals by surface/source + degraded-with-no-backup.

**Hints:** `OnboardingViewModel.kickOffGrantReveal` (`GRANT_REVEAL_TIMEOUT` = 1.5s); `GetHomeScreenNotification.welcome()` gating (`accountJustCreated`, `didSeeInitialGrantInOnboarding`, `welcomeIdentity`). Events in `docs/wiki/app-events.md`. Panel stays empty until the release carrying these events ships.

## ENG-37 [P1] — Consolidate the starter-grant reveal onto the Home notification manager (drop the onboarding-step race)

**Problem:** The reveal exists twice: the bespoke onboarding "Starter grant" step (`kickOffGrantReveal`, which races the balance with a 1.5s `GRANT_REVEAL_TIMEOUT`) and the Home notification manager's `HomeNotification.Welcome`. The bespoke path is the fragile one that silently failed. **Decision (chosen): option B** — make the Home manager's `Welcome` the single, top-priority reveal and delete the onboarding-step race. Rationale: one arbiter for all launch/Home surfaces (future what's-new / promos / events all arbitrate against it instead of colliding); better timing (Home is past the account-creation cold start, so the real balance is likelier ready, with config as instant fallback); graceful re-present on resume when offline.

**This is NOT a straight swap — it removes the backup, so the Home `Welcome` gating must be bulletproof FIRST (depends on ENG-36).** Really consider the surrounding logic before ripping anything out: does `accountJustCreated` reliably survive the onboarding→Home handoff (prime suspect for the double-miss)? Is `welcomeIdentity` resolution timing race-free? Are `didSeeInitialGrantInOnboarding` semantics right once onboarding no longer sets it? The arbiter is a pure function, so there is **no excuse not to heavily unit-test it** — cover fresh account online, fresh account offline-then-reconnect, slow sync, Home resume re-present, account switch, process death mid-onboarding, and welcome-already-seen. It has to be bulletproof or it does not land.

**Acceptance:** Onboarding no longer runs the balance-race reveal (either no grant step, or a contentless "you're all set" beat); every new account sees the `Welcome` reveal exactly once via the manager, including the offline-then-reconnect path; `GRANT_REVEAL_TIMEOUT` and the balance race are deleted; all the gating edge cases above are covered by unit tests that would have caught the original double-miss.

**Hints:** `OnboardingViewModel.kickOffGrantReveal`; pure arbiter `GetHomeScreenNotification` + `HomeNotificationSnapshot`; `HomeViewModel` snapshot `combine` / `presentPendingBlocking` / `DialogIntroDelay` / watermark latches; `accountJustCreated` latch in the profile repo (`/v1/me` `isNewAccount`). Sequenced after ENG-36.

## ENG-38 [P1] — Emit stable install/device facts as OTel Resource attributes, then filter noise out of the dashboards

**Problem:** Prod client telemetry can't tell a genuine retail install from an emulator or a side-loaded store build, so that noise pollutes crash-free, DAU, and the all-time user count — one emulator ANR (CARDS-BR) dragged crash-free users to 94%. The launch events only carry `install_id` / `platform` / `previous_exit` / `release_channel`; the facts that identify noise aren't emitted.

**Acceptance:** Add stable install/device facts as OTel **Resource** attributes (set once at SDK init so they ride every log/span/metric): at minimum `is_emulator`, `is_sideloaded`, `installer_package`, `is_rooted` (+ `device_class`, `os_version` for segmentation). Verify the Loki OTLP mapping lands them as filterable structured metadata (promote only the low-cardinality genuineness booleans to labels, and only if cheap). Then update the `dc-pulse` crash-free / DAU / all-time-users panels to filter `genuine_install` with a dashboard "show all" toggle var (done via the Grafana MCP once a build carrying the attrs ships). **Tag, don't drop** — keep the noise queryable so we can still see piracy/emulator activity on purpose.

**Hints:** Sentry already computes these (`isSideLoaded`, `device.class`, `os`) on the event — mirror that logic into the Resource. Resource init lives near the telemetry bootstrap (OTel/Sentry setup, cf. `GrafanaAppEvents`). Debug builds already route to the dev env, so the prod gap is specifically store-build-on-emulator. Keep new attrs low-cardinality.

## ENG-39 [P1] — Stamp signup-platform on `profiles` and drive user growth from Postgres, not Loki

**Problem:** The all-time user count and growth-by-platform graph are install-based (Loki), so they cap at ~30d log retention and fold in emulator/side-load noise. Accounts (`profiles`) are created at onboarding completion — they persist forever in Postgres and are noise-light — but carry no `platform`, so they can't drive a by-platform growth curve.

**Acceptance:** Add a `platform` column to `profiles` (new V-migration), **stamped once at profile creation and never updated** (= signup-platform, an immutable cohort dimension — do NOT refresh it per request, or a user who uses both OSes flip-flops and retroactively rewrites past growth bars). Client includes `platform` in the profile insert only. Repoint the `dc-pulse` growth graph to Postgres — cumulative `profiles` by `created_at` split by `platform` (persistent, no 30d wall, dedup-free). Keep the Loki install panel as the DAU/reach view. **Decision (chosen): a typed `profiles` column, not `auth.users` metadata** — it's the table the dashboards already query, it's typed/indexable, and it needs no `auth`-schema grant (the profiles count doesn't touch `auth.users` at all). Reversible if we'd rather use `app_metadata`. If we later want *multi-platform usage* (not signup-platform), model it separately — a `user_platforms` set or derive it from install/session telemetry — don't mutate this column.

**Hints:** Profile-create path is the client's supabase-kt `findOrCreate` insert. Grafana Postgres datasource `ffrewas5byf40d` (prod) / `dfrex4f7bg7b4b` (dev) already runs SQL for panels 401–403. Pairs with ENG-38, whose `is_emulator` flag can optionally exclude dev/emulator activations from the count.
