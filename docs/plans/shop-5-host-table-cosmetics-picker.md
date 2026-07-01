# Host picks the table felt + card back from a scrollable list

## Context

Today a multiplayer room already carries a host-chosen `feltProductId` + `cardBackProductId`
(shipped as **SHOP-3**, `docs/decisions.md` 2026-06-27). But the host never *picks* — the
room silently inherits whatever felt + card back the host happened to have **equipped in My
Items** at create time. There's no in-flow choice, and the create screen can't show what
options the host has.

This change adds an explicit picker to the create-room screen: two horizontally-scrollable
rows (Felt, Card back) showing **only cosmetics the host owns**, each with a live mini
preview, defaulting to the host's currently-equipped look. The host's selection is pinned on
the room exactly as today; everyone at the table sees it. The wire format and the render path
are unchanged — we're only replacing "auto-read equipped" with "host explicitly chooses,
pre-seeded to equipped."

Out of scope (orthogonal, noted under Error handling): server-side ownership validation and
forward-compatible rendering for cosmetics newer than a viewer's client. The picker is
owned-only by construction, and the version-mismatch behavior is unchanged from today.

## Current state (for reference)

- Create flow hops screens: [`PrivateCreateScreen`](features/lobby/impl/src/commonMain/kotlin/com/cards/features/lobby/impl/PrivateCreateScreen.kt)
  (stateless form: buy-in slider, max-players stepper, open toggle) → `onCreate(maxPlayers, buyIn, open)`
  → navigates to [`LobbyRoute(autoCreate=true, …)`](features/lobby/src/commonMain/kotlin/com/cards/features/lobby/LobbyRoute.kt)
  → [`LobbyViewModel`](features/lobby/impl/src/commonMain/kotlin/com/cards/features/lobby/impl/LobbyViewModel.kt)
  `init` fires `LobbyAction.CreateRoom`.
- In `CreateRoom` (LobbyViewModel ~133), it reads `equippedTableCosmetics(equipment.observeEquipped().first())`
  ([Equipment.kt:118](libraries/cards/src/commonMain/kotlin/com/cards/libraries/cards/Equipment.kt))
  and passes `feltProductId` / `cardBackProductId` into `rooms.createRoom(…)`.
- The create screen has **no** repository access — the entry point only feeds it `chipBalance`.
- Reusable building blocks exist: [`EdgeToEdgeRow`](libraries/ui/src/commonMain/kotlin/com/cards/libraries/ui/components/EdgeToEdgeRow.kt)
  (the canonical horizontal shelf), [`CosmeticPreview`](libraries/ui/src/commonMain/kotlin/com/cards/libraries/ui/components/poker/CosmeticPreview.kt)
  (renders a felt swatch or mini card-back from a `productId`), and
  `cosmeticSlotFor(productId)` ([CosmeticCategory.kt](libraries/cards/src/commonMain/kotlin/com/cards/libraries/cards/CosmeticCategory.kt))
  to classify owned items into the Felt / CardBack slots.

## Plan

### 1. Feed owned cosmetics into the create screen — entry point
`LobbyFeatureEntryPoint.kt`

- Inject `InventoryRepository` + `ProductsRepository` + `EquipmentRepository` (constructor +
  the existing kotlin-inject wiring — same pattern as `chipsRepository`).
- In the `screen<PrivateCreateRoute>` block, `combine` inventory + catalog + equipped into a
  small UI list: filter owned `productId`s by `cosmeticSlotFor(id) == Felt` and `== CardBack`,
  join catalog for a display label/emoji, and resolve the host's equipped felt/card back via
  `equippedTableCosmetics(...)` for the initial selection. The default cosmetics
  (`felt_default`, `cardback_default`) are in starter inventory, so they appear automatically
  and guarantee each shelf is non-empty.
- Pass `felts: List<CosmeticChoice>`, `cardBacks: List<CosmeticChoice>`, `initialFeltProductId`,
  `initialCardBackProductId` into `PrivateCreateScreen`. Define a tiny
  `CosmeticChoice(productId: String, label: String, emoji: String)` UI model local to the lobby
  feature (the shared `OwnedItem` mapping lives in `:features:profile` and isn't reusable here).
- Extend `onCreate` to `(maxPlayers, buyIn, open, feltProductId, cardBackProductId)` and thread
  the two ids into the `LobbyRoute(...)` navigation.

### 2. The picker UI — `PrivateCreateScreen.kt`
- Add two local `remember { mutableStateOf(initial…) }` selections, seeded from the
  `initial…ProductId` params.
- Add two rows to the existing Rules card (after the "Open to anyone" row, each behind a
  `RuleDivider`): a **Felt** row and a **Card back** row. Each renders an `EdgeToEdgeRow` of
  selectable tiles — one tile per `CosmeticChoice`, each wrapping `CosmeticPreview(productId=…)`
  in a bordered container with a selection ring (mirror the selected/border styling from
  `OwnedCosmeticTile` in `ProfileScreen.kt`; a static selected border, not the acquire pulse).
- Tapping a tile updates the local selection. Pass both selections through the updated
  `onCreate(...)` call from the "Create room" button.
- Keep it owned-only: the lists are owned items, so the host can't select anything they don't
  own — this is the "from items they own only" guarantee, enforced at the source.

### 3. Carry the selection through the route
`LobbyRoute.kt` — add `feltProductId: String? = null`, `cardBackProductId: String? = null`
(nullable, defaulted; `data class` already, so no iOS route-crash concern). Update the
`viewModelFactory` signature in `LobbyFeatureEntryPoint` + the `viewModel { … }` call to forward
`route.feltProductId` / `route.cardBackProductId`.

### 4. Use the explicit selection in `LobbyViewModel`
- Add the two ids to the `LobbyViewModel` constructor (via the factory) and the `init` plumbing.
- In the `CreateRoom` handler: if a route id is non-null, use it; otherwise keep the current
  `equippedTableCosmetics(...)` fallback. This preserves every other create path that doesn't
  go through the picker (e.g. any future quick-create) while letting the picker win when present.
- Note: an explicit pick of `felt_default` / `cardback_default` now correctly forces the plain
  default felt/back for the whole table — more expressive than today's equipped-only model,
  where a null slot meant "each player keeps their own." That's the intended behavior of an
  explicit picker.

## Error handling & edge cases

- **Owned-only:** enforced by construction — the picker only lists owned items. (Optional future
  hardening: validate `feltProductId`/`cardBackProductId` ownership server-side in
  `RoomRoutes`/`RoomService.create`; low priority since chips are freemium and a spoofed cosmetic
  is pure vanity, not money. Flag as a follow-up, don't build here.)
- **Empty shelves:** can't happen — `felt_default` + `cardback_default` are starter inventory, so
  each row always has at least the Default tile.
- **Read failure loading owned cosmetics:** wrap the entry-point `combine` in `Catching {}` (repo
  convention) and fall back to just the defaults + the host's equipped ids, so a catalog/inventory
  hiccup degrades the picker to "Default only" rather than blocking room creation.
- **Version mismatch (host picks a felt a joiner's older client doesn't know):** unchanged by this
  change. `feltForProductId` / `cardBackForProductId` still `else -> Default`, so an unknown id
  renders the plain default felt/back on that client (no crash, but the table looks different
  across client versions). The forward-compatible fix (room carries render data, not just the id)
  is a separate effort — call out as a known limitation, don't address here.

## Critical files

| File | Change |
|------|--------|
| `features/lobby/impl/.../LobbyFeatureEntryPoint.kt` | Inject inventory/products/equipment repos; build owned felt + card-back lists + initial selection; extend `onCreate`; forward ids to `LobbyRoute` + factory |
| `features/lobby/impl/.../PrivateCreateScreen.kt` | Two new picker rows (`EdgeToEdgeRow` + selectable `CosmeticPreview` tiles); local selection state; updated `onCreate` |
| `features/lobby/src/.../LobbyRoute.kt` | Add nullable `feltProductId` + `cardBackProductId` |
| `features/lobby/impl/.../LobbyViewModel.kt` | Accept selected ids via factory; in `CreateRoom`, prefer them over the equipped fallback |
| (reuse, no change) | `EdgeToEdgeRow`, `CosmeticPreview`, `cosmeticSlotFor`, `equippedTableCosmetics` |

## Verification

- **Unit:** extend `LobbyViewModel` tests to assert `CreateRoom` passes the route-provided ids to
  `rooms.createRoom(...)` and falls back to equipped when null.
- **Manual (run app):** create a room with felt/card-back B selected while equipped look is A →
  confirm the room (and a second joiner) renders B. Pick the Default tiles → confirm the table
  renders the plain default for both players. Confirm the picker only lists owned items.
- **QA regression:** existing **MP-14** ("host's felt + card back show on every player's table")
  still passes; update it to note the look is now an explicit pick rather than the equipped read.
