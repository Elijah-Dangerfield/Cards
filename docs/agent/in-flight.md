# In-flight (2026-06-23 cycle)

## fix(server): hard-delete users on account deletion

**Problem:** `DELETE /v1/me` called Supabase admin delete-user with no body, so GoTrue soft-deleted by default — the `auth.users` row stayed, the credential kept authenticating, and the design's `ON DELETE CASCADE` never fired (a tester re-logged-in after "deleting"). CARDS-1T.
**Approach:** `HttpSupabaseAdminClient.deleteUser` now sends `{"should_soft_delete": false}` as a JSON body on the DELETE, forcing GoTrue to hard-delete the row + cascade. Added a body assertion to the success test (the existing tests only checked URL/headers).
**Reviewer notes:** None — body shape matches the GoTrue admin API. Can't end-to-end verify against a live Supabase from here; covered by the MockEngine body assertion.

## feat(rooms): gate private room join on wallet balance

**Problem:** `POST /v1/rooms/{code}/join` had no wallet-vs-buy-in check (unlike matchmaking's find path), so a tester joined a room whose buy-in exceeded their chips and was silently demoted to spectator by escrow. CARDS-1G.
**Approach:** Server gate added to the join route — fetch the room, and if the caller isn't already a member and `room.buyIn > balance`, reject `400 insufficient_balance` (mirrors `MatchmakingRoutes`; re-joins skip the check since they already sat down). Client maps the code to a new `JoinRoomOutcome.OverBalance(message)` → `LobbyError.JoinOverBalance`, surfacing the server's message inline like `CreateInvalidMaxSeats` does (no new string resource needed). Tests on both sides.
**Reviewer notes:** The over-balance message is rendered server-side and shown verbatim (server-supplied copy is the AGENTS.md exception to the resources rule, same as `CreateInvalidMaxSeats`). Unknown codes still fall through to join()'s 404 — the gate only fires when the room exists.

## fix(room): land on Home when leaving a private MP game

**Problem:** Leaving a private multiplayer game dropped the player back into the dead lobby instead of Home. CARDS-1Y.
**Approach:** A private game pushes `PlayMultiplayerRoute` on top of its `LobbyRoute`, so the old `goBack()` (pop one) + `switchTab(Home)` left the lobby underneath. The leave `onBack` now branches on `route.kind`: Private pops the whole chain with `popBackTo(LobbyRoute(), inclusive = true)` (lands on the Home tab root), Public keeps the existing `goBack + switchTab(Home)` since it has no lobby beneath it. Verified both Private entry paths (lobby start + Home active-room banner) route through a `LobbyRoute`, so the pop target always exists.
**Reviewer notes:** Entry-point routing lambda — no VM, so not unit-testable here (it's pure `Router` calls). The `OpponentsLeft` event still intentionally routes a private game to `LobbyRoute(prefilledCode)` (lone player can re-invite); only the explicit-leave path changed.

## chore: remove the redundant Neon table theme

**Problem:** The `table_neon` table-theme product is visually identical to a plain felt in V1 (both just recolor the felt); the owner asked to delete it (CARDS-18), mirroring the V64 Sunset table-theme removal.
**Approach:** Append-only `V70__remove_neon_table_theme.sql` deletes the product row (it was already unlock-only from V51, so no shop row to pull). Dropped the `table_neon` arm from `feltForProductId` and removed `EquippedFelt.Neon` entirely (no surviving felt mapped to it, unlike Sunset which keeps `felt_sunset_weekend`) — plus its `feltSurfaceColor` / `feltAccentSurface` arms and the screen preview. Tests that used `table_neon` as a generic felt/personal product id were repointed to surviving ids (`felt_charcoal`, or `table_classic` for the prefix-only cosmetic-category test); `EquippedFeltMappingsTest` now asserts both removed table themes fall back to Default.
**Reviewer notes:** Kept `CardBackStyle.Neon` / `cardback_neon` untouched — that's a card back, a different product from the table theme. `PostgresProductCatalogSourceTest` only needed a comment update (table.neon was already absent from the catalog as unlock-only); it's a DatabaseTest so it didn't run in the local pass.

## fix(profile): show play-more empty state instead of a fake play style

**Problem:** The profile stats banner fabricated a "Sharp & Steady" style label and a hardcoded "1,284 won" chips value before the user had enough hands to derive a real style, mis-selling fake data as theirs. CARDS-2A.
**Approach:** Lifted the Stats page's private `PlayStyleEmptyCard` into a shared `:libraries:ui` DS component (the todo asked for reuse) and pointed StatsScreen at it. The profile banner now drops the fabricated chips-won entirely and, below the `PlayStyleAxes.MIN_SAMPLE` gate, renders an honest "Play more hands to build your style" subtitle (the decorative radar mark already stood in for the shape); above the gate it shows the real derived style + real win rate. Removed the now-dead `profile_play_style_example` / `profile_stats_banner_subtitle` / `_no_games` strings, added `_style_win` + `_pending`.
**Reviewer notes:** Pure presentation — no VM logic, so covered by previews (added a `PlayStyleEmptyCardPreview`) rather than a unit test. Left the room player-card pending state (`room_player_profile_style_pending_body`) on its own `PublicHeroCard` shape — it's already honest and a different layout; not worth forcing onto the shared card this pass. Fixed the reused `stats_play_style_empty_blurb` string in passing — it shipped a literal `we\'ll` (renders the backslash in Compose-MP) plus an em dash, both AGENTS.md string violations; now plain `we will` + a hyphen.
