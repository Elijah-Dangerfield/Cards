## refactor(ui): swap tutorial pill `RoundedCornerShape(999.dp)` for `Radii.Round.shape`

**Source:** worker-hydrated this cycle (not human-curated).
**Problem:** Four tutorial-screen callsites used `RoundedCornerShape(999.dp)` — the "infinite radius = pill" trick — while `Radii.Round = CornerSize(percent = 50)` already exists in `:libraries:ui` for exactly this. AGENTS.md DS rule §5 says corner radii come from `Radii` tokens.
**Approach:** Mechanical swap. `TutorialPokerScreen` lines 380/382/423 and `TutorialNarrationStep.kt:452` → `Radii.Round.shape`. `TutorialPokerScreen` keeps the `RoundedCornerShape` import for its remaining `RoundedCornerShape(2.dp)` callsite at line 314; `TutorialNarrationStep` drops the import. Visual: pill shape is unchanged (50% of height ≈ 999.dp on heights < 2000dp).
**Reviewer notes:** None. Tutorial previews are the visual safety net.
