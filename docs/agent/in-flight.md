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
