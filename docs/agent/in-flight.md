# In-flight (worker handoff log)

Each block below is one pushed commit this cycle. The reviewer reads these when writing the PR, then deletes this file.

## fix(server): lower card back + felt prices

**Problem:** Card backs (4,000-15,000) and felts (1,500-4,000) were premium-priced, but they're self-only cosmetics nobody else sees — too steep for the value.
**Approach:** Append-only migration `V63__lower_cosmetic_prices.sql` drops card backs to ~500-1,500 and felts to ~250-750, preserving the relative tiering (marble cheapest, holographic/diamond top; royal_red/midnight_blue/pine_green cheapest felts, sunset top). Targets matched the todo's rough guidance. Updated the one test that pinned a hardcoded price (`PostgresProductCatalogSourceTest` felt_royal_red 1500 → 250).
**Reviewer notes:** Prices are a judgement call within the todo's stated range; I kept ~8× compression so tiering stays legible. Verified via the testcontainer-backed `PostgresProductCatalogSourceTest` (Flyway applies V63, 18 tests pass). Shop catalog reflects on next deploy after the 5-min cache rolls.

## feat(rooms): source MatchmakingRadar reduce-motion from the platform

**Problem:** `PublicSearchingScreen` hardcoded `MatchmakingRadar(reduceMotion = false)`; the accessibility setting was never read, so the looping radar always animated even for users who asked the OS to minimize motion.
**Approach:** Added a `@Composable expect fun isReduceMotionEnabled()` in `:libraries:ui` (mirrors the existing `CameraPreview` expect/actual shape) with three actuals — Android reads `Settings.Global.TRANSITION_ANIMATION_SCALE == 0` (the platform-standard probe; there's no dedicated flag), iOS reads `UIAccessibilityIsReduceMotionEnabled()`, JVM returns `false` (no desktop signal, preview-only). Wired the screen to call it. Chose the global-transition-scale probe over an `AccessibilityManager`-based listener because the radar only needs a one-shot read at composition, not a reactive stream.
**Reviewer notes:** Android `TRANSITION_ANIMATION_SCALE` is read once at composition, so a mid-screen toggle won't update until recomposition — acceptable for a transient searching screen. Verified `:apps:compose:assembleDebug` + `compileKotlinIosSimulatorArm64` both build. New primitive is a candidate for reuse by other looping DS animations (level-up confetti, etc.).

## feat(room): explain practice-tier label via tappable + auto-shown dialog

**Problem:** The "Practice tier · bots present" pill was a silent, non-clickable downgrade; players didn't know a bot-stacked MP table halves their XP and locks multiplayer-only achievements.
**Approach:** Made `PracticeTierLabel` clickable (with an a11y click-label) opening a new `PracticeTierExplainer` dialog (mirrors `PotExplainer` — standard `Dialog` + 🤖 emoji bubble), and auto-open it once when the player first lands on a bot-stacked table. Auto-show is gated by a remembered `practiceTierExplainerAutoShown` flag flipped in a `LaunchedEffect`, so it fires once per screen/game session and never re-pops per hand. Copy says XP is halved and MP achievements stay locked — deliberately did NOT claim "no chips earned" since MP chip settlement (B3) isn't live yet.
**Reviewer notes:** Auto-show keying lives in the composable (remembered flag), not the VM — a new game = new screen = re-shows, which matches "once per game." Couldn't unit-test the auto-show without Compose UI tests (Round 6, unstarted); the dialog has a `@Preview`. If you'd rather key strictly on room code, the flag would need to move into `PlayPokerState`, but the per-screen-instance remember is simpler and behaves correctly for the bot-table flow.
