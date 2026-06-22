# In-flight

Handoff log for this cycle. Reviewer reads it when writing the PR, then deletes it.

> The first two blocks (`feat(server): emit room_code …`, `feat(feedback): 500-char cap …`) were committed by an earlier interrupted worker run this cycle but never pushed and never logged — I pushed them at the start of this run and reconstructed their blocks from the commit messages so they're documented.

## feat(server): emit room_code as Loki structured metadata on room routes

**Problem:** MP triage during the 2026-06-22 feedback batch had to brittle-line-grep Loki because room-route logs carried no room code; a long-lived socket coroutine's `session_id` is just whoever currently holds it (a bot has none), so it can't pivot a whole room.
**Approach:** Parse the room code from the request path on room routes, uppercase it to match the handlers' normalized code + the span `room.code` attribute, and seed it into the CallLogging MDC. The OTel logback appender already forwards all MDC keys (`captureMdcAttributes=*`), so it lands as Loki structured metadata with no logback change. Chose structured metadata over a stream label because a room code is unbounded high-cardinality and a Loki label would explode the index — `{service_name="cards-server"} | room_code="<CODE>"` stays cheap. Also corrected the feedback-triage skill's Loki guidance (these correlation fields are matched with the label-matcher pipe `| session_id=`, not a line filter `|=` which silently matches nothing).
**Reviewer notes:** Pre-existing commit reconstructed from its message — I did not re-verify it at runtime this cycle. The MDC-key forwarding assumption (`captureMdcAttributes=*`) is the load-bearing part; worth a glance.

## feat(feedback): 500-char cap in release, uncapped in debug

**Problem:** The in-app feedback box capped messages at 200 chars — too tight for the owner to paste behavior notes / repro steps (feedback CARDS-8).
**Approach:** Raised the release cap from 200 to 500; debug builds are uncapped (and the char counter is hidden) so the owner can paste long notes while testing. Cap is a client constant in `FeedbackScreen.kt`.
**Reviewer notes:** Pre-existing commit reconstructed from its message. The CARDS-8 todo bullet was still in `docs/todo.md` after this shipped — I removed it this cycle (see the `docs(todo)` cleanup below).

## fix(home): white close button on the "New here" card

**Problem:** The tutorial banner's dismiss ✕ used a dark fill (`background`/`content`), which the owner read as a low-contrast "black button" and wanted a white close affordance on the right (feedback CARDS-A).
**Approach:** Swapped the `CircleIcon` fill to the DS `surfaceInverse` / `onSurfaceInverse` tokens (near-white circle, dark ink) instead of hand-tuning a color. It already sits top-right via `BadgePlacement.EdgeAlignedTop`, so only the color needed to change — the "on the left" in the report was a contrast-perception read of the dark sticker on the green gradient, not an actual left placement.
**Reviewer notes:** None.

## fix(profile): drop the persistent equipped ring, keep the badge

**Problem:** Equipped cosmetic tiles in My Items carried a persistent gold accent ring around them; the owner found it heavy-handed and wanted the corner "equipped" badge to stand alone (feedback CARDS-G).
**Approach:** In `OwnedCosmeticTile`, dropped the equipped contribution to the border alpha so the ring now only ever draws from the transient just-acquired `pulseAlpha` (kept — that's a different, momentary spotlight, not the persistent equipped state). The `EquippedBadge` corner check is untouched. Renamed the now-badge-only flag `showEquippedRing` → `showEquippedBadge` to match.
**Reviewer notes:** Kept the buy-pulse border on purpose — it's a 600ms fade on a just-purchased tile, unrelated to the equipped-state ring the owner objected to. If the owner wants that gone too, it's a one-line follow-up.

## fix(home): hide the "Recently played with" shelf when empty

**Problem:** The recents shelf rendered its header + a friend-via-play empty state even with zero opponents; the owner wanted it gone entirely when empty (feedback CARDS-E).
**Approach:** Early-return from `RecentlyPlayedWithStrip` on an empty list (matching `RecentAchievementsStrip`), and deleted the now-unreachable empty-state path — the `EmptyRecentOpponents` / `SuggestPill` composables, the two `onStart*` callbacks they used, the empty `@Preview`, and the three orphaned `home_recents_*` strings.
**Reviewer notes:** This removes the only in-app explanation of the "you can only friend people you've played with" rule (it lived in that empty state). The friend-graph epic in `docs/todo.md` still calls for that copy on the Profile social section — if the owner wants it on Home too, it'd come back as a separate banner, not this shelf's empty state.

## feat(room): bots-only MP explainer notes real chips aren't at stake

**Problem:** When an MP table is only against bots, the practice-tier explainer didn't say real chips were off the table; the owner wanted that spelled out (feedback CARDS-14).
**Approach:** Added `MultiplayerCredit.isBotsOnly(state)` — the local human is the only human seated (`humans <= 1 && bots >= 1`) — distinct from the existing `showsPracticeTierLabel` which also covers bot-stacked tables that still have human opponents (`2H + 4B`). Threaded a `practiceTierBotsOnly` flag through `TableUiState.Active` / `fromGameState` / `RemotePokerSessionFactory` (mirroring `practiceTierBotsPresent`) and appended a second body line in `PracticeTierExplainer` when it's set.
**Reviewer notes:** Bot-only is gated to `humans <= 1`, so a 1H+5B solo-style MP table shows it but a 2H+4B friend-with-bots table does not — matching "no human's chips on the other side." Untested at the VM layer (no existing test covers `practiceTier*` plumbing); the new `isBotsOnly` is pure and could take a small `MultiplayerCreditTest` if one is wanted.

## fix(room): back out of an in-progress MP game routes Home

**Problem:** Swiping back from a multiplayer game returned to the lobby the player came through; the owner wanted it to go Home (feedback CARDS-12).
**Approach:** `onBack` now runs `router.batch { goBack(); switchTab(HomeRoute()) }`. Chose `goBack()` + `switchTab` over a bare `switchTab(HomeRoute())`: `goBack()` pops the MP screen so its VM/socket tears down (server frees the seat after grace), where `switchTab` alone would keep the dead game alive on the saved tab stack and leave the player "present" at a table they left. The `batch` makes the pair atomic so a mid-teardown scope death can't strand them. Adds a `:features:home` dep on `:features:room:impl` for `HomeRoute` (api-only, no cycle).
**Reviewer notes:** The bots (solo) entry point still uses a plain `goBack()` — the owner's report was MP-specific, so I left solo alone. If back-to-Home is wanted there too it's the same one-liner. Verified the new module dep + nav compiles via `assembleDebug`; the actual back-from-MP behavior wants a device check (no automated nav test covers it).

## fix(shop): remove the redundant Sunset table-theme product

**Problem:** The owner noted a "table theme" and a "felt" look identical in V1 (both just recolor the felt), making the premium Sunset *table theme* redundant; he asked to remove it (feedback CARDS-18).
**Approach:** Read it as: remove the `table_sunset` table-theme product (8000 chips, level 8) and KEEP `felt_sunset_weekend` (the cheap Sunset felt that earns the same on-table look) — rather than deleting "sunset" wholesale. So the `EquippedFelt.Sunset` enum + colors stay (the felt still uses them); only the legacy `table_sunset` product + its id mapping go. Server: append-only migration `V64` DELETEs the product (no products FK, deletes clean) instead of editing the V5 seed; client: drop the `table_sunset` alias from `feltForProductId`; updated the felt-mapping test to pin the removal (now falls to Default), repointed the catalog preview to the felt id, and dropped the now-phantom id from the catalog + category tests.
**Reviewer notes:** Left `table_neon` (the other "table theme") alone — same redundancy, but the owner only called out sunset and I didn't want to over-reach. Flagging it as a likely follow-up. Verified the V64 migration applies and the catalog grant-key test passes against real Postgres (`:apps:server:test --tests *PostgresProductCatalogSource*`, 18/18).
**Deferred:** `table_neon` is redundant with felts for the same reason — reviewer please triage whether to remove it too (nothing filed yet).
