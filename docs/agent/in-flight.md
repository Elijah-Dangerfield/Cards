## refactor(ui): swap tutorial pill `RoundedCornerShape(999.dp)` for `Radii.Round.shape`

**Source:** worker-hydrated this cycle (not human-curated).
**Problem:** Four tutorial-screen callsites used `RoundedCornerShape(999.dp)` — the "infinite radius = pill" trick — while `Radii.Round = CornerSize(percent = 50)` already exists in `:libraries:ui` for exactly this. AGENTS.md DS rule §5 says corner radii come from `Radii` tokens.
**Approach:** Mechanical swap. `TutorialPokerScreen` lines 380/382/423 and `TutorialNarrationStep.kt:452` → `Radii.Round.shape`. `TutorialPokerScreen` keeps the `RoundedCornerShape` import for its remaining `RoundedCornerShape(2.dp)` callsite at line 314; `TutorialNarrationStep` drops the import. Visual: pill shape is unchanged (50% of height ≈ 999.dp on heights < 2000dp).
**Reviewer notes:** None. Tutorial previews are the visual safety net.

## refactor(ui): hoist LevelPill progression hues into `PokerPalette`

**Source:** worker-hydrated this cycle (not human-curated).
**Problem:** `LevelPill.kt` hardcoded `Color(0xFF4FC3F7)` (twice — gradient start + outer ring) and `Color(0xFF66BB6A)` (gradient end). The block comment at lines 211-215 explicitly flagged the next step: "a `PokerPalette` entry for 'progression cyan' would let both lift off the literal." AGENTS.md DS rule §1/§4 — raw `Color(0xFF…)` outside `:libraries:ui/system/color/` is the anti-pattern. `LevelProgressGradient.kt` shared the same two literals.
**Approach:** Added `PokerPalette.ProgressionCyan` + `PokerPalette.ProgressionGreen` next to existing brand swatches (chip-gold, card-back-blue), routed `LevelPill`'s gradient + `RING_HUE` and `LevelProgressGradient` through them, dropped the apologetic block comment. Two callsites lifted off the literal in one sweep.
**Reviewer notes:** Pixel-identical — both tokens are the exact same `Color(0xFF…)` values that were inline. Preview pins on `LevelPill` are the visual safety net.

## feat(gameplay): add v1 GameEvent envelope for future persistence

**Problem:** Once `game_events.event_jsonb` rows start landing (B0 first bullet), every row is forever — a future v2 reader must fork on a stable version discriminator without rewriting history. AGENTS.md / docs/decisions.md (2026-05-27 MP architecture revisit) call this out as the first prereq before any event-log persistence ships.
**Approach:** Added `GameEventEnvelope(v, type, payload)` + `toEnvelope()` / `toGameEvent()` helpers next to `GameEvent` in `:libraries:gameplay`, with `GameEventEnvelopeJson` (`classDiscriminator = "type"`, `encodeDefaults = true`). The envelope lifts kotlinx's polymorphic discriminator out of the payload into the outer `type` field — payload stays clean, version-aware reader dispatches on the envelope. Also baked stable `@SerialName` values on every `GameEvent` and `PlayerAction` subclass so the on-disk discriminator survives any future class rename. The WS wire format keeps using the bare kotlinx polymorphic JSON (discriminator value changes from the FQ class name to the new short `@SerialName`, no other shape change). Rejected: building a parallel envelope-only discriminator registry — keeping it in sync with kotlinx is a footgun, and lifting the existing kotlinx discriminator costs nothing.
**Reviewer notes:** Wire compat: the WS discriminator value changes from `"com.dangerfield.cards.libraries.gameplay.GameEvent.HandStarted"` → `"HandStarted"`. Pre-launch, no clients in the wild. New `GameEventEnvelopeTest` pins the v1 layout + round-trip for every variant; existing `RoomSocketRoutesTest` continues to pass (it deserialises by type, doesn't pin the discriminator string).
