# 2026-05-27 hydration

7 proposals — Lane A: 3, B: 1, C: 1 (spec §6.2 Profile fields), D: 2.

## A. Pin `TutorialViewModel`'s step-machine + completion-grant paths

**Problem:** [`TutorialViewModel`](../../features/room/impl/src/commonMain/kotlin/com/cards/features/room/impl/tutorial/TutorialViewModel.kt) drives the tutorial's 168-line state machine — `advance()`, `goBack()`, `skipBasics()`, `restartBasics()`, `submit(intent)`, plus the `wasFirstCompletion` race with `achievementRepository.recordTutorialComplete()` — but has no sibling test. A regression here breaks the only onboarding path the V1 spec promises.
**Evidence:** `find features/room/impl -path '*/tutorial/*' -name '*Test*.kt'` returns empty. Sibling VMs (`HomeViewModel`, `PlayPokerViewModel`, `LobbyViewModel`, etc.) all have `*ViewModelTest.kt` files.
**Suggested item:** `[P1]` Add `TutorialViewModelTest` pinning: (a) `advance()` walks the script, (b) `goBack()` no-ops on step 0 and clamps within bounds, (c) `skipBasics()` jumps to the first non-basics step and no-ops once past basics, (d) `submit(intent)` only advances when the current step's predicate matches, (e) `completed=true` flips immediately on final `advance()` and `wasFirstCompletion` resolves to the value `achievementRepository.recordTutorialComplete()` returned (true on first run, false on replay), (f) a thrown achievement-repo failure leaves `wasFirstCompletion=true` (the `getOrDefault(true)` fallback) — file at `features/room/impl/src/commonTest/kotlin/com/cards/features/room/impl/tutorial/TutorialViewModelTest.kt` extending `CoroutineTest`.

---

## A. Pin `ChipsRepositoryImpl.sync()` reconciliation branches

**Problem:** [`ChipsRepositoryImpl.sync()`](../../libraries/cards/impl/src/commonMain/kotlin/com/cards/libraries/cards/impl/ChipsRepositoryImpl.kt) reconciles pending `WalletEventEntity` rows against `WalletEventOutcomeDto` results (`Applied` / `AlreadyApplied` / `InsufficientChips` drop the local row; `Unknown` leaves it) and then overwrites the local balance with `response.balance`. None of those branches are pinned — a regression that flips `Unknown` into the resolved set, or skips the authoritative-balance overwrite, would silently corrupt wallets.
**Evidence:** `find libraries/cards/impl -name 'ChipsRepositoryImplTest*'` returns empty (sibling repos in the same module — `InventoryRepositoryImpl`, `EquipmentRepositoryImpl`, `UserMessageRepositoryImpl`, `AchievementRepositoryImpl` — all have tests).
**Suggested item:** `[P1]` Add `ChipsRepositoryImplTest` using fake DAOs + a recording `NetworkClient` to pin: (a) `Applied` + `AlreadyApplied` + `InsufficientChips` outcomes delete the matching pending rows; (b) `Unknown` outcome leaves the pending row in place; (c) `setBalance` after sync overwrites the local row (insert when missing, delta when present); (d) `onColdBoot` triggers a sync; (e) `onForeground(isColdBoot = true)` no-ops; (f) the `syncMutex` serialises concurrent `sync()` calls (one POST observed across two parallel invocations). File at `libraries/cards/impl/src/commonTest/kotlin/com/cards/libraries/cards/impl/ChipsRepositoryImplTest.kt` extending `CoroutineTest`.

---

## A. Pin `GET /v1/avatars` route shape

**Problem:** [`AvatarRoutes.avatarRoutes()`](../../apps/server/src/main/kotlin/com/cards/server/routes/AvatarRoutes.kt) is the unauthenticated endpoint the avatar picker hits before the Supabase JWT lands. No route-level test exists, so the "anon access ok / starter + premium packs returned / `backgroundPalette` populated / 60-second `Cache-Control` set" contract isn't pinned. Sibling routes (`MeRoutes`, `WalletRoutes`, `InventoryRoutes`, …) all have route tests.
**Evidence:** `ls apps/server/src/test/kotlin/com/cards/server/routes/` — no `AvatarRoutesTest.kt`. The companion server-side registry (`AvatarPacks`) is tested at `apps/server/src/test/kotlin/com/cards/server/domain/AvatarPacksTest.kt`, but the wire shape isn't.
**Suggested item:** `[P2]` Add `AvatarRoutesTest` using Ktor `testApplication`: (a) `GET /v1/avatars` with no Authorization header returns 200, (b) response body deserialises into `AvatarPackResponse` with both the starter pack and every premium pack from `AvatarPacks.all` represented (`packs.size == AvatarPacks.all.size`), (c) `backgroundPalette` equals `AvatarPalette.values`, (d) `Cache-Control` header is `public, max-age=60`, (e) premium pack rows carry their `unlockProductId` while the starter pack's is null. File at `apps/server/src/test/kotlin/com/cards/server/routes/AvatarRoutesTest.kt`.

---

## B. Password reset / forgot-password flow

**Problem:** [`AuthRepository`](../../libraries/identity/src/commonMain/kotlin/com/cards/libraries/identity/auth/AuthRepository.kt) exposes `signInWithEmail`, `signUpWithEmail`, `resendVerificationEmail`, `deleteAccount`, `signInWithOAuth`, but no `sendPasswordResetEmail` / `resetPassword` method. A claimed-account user who forgets their password has no in-app recovery — Supabase supports `resetPasswordForEmail` + a deep-linked recovery flow, but it isn't wired. With real email/password sign-up shipping, this is the table-stakes affordance every consumer app has.
**Evidence:** `grep -rln 'password reset\|resetPassword\|forgot password\|ForgotPassword' --include='*.kt'` returns no production matches. No `ForgotPasswordScreen`, no `ResetPasswordViewModel`, no route — only the existing `SignInScreen` / `SignUpScreen` / `VerifyEmailScreen` are wired. The spec doesn't explicitly call this out (Lane B table-stakes).
**Suggested item:** `[P1]` Add a "Forgot password?" flow. Sketch: a `ForgotPasswordRoute` reachable from `SignInScreen`, a `ForgotPasswordViewModel` exposing `sendReset(email)` that calls a new `AuthRepository.sendPasswordResetEmail(email): SendResetOutcome` returning `Sent` / `UnknownEmail` / `Rateimited` / `NetworkError`, and a confirmation screen that surfaces the success outcome with the same voice as `VerifyEmailScreen`. Supabase backs `resetPasswordForEmail`; client-side outcome shape mirrors `ResendOutcome` already on `AuthRepository`. The recovery deep-link target (`ResetPasswordScreen` taking the access token from the URL fragment) is out of scope for this item — file a follow-up once the redirect URL is configured (which the [Supabase email-confirm dashboard entry in `developer-todo.md`](../developer-todo.md) is the prerequisite for).

---

## C. ProfileHeader is missing "Member since" line

**Problem:** Spec [§6.2 Profile fields V1.5](../product/product-spec.md#62-profile-fields) lists "Member-since date" as a V1.5 profile field, and [`Profile.Authenticated.createdAt: Instant`](../../libraries/identity/src/commonMain/kotlin/com/cards/libraries/identity/profile/ProfileRepository.kt) already carries the data (the field's KDoc literally points at this use case: "Useful for 'member since' / 'you've been playing for N days' rendering"). [`ProfileHeader`](../../features/profile/impl/src/commonMain/kotlin/com/cards/features/profile/impl/ProfileScreen.kt) renders avatar + display name + LevelSummary — no member-since line. `ProfileSettings` doesn't even expose `createdAt`.
**Evidence:** `ProfileScreen.kt:58-70` defines `ProfileSettings` without a `createdAt` field; `ProfileScreen.kt:406-470` renders `ProfileHeader` with no date affordance. Spec §6.2 V1.5 line listing it: `docs/product/product-spec.md`.
**Suggested item:** `[P2]` Surface "Member since {Month YYYY}" on `ProfileHeader`. Thread `createdAt: Instant` through `ProfileFeatureEntryPoint` into `ProfileSettings`, render it as a subdued caption row beneath the display name (above `LevelSummary`), formatted month + year ("Member since May 2026"). Use the existing `kotlinx-datetime` formatter pattern; degrade gracefully on `Profile.Fallback` (no row rendered when `createdAt` is unavailable).

---

## D. LevelPill — promote hardcoded XP-ring gradient to `PokerPalette`

**Problem:** [`LevelPill.kt:160`](../../libraries/ui/src/commonMain/kotlin/com/cards/libraries/ui/components/LevelPill.kt) hardcodes `listOf(Color(0xFF4FC3F7), Color(0xFF66BB6A))` for the inner XP sparkle gradient and `LevelPill.kt:215` hardcodes `Color(0xFF4FC3F7)` for the outer `RING_HUE`. The inline comment at lines 211-215 even flags this: *"Hardcoded because the surrounding gradient is too — a `PokerPalette` entry for 'progression cyan' would let both lift off the literal, separate cleanup."* AGENTS.md DS rule §1 + §4 calls raw `Color(0xFF…)` outside `:libraries:ui/system/color/` an anti-pattern.
**Evidence:** `grep 'Color(0xFF' libraries/ui/src/commonMain/kotlin/com/cards/libraries/ui/components/LevelPill.kt` returns the three literals. `PokerPalette.kt` already holds chip-gold, suit colors, etc. for the same reason.
**Suggested item:** `[P2]` Add `PokerPalette.progressionCyan` + `PokerPalette.progressionGreen` (or a paired token name the worker picks based on existing PokerPalette naming) and replace the three `Color(0xFF…)` literals in `LevelPill.kt` (lines 160, 215). Drop the apologetic block comment at lines 211-215 once the swap lands.

---

## D. Convert tutorial pill shapes to `Radii.Round.shape`

**Problem:** [`TutorialPokerScreen.kt`](../../features/room/impl/src/commonMain/kotlin/com/cards/features/room/impl/tutorial/TutorialPokerScreen.kt) (lines 380, 382, 423) and [`TutorialNarrationStep.kt:452`](../../features/room/impl/src/commonMain/kotlin/com/cards/features/room/impl/tutorial/TutorialNarrationStep.kt) each call `RoundedCornerShape(999.dp)` — the "infinite radius = pill" trick. `Radii.Round = Radius(CornerSize(percent = 50))` already exists in [`libraries/ui/src/commonMain/kotlin/com/cards/libraries/ui/system/Radius.kt:41`](../../libraries/ui/src/commonMain/kotlin/com/cards/libraries/ui/system/Radius.kt#L41) for exactly this — pure mechanical swap. AGENTS.md DS rule §5 (corner radii from `Radii` tokens).
**Evidence:** `grep -rn 'RoundedCornerShape(999' --include='*.kt' features/` returns four hits, all in `features/room/impl/.../tutorial/`. `Radii.Round` is already used by `IconButton` and other DS primitives.
**Suggested item:** `[P2]` Replace the four `RoundedCornerShape(999.dp)` callsites in `features/room/impl/.../tutorial/{TutorialPokerScreen, TutorialNarrationStep}.kt` with `Radii.Round.shape` (for `.clip(...)` and `.border(..., shape = ...)` arguments). One-file mechanical swap; existing tutorial previews are the visual safety net.

---

# 2026-05-28 hydration

5 proposals — Lane A: 2, B: 0, C: 2 (spec §5.5 Table-side social), D: 1.

## A. Pin `ProgressionRepositoryImpl.awardForHand` + `applyAchievementXp` branches

**Problem:** [`ProgressionRepositoryImpl`](../../libraries/cards/impl/src/commonMain/kotlin/com/cards/libraries/cards/impl/ProgressionRepositoryImpl.kt) is the only thing standing between hand outcomes and the player's lifetime XP / hand counters. `awardForHand` derives five counter deltas (`handsWonDelta`, `handsFoldedDelta`, `handsLostAtShowdownDelta`, `botHandsPlayedDelta`, plus the `XpCalculator.calculate` total) from `HandResultSummary`, writes the progression row + ledger entries inside a single `ensureExistsAndApply` call, and returns a mapped `List<XpEvent>`. `applyAchievementXp` enforces `require(delta > 0)` and tags every row `XpMode.BOTS` until MP lands. None of those branches are pinned — a regression that flips the wonPot/wasFold deltas, drops the ledger insert, or breaks the achievement-mode-tagging contract would silently corrupt every player's progression.
**Evidence:** `find libraries/cards/impl -name 'ProgressionRepositoryImplTest*'` returns empty. Sibling repos in the same module — `InventoryRepositoryImpl`, `EquipmentRepositoryImpl`, `AchievementRepositoryImpl`, `UserMessageRepositoryImpl` — all have `*ImplTest.kt` files at `libraries/cards/impl/src/commonTest/kotlin/com/cards/libraries/cards/impl/`. `XpCalculatorTest.kt` already exists, but only pins the pure math — not the repo's counter-delta + ledger-row composition.
**Suggested item:** `[P1]` Add `ProgressionRepositoryImplTest` using fake `ProgressionDao` + `XpEventDao` + a `Clock` test double to pin: (a) `awardForHand` with `wonPot=true, reachedShowdown=true` increments `handsWonDelta=1` and `handsLostAtShowdownDelta=0`; (b) `wonPot=false, reachedShowdown=true` flips them; (c) `wasFold=true` increments `handsFoldedDelta=1`; (d) `mode=XpMode.BOTS` increments `botHandsPlayedDelta=1` while `XpMode.MULTIPLAYER` leaves it at 0; (e) `awardForHand` inserts one ledger row per `XpCalculator` award and returns them as `XpEvent` instances; (f) `applyAchievementXp(0)` throws `IllegalArgumentException`; (g) `applyAchievementXp(delta>0)` writes a single ledger row with `source=ACHIEVEMENT, mode=BOTS, handId=null` and returns the matching `XpEvent`; (h) `deleteAll` clears both DAOs. File at `libraries/cards/impl/src/commonTest/kotlin/com/cards/libraries/cards/impl/ProgressionRepositoryImplTest.kt` extending `CoroutineTest`.

---

## A. Pin `Level.kt` derived math + clamps

**Problem:** [`Level.kt`](../../libraries/cards/src/commonMain/kotlin/com/cards/libraries/cards/Level.kt) hosts the quadratic level curve (`xpToLevelUpFrom`), the level-from-XP resolver (`levelProgressFor` — bounded loop to `MAX_LEVEL=100`), and three derived properties on `LevelProgress` (`xpIntoLevel`, `xpToNextLevel`, `fraction` with `coerceIn(0f, 1f)` and divide-by-zero guard). Every Home / Stats / Profile / Shop / Room VM consumes this directly. No test pins the curve, the `MAX_LEVEL` ceiling, the negative-XP clamp, the fraction clamp, or the `xpForNextLevel <= 0` fraction-fallback.
**Evidence:** `find libraries -name 'LevelTest.kt' -not -path '*/build/*'` returns empty. `rg 'levelProgressFor|LevelProgress\b' --type kotlin -l` shows the function is consumed by six production files (`HomeViewModel`, `HomeHeader`, `HomeScreen`, `ShopViewModel`, `StatsScreen`, `ProfileScreen`, `TableUiState`, `PlayPokerViewModel`, `AchievementRepositoryImpl`).
**Suggested item:** `[P1]` Add `LevelTest` pinning: (a) `xpToLevelUpFrom(1) == 100L`, `xpToLevelUpFrom(5) == 2_500L`, `xpToLevelUpFrom(MAX_LEVEL) == MAX_LEVEL².toLong()*100L`; (b) `xpToLevelUpFrom(0)` coerces to `level=1` math; (c) `levelProgressFor(0)` returns `LevelProgress(level=1, totalXp=0, xpAtLevelStart=0, xpForNextLevel=100)`; (d) `levelProgressFor(99)` stays at level 1; (e) `levelProgressFor(100)` advances to level 2 with `xpAtLevelStart=100, xpForNextLevel=400`; (f) `levelProgressFor(-1L)` clamps to the level-1 case (negative-XP guard); (g) an XP value above the cumulative `MAX_LEVEL` budget returns `level=MAX_LEVEL` with `xpForNextLevel = xpToLevelUpFrom(MAX_LEVEL)`; (h) `LevelProgress(level=1, totalXp=50, xpAtLevelStart=0, xpForNextLevel=100).fraction == 0.5f`; (i) `fraction` returns 0f when `xpForNextLevel == 0`; (j) `fraction` clamps to 1f when `xpIntoLevel > xpForNextLevel`. File at `libraries/cards/src/commonTest/kotlin/com/cards/libraries/cards/LevelTest.kt`.

---

## C. Baseline 12-emoji blast pool missing — tray hides entirely when no pack is owned

**Problem:** Spec [§5.5 Table-side social](../product/product-spec.md#55-table-side-social) calls for a "Pool of ~12 base (🔥 🎉 😱 🤡 💀 👀 🥶 🤯 💸 🙏 😎 🥲)" available to every player out of the gate, with "Additional themed packs unlockable via shop" stacking on top. Today every emoji is pack-gated: [`EmojiPackCatalog.availableEmojisFor`](../../features/room/impl/src/commonMain/kotlin/com/cards/features/room/impl/EmojiPackCatalog.kt) returns an empty list when the user owns no `emotes_*` pack, and the KDoc explicitly says "callers should hide the tray UI entirely in that case." A brand-new player can't emote at the table at all, which contradicts the spec's social-signal-by-default framing.
**Evidence:** `EmojiPackCatalog.kt:44-50` returns empty when no pack-id matches; `PlayPokerScreen.kt:128` (`emojiPool.takeIf { it.isNotEmpty() }`) hides the tray on an empty pool. Spec line at `docs/product/product-spec.md:514`: "Pool of ~12 base (🔥 🎉 😱 🤡 💀 👀 🥶 🤯 💸 🙏 😎 🥲)".
**Suggested item:** `[P1]` Wire the spec's 12-emoji baseline pool into `EmojiPackCatalog`. Add a `BasePool: List<String>` constant containing the exact 12 emojis from spec §5.5, prepend it (deduped, base-pool-first) to the result of `availableEmojisFor`, and update the KDoc + `PlayPokerScreen` empty-tray guard so the tray always renders for any seated human. Existing pack additions stack on top in the same stable order. Out of scope: the `Mute this player's emoji` flow (already wired via `mutedEmojiPlayerKeys`) and the reactive auto-fire mechanic (separate item).

---

## C. Reactive auto-fired emoji missing — spec §5.5 lists four triggers, none wired

**Problem:** Spec [§5.5](../product/product-spec.md#55-table-side-social) calls for the game itself to auto-fire reactive emoji at four specific moments: 🤯 on a >50BB pot, 🥶 on a 2-outer river beat, 🎉 on first hand-win of session, 💀 on bust, with a "Toggleable in settings" lever. None of this exists today — emoji blasts only fire from the user tapping the tray.
**Evidence:** `rg 'reactive.?emoji|auto.?fired|ReactiveEmoji' --type kotlin` returns no hits across the repo. `rg '>50BB|2-outer|first hand-win|first.hand.of.session' --type kotlin` returns no hits. No setting toggle for reactive emoji exists in `ProfileSettings` or `AppData`. The four scenarios are detectable from existing state — `HandResultSummary` carries the pot/win/showdown signals already, and bust resolution flows through the engine.
**Suggested item:** `[P2]` Add a `ReactiveEmojiFirer` (or fold into `PlayPokerViewModel`) that hooks the four spec triggers — pot > 50× big blind → 🤯, river card flips a 2-outer (winning probability < ~5% pre-river, won at showdown) → 🥶, first own-seat win in the current session → 🎉, own seat busts to 0 → 💀 — and fires through the existing emoji-blast pipeline as a "system-fired" blast (no cooldown collision with user blasts; same 1.5s animation surface). Add a `Reactive emoji` boolean to `ProfileSettings` (default `true`), persist through `AppData`, and gate the firer on it. Tests: each trigger fires exactly one blast; toggle-off suppresses all four. Out of scope: bot-seat reactive emoji (V1 ships the human seat only); new emoji art (use the spec's exact four glyphs).

---

## D. `ShakeHandler` uses raw `Dispatchers.Main` instead of injected `DispatcherProvider`

**Problem:** [`ShakeHandler.kt:20`](../../apps/compose/src/commonMain/kotlin/com/cards/ShakeHandler.kt) constructs `CoroutineScope(SupervisorJob() + Dispatchers.Main)` directly. AGENTS.md DS rule (and the broader sweep documented in [`eeec040`](../../) — `refactor(ui): route platform AudioRecorder/PhotoSaver through DispatcherProvider`) is that production code consumes `DispatcherProvider.main` so tests can swap in a `TestDispatcher` and so the platform `actual` chooses the right dispatcher. `ShakeHandler` is already `@Inject` and runs in `AppScope` — adding the provider to the constructor is a one-call swap.
**Evidence:** `rg 'Dispatchers\.(Main|IO|Default|Unconfined)' --type kotlin -l | grep -v test | grep -v /build/` returns four hits: the provider itself (`DispatcherProvider.kt`), `flowroutines/Compose.kt` (uses `.immediate` — separate item, captured in [backlog.md](../backlog.md) as the `DispatcherProvider.mainImmediate` entry), `navigation/impl/DelegatingRouter.kt` (also in that backlog entry), and `apps/compose/ShakeHandler.kt` — this is the only non-`.immediate` direct usage and isn't tracked anywhere.
**Suggested item:** `[P2]` Inject `DispatcherProvider` into `ShakeHandler`'s constructor and replace `Dispatchers.Main` with `dispatchers.main` at line 20. One-file mechanical swap. No behavior change; existing shake-detection flow continues working. No new test required (the class has no current test sibling and the dispatcher swap doesn't change observable behavior).

---
