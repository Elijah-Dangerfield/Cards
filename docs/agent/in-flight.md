# In-flight

Handoff log for the current cycle. One block per commit. The reviewer reads this when writing the PR, then deletes the file.

## feat: badge the Profile settings gear with the unread count

**Problem:** The unread-notifications count only surfaced on the Profile bottom-tab and the in-Settings "Notifications" row, but the actual path to the inbox is the top-bar gear — a user already on Profile got no signal there was something to read.
**Approach:** Lifted a `BadgedIconButton` primitive into `:libraries:ui` (wraps `IconButton` in the existing `BadgedBox`, mirroring the bottom-tab badge language: numbered pill for `badgeCount > 0`, bare dot for `showDot`, both defaulting off). Plumbed `observeUnreadInboxCount()` into the `ProfileRoute` block's `ProfileSettings` (it was only wired into `SettingsRoute` before) and swapped the gear's plain `IconButton` for the badged one.
**Reviewer notes:** Badge clears when the inbox is opened because it's reactive off the same `observeUnreadInboxCount()` flow the Settings row uses — no extra clear logic. Visual placement (`DpOffset(-4,4)`) eyeballed against the bottom-tab's `(-5,5)` but not rendered in Studio; worth a glance against the new `BadgedIconButtonPreview_Count`.

## feat: open buyable cosmetic tiles into the shop purchase sheet

**Problem:** Tapping a dimmed "next to buy" tile on a Profile cosmetic shelf dumped the user in the shop grid instead of the purchase sheet for that exact product. (This is the buyable-tap half of the now-sliced "buyable tap + richer preview" todo.)
**Approach:** Added an `onBuyableTap: (String) -> Unit` to `ProfileScreen`, threaded it to `BuyableCosmeticTile`, and wired the entry point to `router.batch { switchTab(ShopGraph); navigate(ShopProductSheetRoute(productId)) }` — the same cross-tab deep-link `EditProfileScreen.onNavigateToShop` already uses. The shelf header's "Shop ›" link still goes to the grid; only the tile deep-links. Buyable ids come from `catalog.chipOffers`, which the shop sheet resolves by id, so every buyable tile resolves.
**Reviewer notes:** Left the "richer felt/emote-pack preview" half in `docs/todo.md` — it needs a Studio visual pass. No new tests: this is pure navigation wiring through `Router.batch`, exercised by the existing routing.
**Deferred:** The richer-preview half stays as a (rewritten) `docs/todo.md` bullet — reviewer, no triage needed, just not in this commit.

---

## Cycle notes (not a commit) — substantial items I evaluated and deliberately deferred

Short cycle this run: two confident feature commits. I scoped several meatier items and judged each unsafe to ship autonomously. Flagging the reasoning so the human can decide, since most of these are the high-value work:

- **Banned/suspended enforcement (§A, P2).** The minimum slice puts a per-request ban check on the load-bearing JWT auth path (`Authentication.kt`). The real decision — per-request DB query vs. JWT claim vs. cached gate, and how to return a typed 403 from Ktor's `validate{}`/`challenge{}` (a `validate`→null only yields the generic 401 challenge; a typed 403 likely needs a thrown exception routed through `StatusPages`, whose propagation from `validate` I couldn't confirm) — is an architecture call with whole-app blast radius. Wants human-in-the-loop on the approach before coding.
- **Per-turn time limit (§B3, P1).** Needs a cancellable per-turn scheduler (dispatcher-injected) interacting with `GameSession`'s mutex + hydration, a new wire field, and a client countdown. A bug auto-folds a player who *did* act, in the load-bearing loop. Architecture of the scheduler (where it lives, how it survives reconnect) deserves a design pass; a partial slice is unsafe and the acceptance wants both enforcement + visible countdown.
- **Spectator role (§B4, P2).** "Friend rooms stay closed to non-members," but there's no public/friend-room distinction yet — every room is members-only via `/join`. Opening the WS to non-members would expose private rooms; gating it behind a default-closed flag makes the feature inert. Blocked on a room-visibility concept that doesn't exist.
- **detekt framework + `verifyStrings` (§C, P1).** The right structural item, but a custom-rule RuleSetProvider on a separate `detektPlugins` classpath + baseline + pre-push wiring is iterative build work; a half-built framework on `develop` blocks peers. Wants a focused session.
- **Social-graph P0s / B2 persisted membership / per-hand capture.** All carry schema migrations (friend_relations, rooms+room_members, a telemetry Room table) — the hard-to-undo category. Left for the human.

No `docs/todo.md` items were touched for these — they remain as-written.

---

## test: cover PlayPokerViewModel driving a multiplayer session

**Problem:** The MP testing plan's coverage snapshot flags the client-VM layer as having "No MP-session equivalent" — `PlayPokerViewModelIntegrationTest` exercises the VM against a real `LocalBotsSession`, but nothing drove it against a real `RemotePokerSessionFactory` + `RemotePokerSession`. That VM↔MP-session wiring is load-bearing and was untested end-to-end.
**Approach:** Added `PlayPokerViewModelMultiplayerIntegrationTest` (7 tests) that wires a real `RemotePokerSessionFactory` (over a `FakeRoomConnectionHandle`, the proven frame-pumping pattern from `RemotePokerSessionTest`/`RemotePokerSessionFactoryTest`) into a real `PlayPokerViewModel`. Pumps server `GameplayFrame`s + `RoomConnection` transitions and asserts they propagate through session → factory projection → VM state (Loading-before-snapshot, local-user human-seat derivation, acting-seat → isHumanTurn, observer/not-seated, connection-state mirroring) and that `Submit`/`RequestNextHand` leave the VM as the right `ClientFrame` on the wire. Chose the existing fake-handle seam over building a `FakeRoomServer` (the testing-plan's "fake quality" idea) because the latter would duplicate the server's hand-state machine into test code — high maintenance, low marginal coverage over pumping frames directly.
**Reviewer notes:** Verified via `:features:room:impl:testDebugUnitTest` (all 7 pass) and `compileTestKotlinIosSimulatorArm64` (native target compiles). No `docs/todo.md` bullet maps to this — it fills the gap named in `testing-plan.md`'s coverage snapshot, not a Round checkbox; left the doc's historical baseline table as-is.

## fix: rename native-illegal backtick test name in BetPresetsTest

**Problem:** `BetPresetsTest`'s `` `... (short stack)` `` test name contains `()`, which Kotlin/Native rejects ("Name contains illegal characters"). It silently broke the **entire** `:features:room:impl` `commonTest` compilation for the iOS/native target — the Android unit-test JVM target tolerates the name, so the breakage hid (present since 2026-05-31, commit `7ff3f8fe`).
**Approach:** Renamed the single offending function to `from returns empty when max is below min for a short stack` (no parens). Grepped the module's `commonTest` — this was the only native-illegal name, so the rename fully restores `compileTestKotlinIosSimulatorArm64`.
**Reviewer notes:** Confirmed before/after: the task failed on this name, passes after the rename (`--rerun-tasks`, 9s). Surfaced incidentally while validating the iOS compile of the test above. Worth checking whether CI actually runs the native test target — if it had, this would've been caught a month ago.
**Deferred:** A detekt/lint rule banning `()` (and other native-illegal chars) in backtick test names would mechanically prevent recurrence — fits the `verifyStrings`-style framework in `docs/todo.md §C`. Noted here only; nothing added to a doc.

---

## Cycle notes (not a commit) — later worker, same cycle

Honest short addition on top of the prior two feature commits. I scoped the remaining `docs/todo.md` + `testing-plan.md` items and most confident-and-substantive ones hit real skip-reasons for an autonomous headless run:

- **Testing-plan Round 2 (integration module) + Round 5 (chaos)** both live in the `:integration` module, which is parked in `developer-todo.md` (human-only) — can't build it.
- **Round 6 (Compose UI tests)** is greenfield harness setup: no `compose.ui.test`/robolectric infra exists in the catalog or anywhere in the repo. A half-built UI-test harness on `develop` blocks peers, and I can't validate rendering headlessly. Deferred to a focused session.
- **Testing-plan "fake quality" items** are stale: `FakeRoomConnectionHandle` already backs `connection` with `replay = 1`, and `FakeRoomSocketTransport` already models close→reopen (`primeGated` + `closeFromPeer`). Nothing to fix; left the checkboxes for the reviewer to reconcile.
- **MP achievement hand-count floor (§A)** is genuinely blocked: there is no server-side per-user hand count anywhere (`room_sessions` stores only the JSONB game state). A floor that trusts a client-reported count defeats the gate's own purpose — respected the blocked tag.
- **Studio-gated items** (landscape layouts, emote-glyph centering, cosmetic richer preview, hand-end particles) need a visual pass I can't do headlessly.
- **Architecture-call items already deferred by the prior worker** (ban enforcement on the auth path, per-turn timer in the game loop, spectator role) — same reasoning holds; left for human-in-the-loop.

So: one substantive test suite + one incidental native-compile fix. Stopped rather than reach for items I couldn't ship confidently.
