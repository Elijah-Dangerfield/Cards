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

## fix(room): reset folded hole-card offset on new deal, not on next turn (GAME-10)

**Problem:** After a silent swipe-fold, the hole cards were flung to `-foldFlightPx` (off the top of the screen) and left there; the only reset was keyed on `swipeFoldEnabled`, which requires `table.isHumanTurn`. So the freshly dealt cards of the *next* hand rendered stuck up top in a "ghost" placement until it was the human's turn again, then snapped down.
**Approach:** Added a second reset `LaunchedEffect(human.holeCards)` in `PlayerArea` that snaps `dragOffsetY` back to 0 on every new deal (the hole cards change identity each hand — the same key already used for `manuallyFacedown`). This decouples the reset from turn state, so the next hand always starts at rest. Left the existing gate-keyed reset in place (it still handles mid-turn spring-back).
**Reviewer notes:** No test — the bug is pure Compose animation state (`Animatable` offset + `LaunchedEffect`), and this module has no Compose UI test harness (`createComposeRule`), only logic/VM tests. Verified by reasoning about the reset condition + the swipe-fold flight path and an `:apps:compose:assembleDebug`. The fix is correct-by-construction: any residual offset from a prior hand is cleared the instant new cards deal.

## fix(room): gold seat ring now means "on the clock" only — drop the aggressor ring (GAME-9)

**Problem:** An opponent seat drew a gold ring for two different things — a pulsing "to act" ring AND a solid "aggressor" ring after a bet/raise/all-in that persisted through the street. Both gold + same thickness, so a bettor's lingering aggressor ring read as a turn indicator that never cleared (Sentry CARDS-6D). Still reproduced on current develop despite the PR-#84 rewrite.
**Approach:** Removed the aggressor ring entirely; the gold ring is now to-act-only (pulsing ring or timer countdown). The aggressor's "chips going in" is still carried unambiguously by their gold bet/raise/all-in action chip at the seat's bottom-center. Also dropped the now-dead `pulsing` param on `GoldSeatRing` and the unused `isAggressive()` helper.
**Approach (direction call):** Chose to *remove* the ring over *recoloring* it. `chipGold` and `seatActive` are both golds so a swap wouldn't disambiguate without inventing an aggressor color, and the action chip already conveys "chips in" — a second aggressor affordance is redundant noise. Decision + rejected alternatives logged in `decisions.md` (2026-06-30). If the reviewer wants aggressor emphasis back, it must not be gold.
**Reviewer notes:** No test — this module has no Compose UI test harness (logic/VM tests only); the change is a render-branch removal, covered by `OpponentSeatStatesPreview` (the "Raised" seat now shows only its gold action pill, no ring). Verified via `:apps:compose:assembleDebug`.

## Cycle deferrals (investigated, not shipped)

Four remaining todos were investigated this cycle and consciously left for the human / a later cycle — reviewer please triage:

- **SHOP-6 (cosmetic row start padding):** Could not reproduce on current develop. The profile bookshelf shelves (card backs, felts, emotes) all render through the *same* `EdgeToEdgeRow` under the same `SectionHeader`, and the shop uses a uniform 2-column `ProductGrid` with `screenContentPadding` — there is no card-back-vs-emote/felt padding difference in the code. Looks already resolved (shelves converged on `EdgeToEdgeRow`). Recommend closing or re-confirming against a fresh screenshot before spending on it.
- **GAME-11 (feedback behind bottom sheets):** Real bug, real architectural fix, but risky. `FeedbackRoute` is a full-screen `screen<>` inside `NavHost`; an open `ModalBottomSheet` (its own platform window) draws above it. The correct fixes (present feedback as a windowed dialog composed after the sheet, OR dismiss open floating windows on feedback-open) touch nav semantics + all 4 feedback entry points, and there's no Compose UI harness to guard the change. Left for a focused pass. P2, self-corrects when no sheet is open.
- **SHOP-8 (cosmetic sheet restyle + backend em-dash cleanup):** The "bubbly achievement-sheet" restyle is a subjective visual judgement I didn't want to ship blind; the em-dash cleanup lives in already-applied Supabase product-string migrations (`name_by_locale`/`description_by_locale`) — a content pass across many products that overlaps human-curated Supabase content, safest as a new dedicated migration by someone who can eyeball every locale string. Left whole rather than half-ship.
- **AUTH-12 (Google claim doesn't flip `isAnonymous`):** The base fix (`completeOAuthRedirect` Link path force-`refreshSession()`) is already in place and covered by `SupabaseAuthRepositoryImplTest.linkOAuthIdentity_redirect_refreshesSession_...`. The residual gap is either a screen caching pre-link `isAnonymous` (progression surfaces checked out fine — they subscribe to `observe()`) or the `cards://login-callback` redirect not routing back into `completeOAuthRedirect`. Pinning which requires a device repro / more digging than a confident test-first fix allowed this cycle.
