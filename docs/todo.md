# TODO

**Last reviewed:** 2026-07-12 (todo-maintainer) · **Companion to:** [backlog.md](./backlog.md), [developer-todo.md](./developer-todo.md)

The live punch list of actionable engineering work. Every item is something a worker can pick up and ship.

**Build the best thing, not the smallest change.** This is a greenfield, unshipped codebase — the goal is scalable, maintainable, production-ready systems. When you pick an item up, take a step back and ask what's genuinely best for the project and the user, then build that, even if it means restructuring or rebuilding something already here. Don't stack a minimal patch on top of what exists. Full rule: AGENTS.md → Coding Guidelines and [`docs/agent/worker-prompt.md`](agent/worker-prompt.md) → Picking work.

**Fixing a bug? Reproduce it with a failing test first.** Red (the test fails *because of the bug*), then green (the fix makes it pass). It proves you found the real cause, not a guess, and leaves a permanent regression guard. Can't reproduce it in a test? The harness is missing something — build that first. See AGENTS.md → Coding Guidelines.

**Minimum viable context.** Every item is one bold title + at most ~3 short lines (Problem / Acceptance / Hints). No status archaeology — don't narrate what already shipped, what's "locked," or which sub-part landed when. If a sub-part ships, delete that clause. A bullet a human can't skim in five seconds is too long.

**Item IDs.** Every item carries a stable ID — section prefix + number (`PROG-1`, `AUTH-3`, `MP-2`). IDs never get reused: when an item ships or moves to backlog, its number retires with it. Use the ID when referring to an item in commits, PRs, or other docs.

**ID prefixes:** `PROG` (progression / XP / stats), `AUTH` (auth + onboarding), `GAME` (gameplay + table UX), `SHOP` (consumables + rewards), `SOC` (social graph), `ROOM` (rooms UI), `MP` (multiplayer hardening), `ENG` (engineering / structural), `BILL` (billing), `ECON` (chip economy integrity).

**Priority tags** (every item carries one; bias toward P0 first):

- `[P0]` — V1 ship-blocker or load-bearing for other work.
- `[P1]` — Real value, not blocking.
- `[P2]` — Lower urgency, still worker-pickable. Many need a directional call — make a recommendation, ship a slice, let the reviewer course-correct.

Everything here is worker-pickable. Human-only work (device QA, dashboard config, content, product decisions) lives in [`developer-todo.md`](./developer-todo.md). Deferred ideas live in [`backlog.md`](./backlog.md) — when an item gets descoped or doesn't fit V1, move it there, don't delete it.

---

## MP — multiplayer hardening

**MP-32 [P1] — Wire opponent modeling into multiplayer bots**
- Problem: bots have a real adaptive layer (`OpponentTracker` → shove-monster/passive-caller detection → call lighter vs a serial jammer), but it's fed only in solo (`LocalBotsSession`). `ServerBotDriver.drive` calls `BotDecision.choose` without a tracker, so MP bots use a fresh empty tracker every decision and never adapt to anyone — the samey/predictable complaint, for the bots most players actually face.
- Acceptance: the server holds one `OpponentTracker` per session, fed from the game event/action stream, passed into `choose`; MP bots demonstrably call down a repeat jammer after enough hands (add a test). Consider also detecting a habitual big-bet bluffer, not just literal shovers (`aggressionFrequency`/`pfr` are tracked but unused).
- Hints: [ServerBotDriver.kt](../apps/server/src/main/kotlin/com/cards/server/game/ServerBotDriver.kt), [OpponentProfile.kt](../libraries/bots/src/commonMain/kotlin/com/cards/libraries/bots/OpponentProfile.kt).

## GAME — gameplay + table UX

**GAME-30 [P1] — Pre-action toggles (check/fold, check-any)**
- Problem: no pre-select actions exist; a player must wait for their turn to act even when their decision is already made. Standard poker QoL and a named competitor gap.
- Acceptance: on the action UI, a player can arm "Check/Fold" and "Check any" before their turn; the armed action fires automatically on turn arrival and clears if the situation changes (e.g. facing a raise cancels "check").
- Hints: action UI in [PlayerActionSheet.kt](../features/room/impl/src/commonMain/kotlin/com/cards/features/room/impl/ui/PlayerActionSheet.kt) / [TableActionBar.kt](../features/room/impl/src/commonMain/kotlin/com/cards/features/room/impl/ui/TableActionBar.kt).

## BILL — billing

**BILL-10 [P1] — Confirm step on the post-bust quick-buy**
- Problem: the storefront has a two-step `PurchaseConfirmSheet`, but the in-game `QuickBuyChipsSheet` (shown after a MP bust) goes straight to `purchaseChipPack(...)` — one tap closer to a real charge, at the emotionally-loaded just-busted moment. Only the OS store dialog gates it.
- Acceptance: post-bust quick-buy shows the same lightweight confirm (price + "charged via the App Store / Google Play" line) as the storefront before the purchase fires.
- Hints: [PlayPokerViewModel.kt:1019](../features/room/impl/src/commonMain/kotlin/com/cards/features/room/impl/PlayPokerViewModel.kt), storefront pattern in `PurchaseConfirmSheet.kt`.

## AUTH — auth + onboarding

**AUTH-9 [P0] — Fix the one false claim on the privacy page (+ 2 minor overstatements)**
- Problem: [pages/privacy.html](../pages/privacy.html) line 23 says the anonymous account lets chips/items/progress "survive an app reinstall or a phone swap" — FALSE on Android reinstall (encrypted prefs + keystore key wiped on uninstall) and on device-swap for both platforms (iOS keychain item is not `kSecAttrSynchronizable`, no server `recovery_id`). It also contradicts our own [terms.html](../pages/terms.html) line 51, which is correct ("tied to the device… we can't recover anonymous accounts"). This is the only hard falsehood; the rest of both docs audited accurate.
- Acceptance: rewrite line 23 to state the account is device-local while installed, does NOT auto-move to a new phone or survive uninstall, and that claiming (email/Apple/Google) is the durable path — making privacy.html consistent with terms.html and `docs/wiki/account-lifecycle.md`. Also (minor) narrow the "hosted in the United States" line to Fly+Supabase (Sentry/Grafana region isn't pinned in-repo), and swap the illustrative "game started" telemetry example for a real event (`room.joined`). Consider adding: anonymous accounts can be auto-reaped when abandoned, and `install_id` is stored on the profile row.
- Note: neither doc currently makes a fairness/randomness claim — keep it that way unless/until a verifiable shuffle ships, since the server uses `Random.Default` (not a CSPRNG), so any "provably fair" wording would be false as built.
- Hints: iOS keychain [IOSSecureSessionStorage.swift](../apps/ios/iosApp/Platform/IOSSecureSessionStorage.swift), Android [EncryptedSessionStorage.kt](../libraries/identity/impl/src/androidMain/kotlin/com/cards/libraries/identity/impl/auth/EncryptedSessionStorage.kt), ground truth in [docs/wiki/account-lifecycle.md](../docs/wiki/account-lifecycle.md).
