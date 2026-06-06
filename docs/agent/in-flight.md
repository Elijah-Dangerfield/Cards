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
