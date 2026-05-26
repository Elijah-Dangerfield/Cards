## refactor(ui): generalize dialog + sheet top accessory to TopAccessory

**Problem:** `Dialog(emoji = DialogEmoji)` and `BottomSheetDragHandle.Emoji(...)` baked in an emoji-shaped affordance. Future surfaces need icons, custom tiles, etc., but every callsite was constrained to a Text(emoji) bubble.
**Approach:** Introduced `TopAccessory` sealed type (Emoji / Icon / Custom) in `:libraries:ui`. Renamed `EmojiBubble` → `TopAccessoryBubble` (renderer switches on the variant). Renamed `EmojiHandleStyle` → `AccessoryShape`. Replaced `BottomSheetDragHandle.Emoji` with `BottomSheetDragHandle.Accessory(TopAccessory)` + an `asDragHandle()` extension for ergonomics. Replaced `Dialog`'s `emoji: DialogEmoji?` with `topAccessory: TopAccessory?`. Migrated factories (`dialogEmoji` → `topAccessoryEmoji`, `dialogChipBubble` → `topAccessoryChipBubble`, `bottomSheetEmojiHandle` collapsed into `topAccessoryEmoji(...).asDragHandle()`) and all 12 callsites in apps/compose, features/home, features/room, features/shop, features/profile.
**Reviewer notes:** Image variant from the spec is intentionally not modelled — `TopAccessory.Custom { render }` covers it (a future image-specific variant can land if a second caller materialises). Existing visual behaviour is byte-for-byte unchanged for every migrated callsite; the rename is the substance. Snackbar's private `SnackbarEmojiBubble` is unrelated and untouched.

## refactor(ui): hoist EquippedFelt resolvers to :libraries:ui

**Problem:** `EquippedFelt` + the felt/card-back/title productId resolvers + `LocalFeltAccentSurface` lived in `:features:room:impl`, so any other feature wanting to render an equipped felt or card back couldn't reach them (impls can't depend on impls).
**Approach:** Plain move from `features/room/impl/EquippedFelt.kt` → `libraries/ui/components/poker/EquippedFelt.kt`. Same code, new package. Added imports in the six room-impl files that referenced it. Zero behaviour change.
**Reviewer notes:** Sets up the My Items cosmetic-preview commit that follows. If you'd rather the resolvers sat in a smaller dedicated module (e.g. `:libraries:cosmetics`) than `:libraries:ui`, that's a fine follow-up — the move is shallow enough that re-homing it is mechanical.

## feat(profile): render felt + card-back previews in My Items

**Problem:** My Items showed every owned cosmetic as a 48dp emoji circle — felts, card backs, emote packs all rendered identically (`⬛`, `🃏`, `💃`). No visual cue that a card back is a card back.
**Approach:** New `CosmeticPreview(productId, emoji, size)` primitive in `:libraries:ui:components:poker` that classifies the productId (`felt_*` / `table_*` → felt swatch; `cardback_*` → `PlayingCardBack`; otherwise → emoji tile) and renders the appropriate visual. Swapped `MyItemsScreen`'s inline 48dp emoji circle for one call. Felts paint the real `feltSurfaceColor`; card backs use the real `PlayingCardBack` at a card-shaped 0.7× aspect ratio inside the swatch tile.
**Reviewer notes:** Built on top of the EquippedFelt move; pairs cleanly with that commit. Same primitive is the natural fit for the shop's `ProductIcon` tile and the `PurchaseConfirmSheet` body — left as a todo entry because the size + placement on those bigger surfaces want a design judgment, not a worker call.
**Deferred:** Shop integration (grid tile + purchase sheet) — left as a `docs/todo.md` entry pointing at the new `CosmeticPreview` primitive.
