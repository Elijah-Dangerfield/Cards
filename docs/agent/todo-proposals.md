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
