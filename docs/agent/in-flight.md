# In-flight (this cycle)

## feat(profile): shrink profile achievement medals to Home size (PROG-7)

**Problem:** Profile-screen achievement medals render larger than Home's recent-achievements strip (they filled their grid column), so the two surfaces read inconsistently.
**Approach:** The profile `AchievementsSection` now pins each medal to `MedalSize.Small` (88dp — the same preset Home's `RecentAchievementsStrip` uses) instead of `fillMaxWidth()`; the medal stays centered in its 3-up column. One shared size token drives both surfaces, so they can't drift.
**Reviewer notes:** On a very narrow phone the fixed 88dp is slightly smaller than the old fill-column size; that's the intended "smaller, matches Home" direction. No test — pure sizing tweak; the previews (`ProfileScreenPreview*`) cover the render.

## fix(profile): suppress acquisition line on default cosmetics (SHOP-4)

**Problem:** The default felt + card back render an "Earned"/"Bought free … ago" line in their detail sheet, even though they're granted at account creation, not earned — a fresh account's seeded `acquiredAtEpochMs` resolves to "today".
**Approach:** Extracted the "which acquisition line, if any" decision out of the Composable into a pure `acquisitionLineKind(item)` (in `items/AcquisitionLine.kt`) so it has a real regression guard, returning null for `isDefaultCosmetic(...)` ids (and rows with no acquisition timestamp). `CosmeticDetailSheet.acquisitionLine` renders off the pure kind. Test-first: `AcquisitionLineTest` asserts default felt/card back → null, earned → Earned, bought → Bought(cost), free grant → BoughtFree.
**Reviewer notes:** The "earned badge" the todo named is really this detail-sheet line — there's no separate corner "earned" badge on the tile (corner badges are equipped/locked only). Pairs with the backlog "Earn-source attribution on My Items 'Earned' rows."

## fix(shop): shrink specialty offer icons for a congruent grid (SHOP-7)

**Problem:** The specialty (cosmetic) offer cards in the shop used a 64dp product icon, making the two-column grid read icon-heavy vs. the rest of the screen.
**Approach:** Introduced a `SpecialtyIconSize` (56dp) used by both the `CosmeticPreview` and `ProductIcon` branches of `ChipOfferCard`, so the specialty tiles read a touch smaller and more congruent. Chip-pack tiles keep their 64dp icon.
**Reviewer notes:** Direction call — 56dp over a larger reduction so the swatch/preview stays legible. No test; sizing-only, covered by the shop previews.

## feat(lobby): host picks table felt + card back on create-room (SHOP-5)

**Problem:** The host never *chose* the table look — the room silently inherited whatever felt + card back they had equipped in My Items at create time (SHOP-3). No in-flow choice, no "from items I own" surface.
**Approach:** Added two horizontally-scrollable picker rows (Table felt, Card back) to the create-room Rules card, each an `EdgeToEdgeRow` of `CosmeticPreview` tiles listing **only the host's owned** felt/card-back cosmetics (built from `inventory ∩ catalog` in the entry point), pre-selected to their equipped look. The pick threads through `LobbyRoute` (+nullable `feltProductId`/`cardBackProductId`) into `LobbyViewModel.CreateRoom`, which prefers the picked id per-slot and falls back to `equippedTableCosmetics(...)` only when a slot is unpicked. Wire format + render path unchanged from SHOP-3. Owned-only by construction. An explicit Default pick now forces the plain default table-wide. Followed the written plan at `docs/plans/shop-5-host-table-cosmetics-picker.md` (deleted on ship). Decision logged in `decisions.md`.
**Reviewer notes:** Multi-file but every seam reuses existing plumbing. The assisted `LobbyViewModel` factory grew two params — verified the whole DI graph via `:apps:compose:assembleDebug`. Tests: `LobbyViewModelTest` now asserts picked ids win over equipped, and a partial pick falls back per-slot. MP-14 QA updated to the explicit-pick shape.
**Deferred:** Server-side ownership validation of the picked ids and forward-compatible cross-version rendering (unknown id → default felt, no crash) are called out as known limitations in the decision entry — reviewer please triage whether either wants a backlog item (freemium cosmetics, low stakes).

## fix(room): make seat emote + win-ratio badges read as card cutouts (GAME-12)

**Problem:** The human seat's emote badge and win-ratio (odds-flip) button filled with `surfaceRaised`, so they read as raised chips floating on the player-area card rather than cutouts of it, and hung far out into the felt where they crowded the active-turn ring.
**Approach:** Both badges now fill with `AppTheme.colors.surface.color` (the player-area card surface) so the cutout reveals the card, keeping the felt-toned ring only as a hairline separator from the tile border/turn ring. Shrank the emote trigger (Medium→Small icon button, 3dp→2dp cutout ring) and the flip affordance (24dp→22dp, 3dp→2dp ring), and pulled both offsets inward (emote 16/8→6/2, flip 8/-8→6/-2) so they inset into the corner instead of hanging into the felt.
**Reviewer notes:** The human player area uses a pulsing border, not a gold seat ring (that's opponents), so "space the gold ring" reduces to keeping the cutout ring clear of the tile border — the inward inset does that. Pure styling; covered by the `PlayerArea`/`SeatEmoteBadge` previews, no test.
