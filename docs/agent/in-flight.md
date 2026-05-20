## fix(server): drop "only in solo games" framing from felt catalog copy

**Problem:** V5's seed for `felt_royal_red` described the felt as "visible to you only in solo games", implying it was broadcast to other seats in MP. The 2026-05-20 decision locked felts as private (owner-only render), so that framing is wrong.

**Approach:** Added `V7__felt_private_copy.sql` that `UPDATE`s the one row whose description carried the broadcast-implying clause. V5 was shipped (in a recent commit, but possibly run on dev DBs), so editing it in place would be a Flyway checksum hazard — a forward migration is the safer pattern. Verified the audit half of the todo by tracing `EquippedFelt` through the room VM: the equipped felt is sourced from the local `equipmentRepository.observeEquipped()`, lives only on the screen state, and never appears in `:libraries:gameplay` / `:libraries:game` / server payloads. Render path is already local-only.

**Reviewer notes:** Only `felt_royal_red` had the offending clause — the other felts/themes (`felt_midnight_blue`, `felt_charcoal`, `table_neon`, `table_sunset`, `felt_sunset_weekend`) already read cleanly. `PostgresProductCatalogSourceTest.read_chipOffer_carriesDescription_whenPresent` asserts `contains("Deep red felt")`, which the new description still satisfies.

## fix(profile): hide Sound option from turn-feedback picker until audio lands

**Problem:** The Profile → Gameplay → "Your turn feedback" picker exposes Sound / Vibrate / Mute, but Sound is a no-op — the KMP audio path isn't built (tracked in backlog.md → audio infrastructure). New users get the default `Sound`, which silently gives them no cue at all.

**Approach:** Four touch points, all in service of a single user-visible change ("Sound disappears from the picker, behavior matches what the dropdown advertises"):
- `AppData.turnFeedback` default flipped to `Vibrate` so new users land on a working cue.
- `ProfileScreen` filters `TurnFeedback.Sound` out of the dropdown options and coerces the displayed label to `Vibrate` for legacy users whose stored value is still `Sound` (so the trigger doesn't show an option that isn't in the list).
- `PlayPokerScreen` consumer treats `Sound` as a vibrate haptic, matching the picker's display promise so legacy users actually get the feedback the UI advertises.
- `PlayPokerViewModel.State.turnFeedback` default mirrors `AppData`; test default updated.

Kept `Sound` in the enum (rather than removing it) because persisted JSON may carry it for existing users and removing the variant would fail deserialization — `Sound` is documented as the legacy value and lives behind a single helper (`pickerDisplayValue`).

**Reviewer notes:** Considered a versioned-cache migration to flip stored `Sound` → `Vibrate` at read time, but `versionedJsonSerializer` works at whole-`AppData`-version granularity. Adding a version just to coerce one enum felt heavier than the four-touch coercion approach. Worth revisiting if `AppData` gets a real version bump later.

**Deferred:**
- `ProfileScreen.kt` has an existing pattern of fully-qualified `com.dangerfield.cards.libraries.cards.TurnFeedback` references rather than imports — I followed the local style. Reviewer: nothing for you to do, just noting.

## feat(home): note that bot tables don't move chip balance on the setup dialog

**Problem:** `BotTableSetupDialog` (the seat-count picker that opens before a bots game) doesn't tell new users that bot tables are sandboxed — they may worry that losing a hand to a bot drains their chip balance.

**Approach:** Added a small note above the Start button: "Bot tables don't move your chip balance." Placed it directly above the CTA so the reassurance is the last thing the user reads before tapping. Tone matches the existing subtitle copy on the dialog (declarative, no exclamation marks, no "practice" — `voice-and-copy.md §4.1` explicitly rejects "Practice mode" as too clinical, so the todo's suggested phrasing was reworded to lead with "Bot tables" instead).

**Reviewer notes:** Voice check — `voice-and-copy.md` doesn't have a canonical line for this surface yet, so the wording is my call. Reviewer: if you want a stricter house style here, propose an alternate line and I'll swap it in a follow-up commit.

## feat(server): admin grant-chips endpoint

**Problem:** Production-support gap: when something goes wrong (chargeback, payout error, lost-chips ticket), there was no supported way to credit chips on a specific user's wallet — only the user-authenticated `POST /v1/me/wallet/sync` path existed, which can't be safely impersonated. The todo asked for `POST /v1/admin/grant-chips` behind the existing admin token, writing a `wallet_event` with reason `admin_grant`.

**Approach:** Added the route to `AdminRoutes.kt`, gated by the same `X-Admin-Token` check the other admin endpoints use. The handler calls `WalletRepository.apply(...)` directly — the existing apply path is already idempotent, transactional, and non-negative-balance-checked, so the admin path inherits all of that for free.
- `delta` is signed; negative values debit. An over-debit returns `409 Conflict` + `InsufficientChips` so on-call sees the soft failure rather than mistaking it for a 5xx.
- Ledger reason is stored as `"admin_grant:<reason>"` so `wallet_events.reason LIKE 'admin_grant:%'` filters cleanly while still preserving the operator's free-form note.
- `idempotencyKey` is optional in the body; absent → server fills a UUID. Letting the caller pass one means a retry after a network blip is a safe no-op.
- Validation: 400 on non-UUID userId, blank reason, or zero delta (zero-delta would otherwise consume an idempotency key while doing nothing — easier to surface that upfront).

Tests cover: 401 without/with bad token / with no token configured, 200 Applied + ledger reason stamping, 409 on insufficient chips, idempotency-key passthrough + echo, server-generated key when omitted, AlreadyApplied on replay, 400 validation cases, and that two omitted-key calls produce distinct generated keys (so two genuine grants don't collapse).

**Reviewer notes:** Three calls worth flagging —
1. Stored reason format is `admin_grant:<reason>` (colon-prefixed), not bare `admin_grant`. The todo wording was "writes a `wallet_event` with reason `admin_grant`"; I read that as "stamp the admin_grant namespace on the ledger" rather than "literally store the string 'admin_grant' and drop the operator's note." If the reviewer wants the literal-`admin_grant` form (dropping the operator note), it's a one-line change.
2. Insufficient-chips returns 409 Conflict (matches "request conflicts with current state" semantics) rather than the 200-with-`InsufficientChips`-outcome shape that `/v1/me/wallet/sync` uses. Sync's 200 is right because it's a *batch* that can have mixed outcomes; grant is single-event so a non-200 makes the failure obvious in `curl` / on-call dashboards. Different shape; both intentional.
3. No rate limit on the endpoint. The admin token is already the gate, and we don't expect bulk volume here — adding a rate-limit bucket felt like over-engineering. If we ever script `grant-chips` from a cron, revisit.

## fix(onboarding): hard-guard OnboardingViewModel against returning users

**Problem:** The "bouncing to onboarding when app-config changes" todo: a returning user can land back on the onboarding pager because something past `AppGuardGate` / `SplashGate` causes a root-level recomposition that re-pushes the start destination. The todo asked for both a root-cause fix and a hard guard short-circuiting any path that lands a returning user on `OnboardingRoute`.

**Approach:** Added the hard guard only — root cause still needs hands-on simulator repro. In `OnboardingViewModel.init`, read `AppCache.get().hasUserOnboarded` and `sendEvent(OnboardingEvent.NavigateToHome)` if already true. The existing `ObserveEvents` in `OnboardingFeatureEntryPoint` already wires `NavigateToHome → router.navigate(HomeRoute(), clearBackStack = true)`, so the bounce is one channel-send away. `Catching {}` around the cache read so a DataStore hiccup doesn't trap the user on a blank pager. Updated the docstring + trimmed the todo entry to reflect what's left (root cause).

Why guard in the VM init rather than at the route declaration: the VM init runs once per OnboardingRoute push, and `sendEvent` lands in an unlimited channel that the `ObserveEvents` subscriber drains as soon as the screen composes. Doing the guard at the `screen<OnboardingRoute>` block would require collecting `AppCache.updates` as state, which adds a frame of "show pager → guard fires" anyway and pulls cache reads into the nav layer. The VM placement keeps the responsibility on the VM (which already owns the onboarding flag write).

**Reviewer notes:** Two follow-ups worth knowing —
1. There's a frame of visible flicker (pager renders briefly before the bounce fires) since the cache read is async. The todo accepts that as the cost of a "safety net"; if we want zero flicker, we'd need to gate the screen on `isCheckingOnboarded` state and add a loading placeholder. Skipped because the root-cause fix should make the guard never fire in steady-state — flicker is the *symptom of the bug we still need to fix*.
2. Sign-out paths flip `hasUserOnboarded → false` *before* navigating to `OnboardingRoute` (see `AccountActionsViewModel`, `DeleteAccountViewModel`), so the guard is one-way: a real "send me back to onboarding" intent isn't blocked. Tested in `OnboardingViewModelTest` (`init_alreadyOnboarded_…` + `init_notOnboarded_…`).

**Deferred:**
- Root-cause investigation of which composable above `OnboardingRoute` is recomposing past the existing `AppGuardGate` / `SplashGate` insulation — left as the surviving bullet under "Bouncing to onboarding when app-config changes" in `docs/todo.md`. Needs simulator repro before a worker should touch it.

