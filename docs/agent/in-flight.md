## refactor(shake): route ShakeHandler through DispatcherProvider.main

**Problem:** `ShakeHandler` constructed its `CoroutineScope` with raw `Dispatchers.Main`, violating the repo's dispatcher-injection rule (production code consumes `DispatcherProvider.*` so tests can swap a `TestDispatcher`).
**Approach:** Added `dispatchers: DispatcherProvider` to the `@Inject` constructor and routed the scope through `dispatchers.main`. `apps:compose` already depends on `:libraries:flowroutines`.
**Reviewer notes:** No new test — the todo entry explicitly noted this dispatcher swap doesn't change observable behavior and no test sibling exists.

## test(tutorial): pin TutorialViewModel state machine

**Problem:** `TutorialViewModel` drove the entire onboarding flow (`advance` / `goBack` / `skipBasics` / `restartBasics` / `submit`) plus the `wasFirstCompletion` race against `recordTutorialComplete()`, but had no test sibling — every other feature VM does.
**Approach:** New `TutorialViewModelTest` extending `CoroutineTest`. Local `TutorialAchievementsFake` (configurable `tutorialEarned` + `throwOnRecord`) so the three completion outcomes (first-time / replay / grant-failure → fallback to `true`) are individually pin-able; a local `TutorialAppCacheFake` keeps the test isolated from `Fakes.kt`. The script-walk tests don't hardcode step indices — they read `TutorialScript.steps` for the first basics / non-basics / advance-gated step so the test survives script edits. Used `UnconfinedTestDispatcher` (the `CoroutineTest` default) so `viewModelScope.launch` in `recordCompletion()` drains inline without `advanceUntilIdle`.
**Reviewer notes:** None.

## test(server): pin /v1/avatars route contract

**Problem:** `avatarRoutes()` is the unauthenticated endpoint the avatar picker hits before the Supabase JWT lands, but it had no route test — the "anon ok / full registry / palette / Cache-Control / unlock id presence" contract was unverified despite sibling routes (Equipment, Wallet, Inventory, Me) all having one.
**Approach:** New `AvatarRoutesTest` using Ktor `testApplication` against the route in isolation (no auth plugin installed since the endpoint is intentionally anon). Five tests pin: 200 without auth, pack count + ordering matches `AvatarPacks.all`, `backgroundPalette == AvatarPalette.values`, `Cache-Control: public, max-age=60`, premium packs carry `unlockProductId` while starter is null.
**Reviewer notes:** None.

## fix(room): pin emote-tray trigger to a square footprint

**Problem:** The emote-tray trigger on the play-poker screen rendered as an ellipse instead of a perfect circle — `EmojiButton` uses `defaultMinSize` on its inner Box and emoji glyphs measure wider than tall, so the Surface grew horizontally even though `Radii.IconButton` (= `Radii.Round`, `percent=50`) wants a 1:1 aspect ratio to read as a circle.
**Approach:** Lifted the existing `triggerFootprint = iconSize + 2 × padding` computation out of the popup branch and applied `Modifier.size(triggerFootprint)` to the outer wrapping `Box`. Both the live `EmojiButton` and the `CooldownChip` Surface inherit the fixed square, and the popup's vertical offset still keys off the same value so the picker still anchors cleanly under the trigger.
**Reviewer notes:** Considered fixing this inside `EmojiButton` itself but that would touch every caller and the size-vs-glyph trade-off is documented as intentional on the primitive. Pinning at the call site is the smaller, targeted change.

## test(level): pin XP curve + LevelProgress derived props

**Problem:** `Level.kt` (quadratic XP curve, `levelProgressFor` resolver, `LevelProgress` derived fraction with `coerceIn(0f, 1f)` + divide-by-zero guard) is consumed by Home / Stats / Profile / Shop / Room VMs but had no test pin; the `MAX_LEVEL=100` clamp, the negative-XP clamp, and the fraction-fallback were all unverified.
**Approach:** Added `LevelTest` covering the curve at known levels, the `<1` clamp on `xpToLevelUpFrom`, the level-from-XP boundaries (0, 99, 100, negative, beyond MAX_LEVEL), the three derived `LevelProgress` properties, plus a monotonicity sweep. New `commonTest.dependencies` block added to `:libraries:cards` (it had none) wired to `:libraries:flowroutines:testing` for source-set parity with the rest of the module graph; the tests themselves use plain `kotlin.test` because `Level.kt` is pure math.
**Reviewer notes:** None.

## fix(auth): dismiss keyboard when submitting sign-in / sign-up forms

**Problem:** Tapping the submit button or pressing the keyboard's "Go" action on the auth screens left the soft keyboard on screen, covering inline errors, loading state, and the claim-progress dialog.
**Approach:** Captured `LocalSoftwareKeyboardController.current` at each `SignInScreen` / `SignUpScreen` body, wrapped it in a local `submit = { keyboardController?.hide(); onAction(...Submit) }` lambda, and routed every Submit caller (the primary button + every `onSubmitImeAction` slot on the email/password fields) through it. Doesn't touch `VerifyEmailScreen` — that one has no text inputs to begin with.
**Reviewer notes:** None.

## test(progression): pin ProgressionRepositoryImpl counter deltas + ledger rows

**Problem:** `ProgressionRepositoryImpl` is the only thing standing between hand outcomes and the player's lifetime XP / hand counters, but had no test sibling. `XpCalculatorTest` pins the pure math; the repo's counter-delta + ledger-row composition (the five `handsWonDelta` / `handsFoldedDelta` / `handsLostAtShowdownDelta` / `botHandsPlayedDelta` derivations + `applyAchievementXp`'s `require(delta>0)` + `ACHIEVEMENT/BOTS/null-handId` tagging) was unverified.
**Approach:** New `ProgressionRepositoryImplTest` extending `CoroutineTest`, with in-file `FakeProgressionDao` + `FakeXpEventDao` matching the existing `Achievement` / `Inventory` test style. Nine tests pin the eight bullets the todo enumerated: win/loss/fold counter routing, bot-vs-MP `botHandsPlayed` gate, one-ledger-row-per-`XpCalculator`-award (asserted both on the inserted entities and on the returned `XpEvent`s), zero-aware insert when calculator returns nothing extra, `applyAchievementXp(0|negative)` throws, achievement-XP write tags `ACHIEVEMENT/BOTS/null-handId` and leaves hand counters at 0, `deleteAll` clears both DAOs. Reuses the `FixedClock` shape from `InventoryRepositoryImplTest`.
**Reviewer notes:** None.
