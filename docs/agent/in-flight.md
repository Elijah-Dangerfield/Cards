# In-flight log

## docs(wiki): describe as-built bot behavior, drop unshipped claims (ENG-19)

**Problem:** `docs/wiki/bots.md` presented unbuilt "V1 countermeasures" as shipped: a provably-fair SHA-256 deck commit (no implementation anywhere), showdown equity transparency and bot-thought hand-history replay (`BotThought` never leaves think-delay pacing), and "Casual bots make only pot-odds-positive calls" (the curiosity-call branch calls without pot odds at every tier).
**Approach:** Restructured the page into "What's built" (tiers, difficulty-gated opponent modeling/tactics, casual no-bluff, the curiosity-call caveat, BotThought's actual usage) and a "Not implemented" section that keeps the three aspirational surfaces visible as candidate follow-ups rather than deleting the ideas. Also fixed `docs/wiki/client-patterns.md`: the shop deep-link call is `bus.requestScrollTo(ShopCategory.Avatars)` and the bus lives in the `:features:shop` api, not `:libraries:cards`.
**Reviewer notes:** All claims verified against `BotDecision.kt`, `BotPersonality.forDifficulty`, `ServerBotDriver.thinkDelay`, and a repo-wide grep for SHA-256/deck-commit code. "Not implemented" framing (vs. outright deletion) was a judgement call — the fairness roadmap context seemed worth keeping.

## style(ui): route Banner's icon well through Radii.Callout (ENG-20)

**Problem:** `Banner.kt` clipped the leading icon well with a literal `RoundedCornerShape(14.dp)`, the last literal-corner callsite in non-preview `:libraries:ui` component code.
**Approach:** Swapped to `Radii.Callout.shape` (R600 = 14dp, so rendering is identical) rather than minting a new `IconWell` token — one callsite doesn't justify a new semantic alias, and Callout already reads right for a small inner tile.
**Reviewer notes:** `BaseBottomSheet.kt` still holds `RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)` as a named private constant — top-corners-only geometry the all-corner `Radius` token can't express, so I left it (the todo's problem statement also treated Banner as the last offender). Flagging in case you want a partial-corner token instead.

## docs(server): describe Room persistence as built in the Room KDoc (MP-32)

**Problem:** `Room.kt`'s class KDoc still claimed V1 rooms live in memory only with "no Postgres backing yet" — stale since migration V65 added `rooms` + `room_members` behind `PostgresRoomStore`.
**Approach:** Rewrote the paragraph to describe the as-built model: in-memory registry as live authority, write-through Postgres snapshot on every mutation, hydrate-on-miss for restart survival, orphan reap via the sweep. Dropped the "why not persist now" paragraph entirely since its premise no longer holds; kept the 2026-05-13 server-authoritative boundary note.
**Reviewer notes:** Comment-only change; server compiles.

## test(profile): add preview coverage to the cosmetic detail sheets (SHOP-10)

**Problem:** `CosmeticDetailSheet.kt` had zero `@Preview`s despite several distinct layouts (founding-member ceremony, emote pack, avatar pack, earned item, equip CTA), against the AGENTS.md preview rule.
**Approach:** Added six `CosmeticDetailSheet` previews via `PreviewContent` (bought card back with Equip CTA, equipped felt showing the disabled swap-only "Equipped" state, emote pack with Try-emote CTA, avatar pack with the edit-profile hint, earned card back with the "Earned N ago" line, founding-member ceremony) plus one for `LockedCosmeticSheet` in the same file, with a `previewOwnedItem` factory using real catalog product ids so the slot-driven hero branches (flip card, felt vignette, pack stack) render for real.
**Reviewer notes:** Preview acquisition timestamps derive from `Clock.System.now()` minus a fixed offset, matching how the sheet itself computes the "ago" label; previews render once so the live clock is harmless.
