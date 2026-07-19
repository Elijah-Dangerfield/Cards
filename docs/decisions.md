# Decision Log

> **Keep this log small.** Most work does not belong here — see "What goes here" below. If you're unsure whether a change earns an entry, it doesn't. A bug fix, refactor, or exit-path patch is carried by its commit message, not this file. This log is for calls future-you would otherwise *re-derive*, and it should grow slowly.

Decisions made about Cards' product direction and architecture. Append new decisions; do not rewrite history.

## 2026-07-18 — The tester right-edge feedback shortcut is disabled on Android (ENG-31)

**Problem:** Owner reported that on Android you can't swipe in from the right edge to go back. The `rightEdgeSwipe` modifier (App root) is a tester shortcut that opens the feedback form on a leftward swipe starting within 24dp of the **right edge**, enabled on debug + TestFlight builds. Its own comment claimed the right edge is "a low-collision zone — neither iOS nor default Android claims a system gesture there." That's wrong for Android: gesture navigation claims **both** edges for system back. So on a debug/TestFlight Android build (which is what the owner runs), a right-edge back swipe hit the feedback shortcut instead of navigating back.

**Decision:** Gate `rightEdgeSwipe` off on Android (`&& BuildInfo.platform != Platform.Android`). It stays on iOS debug/TestFlight, where the right edge genuinely is free (iOS back is the left edge). Android testers reach feedback via shake-to-debug + the Settings entry, so nothing is lost there. The NavHost/system-back wiring is otherwise untouched — predictive back is on by default at targetSdk 36 and no root `BackHandler` swallows it, so ordinary screens already pop on back once the shortcut isn't intercepting the edge.

**Alternatives rejected:** moving the tester shortcut to a different Android gesture (adds tester-behavior surface for a debug-only affordance that shake-to-debug already covers); declaring `android:enableOnBackInvokedCallback` (deprecated/ignored at targetSdk 36 — predictive back is already default-on, so it wouldn't change anything).

**Status:** Fix shipped; Android `assembleDebug` + iOS compile green. **Needs a device-QA pass** — a gesture is not unit-testable here, and confirming the right-edge back swipe now navigates on a gesture-nav Android device is the last step (QA.md `ENG-31`). This removes the confirmed collision; if back still misbehaves on a specific screen it'll be a per-screen `BackHandler` (e.g. the play screen's intentional leave-confirm), not this.

## 2026-07-18 — Report reason categories store comma-joined; the picker is an inline sheet (MOD-2)

**Problem:** Reporting was single-tap with no reason — a moderator reading `player_reports` couldn't tell harassment from cheating from an offensive name. MOD-2 adds a reason picker + optional details.

**Decision (storage):** Store the selected reason tags as canonical keys (`harassment`, `cheating`, `offensive_name`, `spam`, `other`) comma-joined in one nullable `reason_categories TEXT` column (migration V87), alongside the existing free-text `reason`. The route sanitizes (trim, drop blanks, dedupe, cap length + count) before persisting; `categories` defaults empty across the wire DTOs for back-compat.

**Decision (UI):** The picker is an inline state-driven `ReportPlayerSheet` (DS `BottomSheet` + `SelectChip` multi-select + `OutlinedTextField`), opened from the player-profile sheet's Report action, not a nav-route bottom sheet. The play screen already drives its sheets (profile, badge) off local state (`profileSheetSeat`), so a route would have been the odd one out. Submit is gated on ≥1 reason selected; the optimistic "Reported" flip and rate-limit/error revert are unchanged.

**Alternatives rejected:** a normalized `player_report_categories` child table or a Postgres `text[]` (more machinery than a low-volume moderation log filtered with a LIKE earns; Exposed array support is also awkward); a `bottomSheet<ReportPlayerSheetRoute>` nav route (adds a route + VM for a sheet the screen can own in local state, and would have to interop with the profile sheet's own local-state presentation).

**Status:** Shipped. Server route test (categories captured, trimmed, deduped; empty default), Postgres test (persist joined / NULL when none), client repo test (categories forwarded), VM test (categories + reason ride with the report). Android `assembleDebug` + iOS compile green. The sheet's visuals want a device-QA pass (MOD-1 in QA.md updated).

## 2026-07-18 — The turn-countdown ring anchors to a client-derived per-turn deadline (MP-33)

**Problem:** The on-table countdown ring was a composition-local fixed-duration tween keyed on the turn token, with no time anchor. Tapping stats pushes a full screen, so the play screen leaves composition; on return the ring's `Animatable`/`LaunchedEffect` re-initialized and re-ran the full sweep from full — the timer visibly jumped back up even though the server's real clock kept ticking.

**Decision:** Stamp a stable absolute `turnDeadlineEpochMs` once per turn (`handNumber to lastSequence`) in a `TurnDeadlineTracker` held by `RemotePokerSessionFactory` (which outlives any single composition), surface it on `TableUiState.Active`, and have `TurnCountdownRing` render remaining = `deadline - now` — so a re-entry recomputes the real time-left and resumes. The tracker only stamps for a human seat on a timer-enforced table; no acting seat / a bot clears it.

**Client-derived, not server-broadcast — deliberately.** The ring is explicitly a *visual*: the server (`TurnTimerDriver`) stays authoritative on the actual auto-act timeout, unchanged. A full server-broadcast per-turn deadline (like the between-hands `NextHandPending.deadlineEpochMs`) would also fix cross-client clock drift and reconnect-mid-turn ring accuracy, but it touches the gameplay hot path and wire protocol for a visual-only gain. The client anchor fully fixes the reported bug (and any nav-away reset) with no server/protocol risk. The server-authoritative version is a noted follow-up if drift/reconnect ring precision ever matters.

**Status:** Shipped. `TurnDeadlineTracker` unit-tested (stamps clock+timer; a re-projection of the same turn keeps the same deadline even as the clock advances — the MP-33 guard; a new turn re-arms; null for no-acting / bot). Room-impl suite + `assembleDebug` + iOS compile green.

## 2026-07-18 — Matchmaking rescues a lonely searcher across one buy-in tier (MP-34)

**Problem:** Public matchmaking is find-or-create and atomic, so it never double-creates from a timing race. But two people who both hit "find a table" still ended up alone whenever their buy-in ranges snapped to different canonical tiers (1k/5k/25k/100k): the join filter is `room.buyIn in min..max`, so a 5k searcher never lands on a waiting 1k table. At low liquidity that's backwards — a slightly-off stake is better than no game.

**Decision:** When nothing fits the exact range, `findOrJoinPublic` runs a second **relaxed** pass (`lonelyRescueCandidate`) before creating: seat the searcher onto a *lonely singleton lobby table* (exactly one human, no bots, not full, not their own, no blocked member) whose buy-in is within **one canonical step** (`BuyInTier.withinOneStep`, a 5x ratio) of what they asked for **and at or below their `maxBuyIn`** — so a searcher is pulled *down* to an affordable nearby stake but never *up* past the ceiling they set. Closest stake wins, ties → oldest waiting table. The strict in-range join is unchanged and still runs first; the rescue only fires when it finds nothing, so normal same-tier matching is untouched.

**Alternatives rejected:** collapsing the canonical tier list to fewer stakes (blunt — changes every created table's stake, not just the lonely-pairing case); relaxing the client's candidate poll instead (the server is the atomic authority — fixing it there covers every client and needs no ping-pong "who migrates" rule); merging *any* nearby table rather than only singleton lobby ones (would yank a searcher into a busier or mid-hand game at the wrong stake). Direction is one-way by construction: the searcher joins the incumbent's fixed stake, never the reverse, because an existing room's buy-in can't change under seated players.

**Status:** Shipped. Unit tests cover the rescue (one tier below → paired), the affordability ceiling (never pulled above `maxBuyIn`), the >one-tier gap (stays separate), and blocked-member skip; the integration `searchersAtAdjacentTiers_pairUpAcrossTheGap` replaces the old `searchersAtDifferentTiers_getSeparateTables`, which asserted the pre-MP-34 behavior we deliberately relaxed. Server + integration suites green.

## 2026-07-18 — Android debug builds always use the fake billing catalog (SHOP-11)

**Problem:** The shop rendered empty on debug builds. `billing.realPurchasesEnabled` defaults to `true`, so a sideloaded Android debug build queried the live Play catalog — which isn't provisioned for a dev build — and got zero products. The `FakeBillingClient` + `DEV_FAKE_CATALOG` exist for exactly this but weren't reached by default.

**Decision:** `PlayBillingClient.delegate()` uses the fake whenever `BuildInfo.isDebug`, regardless of the flag: a sideloaded dev build has no Play catalog, so the real client can only ever report an empty shop there. The config flag's default stays `true` and now governs release / internal Android builds only. iOS is left alone — a local `.storekit` config backs the shop even in a debug build, so iOS debug purchase testing still works.

**Alternatives rejected:** making the config default `!BuildInfo.isDebug` (cleaner-looking, but the default is build-independent in the admin config manifest, and `ConfigManifestDriftTest` runs on the debug variant where `isDebug` is true, so it would red the build and the manifest could no longer state one shipped default); hard-gating both platforms on `isDebug` (would break iOS's working `.storekit` debug purchase flow). The flag stays a QA override for release/internal builds; on debug Android it's intentionally inert because the alternative is an empty shop.

**Status:** Shipped. `assembleDebug` + billing impl tests + `ConfigManifestDriftTest` green (the manifest default is unchanged at `true`).

## 2026-07-18 — Typed `AuthOutcome` ships as a standalone classifier, not on `AuthRepository` (AUTH-22)

**Problem:** Phase 3 of the auth-outcome work (docs/decisions.md 2026-07-17) called for a typed `SignedUp` / `SignedIn` / `Linked` outcome so onboarding / verify stop rebuilding new-vs-returning from a raw `resolveIsNewAccount()` boolean at each call site. The item's acceptance literally said "`AuthRepository` sign-in/link entry points return a typed `AuthOutcome`."

**Decision:** Introduce `AuthOutcome` + an injectable `AuthOutcomeClassifier` (`DefaultAuthOutcomeClassifier`) rather than putting the classification on `AuthRepository`. The `SignedUp`-vs-`SignedIn` signal is owned by `ProfileRepository`'s one-shot `/v1/me` `isNewAccount` latch, and `ProfileRepositoryImpl` already depends on `AuthRepository` — so making `AuthRepository` return the classified outcome would need it to read that signal, creating a DI cycle and risking double-consuming the one-shot latch the Home welcome also observes. A classifier that sits *above* both (depends on `ProfileRepository` only, reuses the existing latch) keeps the signal single-sourced and the auth→profile dependency one-directional. `Linked` stays statically known: callers pass `wasLink` (the verify-email guest-link path uses `classify(wasLink = guestLink)`, exercising all three cases through one branch). `OnboardingViewModel` / `VerifyEmailViewModel` now branch on the typed outcome; the duplicated `isBrandNewAccount()` helpers are gone.

**Alternatives rejected:** folding classification onto `AuthRepository` per the literal acceptance (DI cycle + latch double-consume); a full `AccountClaimer` facade that owns every sign-in/link method and returns one unified `AuthResult` (correct end state but a large surface duplicating every auth entry point — deferred, the classifier is the incremental slice); giving `ProfileRepository` a typed `resolveAuthOutcome()` (couples the profile layer to auth vocabulary).

**Status:** Shipped. Classifier unit-tested (link → `Linked` without a server read; new → `SignedUp`; returning → `SignedIn`); the existing onboarding/verify VM suites pass unchanged, driving the classifier through the same `profile.isNewAccount` fake, which is the regression guard that behavior was preserved. The static-`Linked` claim paths (`ClaimAccountViewModel`, `finishAppleSignIn`) still branch on `LinkIdentityOutcome` and weren't rerouted — a unified single-entry-point outcome is the deferred `AccountClaimer` follow-up.

## 2026-07-15 — Receipt account binding widens to the install's upgrade lineage (BILL-11)

**Problem:** A StoreKit transaction stamps its `appAccountToken` with whoever the user was at purchase time. After an AUTH-19 account upgrade (anon → anon / anon → claimed on the same device, each a distinct Supabase user id), a pack bought under the prior id carries that old token forever, so `AppStoreReceiptValidator`'s strict `appAccountToken == caller` check rejected it as `apple_account_mismatch` and stranded the purchase uncredited (owner's "only the medium pack works" report). Fresh purchases under the current id redeemed fine.

**Decision:** The receipt's accepted-account set widens from `{caller}` to the caller's **install lineage** — every user id sharing the caller's `install_id`, plus the caller — carried on `PurchaseReceipt.accountLineage` and resolved by a new `ProfileRepository.findInstallLineage(userId)`. The validator accepts a token in that set. The lineage is derived **server-side from the caller's own profile row**, never from the client-supplied `X-Install-Id` header, so a client can't inject a lineage it isn't part of. Empty lineage (unknown profile or unset `install_id`) falls back to the strict `{caller}` binding, so nothing loosens by default. `install_id` is the only lineage anchor available because these upgrades mint new user ids rather than converting one, and it's the same anchor the L1 orphan sweep already trusts.

**Alternatives rejected:** re-stamping the `appAccountToken` on upgrade (StoreKit transactions are immutable once minted — can't retroactively fix already-purchased packs, which is the actual stranded population); trusting the `X-Install-Id` header directly (a client could then present any install to redeem a receipt whose token it doesn't own); a dedicated lineage table (the `profiles.install_id` column + partial index already models exactly this — a new table would duplicate it).

**Status:** Shipped. Red-first: validator rejects a lineage token under the old strict check (`apple_account_mismatch`), accepts it after the widen; route hands the resolved lineage to the validator; `findInstallLineage` Postgres query spans the install and falls back to `{caller}`. Server suite green (Testcontainers lineage query runs in CI — Docker unavailable locally). Reviewer note: an attacker who set their own `X-Install-Id` to a victim's install at `/v1/me` could join the lineage and redeem a victim's un-redeemed receipt — narrow (beta/sandbox only, grants to the attacker, idempotent per transaction), same header-trust surface as the existing orphan sweep; flagged for the real-money go-live review.

## 2026-07-13 — Player reports are append-only, single-tap, and live in `:libraries:social` (MOD-1)

**Problem:** Google Play's UGC policy needs an in-app report path (we had mute/emote-block but no report), so store submission was gated. The build had no moderation table, route, or client repository — all three had to be added, and several shapes were judgement calls.

**Decision:** (1) *Storage:* a new append-only `player_reports` table (`V85`) — one row per report, no unique constraint on the pair (a reporter can report the same user across rooms; the route's rate limit bounds abuse), FK to `auth.users` `ON DELETE CASCADE` per the V11 convention, `room_code`/`reason` nullable. No auto-ban and no moderation-review UI in V1 (both deferred to post-launch); a `(reported_user_id, created_at DESC)` index is the seam for that future review. (2) *UX:* a single-tap "Report" button on the player card that flips to "Reported" and disables, mirroring the existing add-friend affordance, with a snackbar confirmation — rather than a confirmation dialog or a reason picker. The deliberate button + disabled-after-tap + toast is enough accidental-tap protection for V1, and keeps the report consistent with mute/add-friend. Reporting is **not** gated on the `social.enabled` flag (unlike add-friend): it's a store-compliance path that must stay available on any human opponent. (3) *Home:* the client `ReportRepository` lives in `:libraries:social` (reusing `SocialApi`/`HttpSocialApi` and the room feature's existing social dependency) rather than a new `:libraries:moderation` module — lowest-friction reuse for a one-endpoint feature.

**Alternatives rejected:** a reason-picker sheet or a confirmation dialog before filing (better moderation signal / accidental-tap protection, but a whole extra overlay + state + strings for a nightly slice — deferred to backlog, and the nullable `reason` column is already wire-ready); a dedicated `:libraries:moderation` module (cleaner boundary matching the MOD prefix, but module boilerplate + DI wiring the room feature would have to pick up, for a single POST); folding report into `FriendRepository` (conflates the friend graph with trust-and-safety — kept as a distinct repository even though the HTTP plumbing is shared).

## 2026-07-11 — iOS `previous_exit` is a consume-once MetricKit sample (ENG-25)

**Problem:** `app.launched`'s `previous_exit` was real on Android but hardcoded `unknown` on iOS, hiding iOS crash/ANR/OOM rates from the launch funnel. iOS has no per-launch exit API; MetricKit's `MXAppExitMetric` delivers day-window aggregate counts, up to 24h late, and only on real devices.

**Decision:** Accept MetricKit's granularity instead of faking per-run truth: a Kotlin/Native `MXMetricManagerSubscriber` (inside `IosPreviousExitProvider`, `platform.MetricKit` interop — no Swift/Xcode surface needed) classifies each payload's **foreground** exit counts to the most severe reason (crash > anr > oom > clean), persists it in `NSUserDefaults`, and the next cold start's `app.launched` reports it **exactly once** before clearing (`LatestExitReport`, common + unit-tested). Re-reporting the same window every launch would multiply one crash by the user's launch frequency; consume-once makes iOS `previous_exit` a sparse daily sample — `unknown` on most launches — which dashboards must read as samples, per platform.

**Alternatives rejected:** a clean-exit-marker heuristic (persist foreground/background state, infer abnormal exits per-run like Sentry's watchdog detection) — genuinely per-run but can't distinguish crash/ANR/OOM and misfires on device restarts/upgrades; blending it with MetricKit was judged more machinery than the beta needs (revisit if iOS exit rates become load-bearing). Background exit counts — jetsam kills of suspended apps are routine and would read as fake OOMs next to Android's user-perceived `REASON_LOW_MEMORY`. A Swift subscriber passed through `IosAppComponentFactory` — equivalent at the ObjC runtime, but the Kotlin interop keeps the whole feature in `:libraries:telemetry:impl` and verifiable by the KMP toolchain.

## 2026-07-11 — Client telemetry batches persist to disk before export (ENG-25)

**Problem:** The OTLP log pipe was fire-and-forget: a batch that failed to export was dropped, so every event emitted offline was lost (verified in the 2026-07-11 offline drill). The reliability events this pipe exists for — `net.backend_unreachable`, reconnect failures — are disproportionately emitted exactly when export can't succeed.

**Decision:** Swap the plain batch processor for the library's `persistingLogRecordProcessor` behind our own `durableLogRecordProcessor` seam: batch → disk buffer (`FileManager`-provided app-files directory) → OTLP, with batches deleted only on gateway acknowledgment and leftover batches from prior launches picked up by the same flush loop. Delivery semantics change from at-most-once to effectively at-least-once (rare duplicate on a crash between export and delete — dashboards tolerate it). `is_offline` (from `AppState`) is stamped per-record **at emit time** so late-shipping records describe the connectivity they were born under. A `TelemetryBackgroundFlusher` force-flushes on app background to close the one remaining RAM window (up to one 5s flush tick); it holds the tree through a settable reference because an `AppEventListener` that reaches the config system closes the same DI cycle that split `AppLaunchedEmitter` out. Buffer caps are the library defaults (100 batches / 30 days).

**Alternatives rejected:** building our own persistence on the existing exporter (the library's is purpose-built and sits behind the `processorFactory` seam we already own — if 0.5.0 misbehaves we re-back the seam); gating exports on `AppState.isOffline` to skip doomed POSTs (`isOffline` trips on *backend* unreachability too, and surviving backend outages is this pipe's founding requirement); consolidating the kill-switch flag with the sample rate (an emergency lever shouldn't be a magic number on a volume dial).

## 2026-07-11 — Uncredited purchases stay unfinished at the store; a launch redeemer drains them (BILL-7)

**Problem:** Every TestFlight chip-pack purchase 400'd at `/v1/billing/redeem` ("appAppleId is required when the environment is Production" — Apple's `SignedDataVerifier` refuses to construct a PRODUCTION verifier without the numeric app id, and the lazily-thrown exception took the sandbox verifier down with it). The client left the purchase uncredited with only a toast and no retry path: paid, zero chips, forever.

**Decision:** Three layers. (1) Server: verifier construction is per-environment best-effort — a missing `APPLE_APP_APPLE_ID` degrades to sandbox-only verification (TestFlight receipts are sandbox-signed, so testers work) with a loud log; configuring the id enables production verification. (2) Client: a failed redeem already left the StoreKit transaction unfinished — that is now the durable retry queue. A new `StoreKitCoordinator.loadUnfinishedTransactions` (StoreKit 2 `Transaction.unfinished`) feeds `PurchaseChipPackUseCase.redeemOutstanding()`, driven by an `AutoInit` launch redeemer once auth resolves: redeem → grant → finish, idempotent server-side on the transaction id. Rejected receipts stay unfinished on purpose — finishing would erase the only evidence the user paid. (3) Shop UX (owner directive): purchase-in-flight blocks the page under a "finishing your purchase" overlay, and failures show a full dialog whose copy distinguishes paid-but-pending (`redeem_unavailable`) from refused (`receipt_rejected`) from not-charged store failures.

**Alternatives rejected:** a client-side persisted retry queue (StoreKit already IS one — Apple replays unfinished transactions across reinstalls); consuming failed transactions and refunding via support (destroys the receipt); blocking the purchase CTA until the server is verified reachable (adds latency to the happy path for a rare failure).

## 2026-07-11 — Server-minted rewards signal the client to re-pull the wallet (PROG-12)

**Problem:** ENG-9 moved level-up and achievement chip mints server-side, onto the progression/achievements sync endpoints — but nothing told the wallet. The sync coordinator's per-syncer loops all fire on the same trigger edge, so the wallet sync typically completes *before* (or concurrent with) the sync that mints, and the reward stays invisible until the next edge — observed as "earned 1000 chips, stale until force-kill" (CARDS-98).

**Decision:** The minting endpoints' responses carry a `walletBalance` field, populated only when this request actually minted (a replay signals nothing). The client treats non-null as "the wallet changed server-side" and issues a fresh `ChipsRepository.sync()` — it deliberately does **not** apply the returned balance directly, because a concurrent wallet sync whose server-side read predates the mint can arrive later client-side and stomp it (arrival order ≠ processing order across connections); a pull *issued after* the mint is ordering-safe and serialized by the wallet's own sync mutex. Sync loops stay independent (no coordinator-level ordering between syncers).

**Alternatives rejected:** applying `walletBalance` via `setBalance` (the race above — reintroduces intermittent staleness); ordering the coordinator's loops (couples the isolation/retry design to domain knowledge and still misses mid-session mints); a versioned wallet snapshot (`revision` column + monotonic `setBalance`) — the fully general fix for every balance writer, deferred until a real race is observed since it touches every endpoint that returns a balance plus the MP leave payload.

## 2026-07-11 — Session loss never mints over an existing account; anonymous sessions keep a file-backed mirror (AUTH-19)

**Problem:** A TestFlight upgrade wiped the Keychain copy of the owner's Supabase session while the app's ordinary files survived. Boot found the cached profile but no token, and `GuestSessionHealer` silently minted a fresh guest *over* the real account — balance/XP stranded server-side, and the recovery attempt (Apple sign-in) minted a third account. An anonymous account has no credential, so a lost token is otherwise unrecoverable by the user.

**Decision:** Two independent guards. (1) **Recovery over minting:** the healer never mints when a cached server-backed profile exists — it declares the session unrecoverable (`AuthRepository.markSessionUnrecoverable`), which runs the same teardown as a server rejection and routes to the existing SessionExpired recovery screen (retry, or an explicit sign-in / start-fresh choice). `Reason.SessionExpired` is now *sticky* across re-resolves — previously one `retry()` decayed it to `None`, which is exactly what re-armed the silent mint. (2) **Anonymous session mirror:** `SecureSessionManager` keeps a file-backed copy (`SessionMirrorStore`, ordinary `CacheFactory.persistent` storage that demonstrably survived the upgrade) of **anonymous sessions only**, consulted when the OS store comes up empty and restored back into it. Security trade, accepted deliberately: the mirror holds the refresh token without OS-keystore encryption, but only while the account's sole credential *is* device possession; the moment the account is claimed the mirror is cleared and the session goes back to Keychain/EncryptedSharedPreferences-only (preserving the AUTH-16 posture where it actually protects something).

**Alternatives rejected:** mirroring every session (weakens AUTH-16 for claimed accounts, which have a real recovery path); seeding the minted guest from the cached profile's name/avatar (what shipped originally — it *looks* like recovery while stranding the data); a Keychain-only fix (the loss mechanism is the OS store itself; no retry logic recovers a deleted item).

**Status:** Shipped, red-first (healer mint-over-cache, reason decay, and markSessionUnrecoverable all reproduced failing first). Keychain read/write statuses are now logged on iOS so the next storage loss is visible in session logs. The stranded accounts (087ac8d1… et al.) remain recoverable only by an admin merge — flagged for the owner.

## 2026-07-11 — Room host authority follows the effective host, mirrored client/server (ROOM-16)

**Problem:** The client computed "who is host" as the first connected human (implicit promotion when the tagged host disconnects), while the server gated bot/start mutations on the literal `hostUserId` — which is the synthetic system host on every matchmaker-created Public room. The models disagreed, so the client showed affordances the server rejected: the sole human of a rejoined matchmaking room tapped add-bots seven times into silent `not_host` 403s.

**Decision:** Authority is the *effective host*, computed identically on both sides: the first connected human in seat order, falling back to the first human when nobody reads connected yet (presence flips lag snapshots). Server: `Room.effectiveHostUserId` + `Room.wieldsHostPowers` gate `addBot` / `fillBotsUpTo` / `removeBot` / private `StartHand` (the synthetic system host always passes — server-internal callers use it, and no real JWT carries the all-zeros subject). Client: `LobbyState.effectiveHostUserId` gained the same fallback. The tagged `hostUserId` remains as data (creation cap, wire compat) but no longer gates mutations. Consequence, accepted deliberately: while a private room's creator is disconnected, the next connected member genuinely holds host powers — the client has always *shown* them those buttons; now the server honors it.

**Alternatives rejected:** reassigning `hostUserId` persistently on every presence flip (state churn, host flapping on brief blips, and the per-host room-creation cap would start counting matchmaking placements); allowing add-bots for any sole human without touching the host model (fixes one symptom, leaves the client/server host models diverged for Start and future host-gated ops).

**Status:** Shipped. Red-first service tests (sole matchmaking member adds/removes bots, disconnected-host promotion), route test updated to the new model, lobby VM tests mirror the fallback; full server + lobby suites green.

## 2026-07-11 — Observability suite: dashboard structure, alert contact point, crash-free via previous_exit

**Decision:** One Grafana suite in three folders (Product / Business / Engineering; see
`docs/wiki/observability.md`), Pulse as the home dashboard, alert emails to the owner's gmail via a
single `owner-email` contact point with a 24h repeat cap. Crash-free session/user rates are computed
in Loki from a `previous_exit` attribute stamped on the next `app.launched` (Android
ApplicationExitInfo; iOS unknown until MetricKit) rather than from Sentry, because the Grafana
Sentry datasource cannot chart release-health sessions and a crashing process can't flush its own
crash log. Warn+ client-log forwarding (`telemetry.klogForwardingEnabled`) defaults ON at beta
scale. Revenue panels price from `products.ios_fallback_price` (3 packs exist: small $0.99, medium
$4.99, large $14.99) instead of a hardcoded map. Daily-brief automation deliberately deferred to
post-launch. Old `cards-server Overview` dashboard deleted (its queries absorbed into `dc-infra`
with the `!="200"`→`=~"5.."` error-rate fix).


This log is for in-repo continuity and future sessions. (The V1 punch list lives in [`todo.md`](./todo.md); the old out-of-repo plan file no longer exists.)

## What goes here

**Add an entry when** — you've made a non-trivial call that future-you (or a future agent) would otherwise *re-derive*: a new module boundary, choice of library, a scope cut, a schema shape, an explicit rejection of an obvious-looking alternative, anything where the reason matters more than the change.

**Don't add an entry when** — the work speaks for itself (a refactor, a bug fix, a dependency bump, a typo). Code + commit message + PR title is enough. Most commits don't deserve a decision entry.

When a decision becomes settled enough that it reads as "just how the code works," graduate its explainer to a [`wiki/`](./wiki/) file and remove the dated entry here. The log is for live rationale and rejected alternatives, not settled architecture.

## Format

```
## YYYY-MM-DD — <one-line decision>

**Decision:** <what we're doing>
**Why:** <load-bearing reason — what changes if this reason goes away>
**Alternatives considered:** <briefly, with why each was rejected>
**Status:** Locked / Tentative / Superseded by <date>
```

If a later decision supersedes an older one, mark the old one `Superseded by YYYY-MM-DD` in place — don't delete it. Knowing why we used to think X is often the reason future-you doesn't fall back to X.

---

## 2026-07-07 — Prod validates ONE Apple receipt environment at a time: Sandbox pre-launch, Production from launch day (BILL)

**Decision:** `APPLE_STORE_ENVIRONMENT` stays a single-valued switch; the server never accepts both Apple environments at once. Pre-launch, `cards-server-prod` is set to `Sandbox` so TestFlight builds (whose receipts are always sandbox-stamped) can exercise the full real-purchase path end-to-end. On App Store launch day it flips to `Production` (tracked in [developer-todo.md](./developer-todo.md)), after which only paid App Store receipts mint chips and TestFlight purchases are rejected **by design**. Post-launch purchase testing happens on debug builds against `cards-server-dev`, which stays `Sandbox` permanently — the client code path is identical, so that's a trustworthy signal.

**Why:** TestFlight receipts are sandbox-stamped forever and TestFlight builds always talk to prod, so a prod that accepts Sandbox post-launch lets any invited tester mint chips for free. Freemium no-cash-out chips cap the damage, but there's no need to carry the risk (or extra validator code) when dev covers the testing need. If launch day ever stops being a single hard cutover, this reasoning needs revisiting.

**Alternatives considered:** **Production-first with Sandbox fallback** (Apple's documented pattern; what larger shops run so QA can purchase through TestFlight after launch) — rejected for now: it reopens the free-mint path and is only safe with tester allowlisting + per-grant environment ledgering, which is real work with no current payoff. The trigger to build it is missing TestFlight purchase testing post-launch.

**Status:** Locked (revisit trigger above).

## 2026-07-07 — `billing.realPurchasesEnabled` defaults ON; the flag also selects fake vs real store client (BILL-5 amendment)

**Decision:** The flag's default flips to **true**, and the fake-vs-real billing client selection moves from build type (`BuildInfo.isDebug`) onto the same flag, resolved per call. Default posture on every build: real StoreKit/Play client → receipt to `/v1/billing/redeem` → server-authoritative balance. Flipping off (usually via the QA override) swaps in `FakeBillingClient` + local credit — the escape hatch for simulators, previews, and Android until Play listings exist.

**Why:** The money path is the one worth testing; a debug build on a device should exercise the exact sandbox → redeem path TestFlight will, by default. The old build-type split also became actively wrong once the dev validator got real credentials (2026-07-07): a debug build with the flag on would have POSTed fake tokens to a validator that now rejects them. Dark-shipping was BILL-5's original reason for default-off; with validators live and configured in both envs, that reason is gone. Safety holds regardless: an unconfigured server refuses every receipt.

**Alternatives considered:** Keeping default-off and flipping per-env via config — rejected: every new device/tester starts on the fake path and someone has to remember the flip; the failure mode (testing the wrong flow without knowing) is silent.

**Status:** Locked.

## 2026-07-04 — Reward chips are server-minted; wallet sync refuses `levelup.*` / `achievement.*` credits (ENG-9)

**Decision:** Chip rewards for level-ups and single-player achievements are computed and applied by the server at the sync it can witness — level chips on `POST /v1/me/progression/sync` when the authoritative XP total crosses a rewarded level, achievement chips on `POST /v1/me/achievements/sync` when the earned id is recorded — from server-owned tables (`RewardChips` in the server domain). `POST /v1/me/wallet/sync` refuses any client-asserted positive delta whose reason starts with `levelup.` / `achievement.` (`RefusedServerOwned` outcome, no ledger write); the client drops the refused row and converges on the authoritative balance. The client keeps its optimistic local credit for instant UI.

**Why:** The wallet sync applied client-supplied deltas verbatim, so a modified client could POST `delta=1_000_000, reason="levelup.99"` and mint chips that gate real-stakes MP entry. Granting at the server's own witness points bounds the total mintable via reward reasons to the fixed sum of the server tables, and reuses the exact key/reason convention (`achievement:<id>` / `achievement_grant:<id>`) `DefaultServerWitnessedAchievements` already established, so both grant paths share one ledger namespace and can't double-credit.

**Alternatives considered:** (1) Verify-in-place at wallet sync (server recomputes the amount and applies the client's event when it matches) — rejected: two code paths asserting the same grant, and the client event's key shape becomes load-bearing. (2) Sharing the reward tables through a KMP module instead of mirroring them server-side — rejected for now: the repo already mirrors client tables by hand (`ClientGrantableAchievements`, `CHIP_REWARDS`), the tables are tiny, and drift is self-correcting (the server's number wins on the next balance overwrite). Revisit if the tables grow or the level curve gets retuned via app-config — the server's `RewardChips.levelForXp` duplicates the bundled default curve and must move with any retune.

**Status:** Locked.

## 2026-07-02 — Auth-readiness system shipped; implementation deltas from the 07-01 plan

**Decision:** The unified auth-readiness system landed (AuthGate vocabulary in `:libraries:core`, `AuthGateImpl` evaluator, `authedCall` short-circuit, heuristics deleted). Four deliberate deltas from the approved plan doc: (1) **mid-flight session expiry is covered** — `SessionRejectionBus` gained a monotonic `rejectionEpoch` bumped synchronously inside the bearer refresh, and `authedCall` remaps a 401-with-epoch-bump to `AuthUnready(SessionExpired)`, so the *discovery moment* of a dead session speaks the same vocabulary as the pre-flight gate; correspondingly, a raw 401 reaching a repo now maps to `NetworkError`, not `NotSignedIn` (post-gate it means a transient refresh failure while holding a session). (2) **The `BlockReason` presentation union (plan Stage 4) was dropped** — `AuthGateRoute(reason)` already serves VM-initiated takeovers and `AccessDeniedRoute` stays its own axis; no union until a second real consumer exists. (3) **Operator set trimmed to two** (`mapAuthFailure`, `onAuthFailure`) — the planned `recoverAuthFailure` had an identical signature to `mapAuthFailure`. (4) **`AuthGate` has two entry points** — sync `verdict()` (fail-closed, router) and `suspend awaitVerdict()` (call boundary), because a single sync peek would wrongly short-circuit launch-time background syncs while the cache is still null. Also: `GuestSessionHealer` is single-flighted (`Mutex.tryLock`, skip-if-running) now that the gate kicks it per-call.

**Why:** (1) The epoch beats re-consulting the verdict on 401 because the rejection propagates to the gate's cache through three async hops (bus → session teardown → state flow) while the 401 reaches `authedCall` synchronously — the re-consult loses that race routinely; the epoch bump happens inside `refreshTokens`, which Ktor awaits before the final 401, so it's deterministically ordered. A concurrent call's bump is a harmless false-positive (a confirmed rejection means the session is dead for every in-flight call). (2)–(3) are the plan's own "don't add structure without a consumer" rule applied to itself.

**Alternatives considered:** re-consult verdict post-401 (rejected — race, above); a bounded wait for the rejection to propagate (rejected — latency + a tunable constant for no gain).

**Status:** Locked. Architecture explainer: [`wiki/auth-and-network-errors.md`](./wiki/auth-and-network-errors.md).

## 2026-07-01 — One auth-readiness authority feeds nav, the call boundary, and the UI (unified system)

**Decision:** A single evaluator (`AuthGateImpl`, in `:libraries:identity:impl`) becomes the sole producer of "can this user do this, and if not, why?" — emitting one `AuthReason` vocabulary consumed by the navigation gate, the `authedCall` boundary, and the blocking UI. `authedCall` stops firing doomed unauthed requests: a session-less state short-circuits to a typed `Catching` failure (`AuthUnready(reason)`) that callers map, ignore (`getOrNull()`), or route on via a small shared operator set (`mapAuthFailure` / `onAuthFailure`). The nav gate's private `reasonFor` and every ViewModel's `isFallbackProfile` heuristic are deleted. Heal-on-demand is fire-and-forget (kicks the existing `GuestSessionHealer`), never a blocking wait. Full staged plan: [`docs/plans/auth-readiness-system.md`](./plans/auth-readiness-system.md).

**Why:** The offline-vs-"account needed" decision lived in two disconnected places — the proactive nav gate (correct) and reactive per-ViewModel `isFallbackProfile` guessing (drifts, and fired phantom 401s). An onboarded guest hitting a mid-screen action offline could still get the misleading "account needed" the nav-gate fix already killed, because the two paths never shared a source of truth. One authority makes the two agree *by construction*. If this reason goes away (e.g. every gated action became a navigation, so only the nav gate ever decides), the call-boundary half would be redundant — but mid-screen authed actions are real, so it isn't.

**Alternatives considered:** (1) A new `AuthIntent` param + `AuthedResult` wrapper type on every call — rejected: reinvents the `Catching` monad the codebase already speaks and adds per-call ceremony; intent is better expressed by *how the caller consumes the Result*. (2) `ensureAuthed()` that heals **and blocks/waits** for a session — rejected: hangs for a legitimately signed-out user, wastes time on doomed offline calls, and the passive healer already covers the common reconnect case; fire-and-forget kick + honest-now report is simpler and sufficient. (3) Auto-navigate to the blocking screen whenever an auth error propagates — rejected: propagation is error *flow*, navigation is a *product decision*; the same reason is silent for a background sync, inline for a button, a sheet for a gated screen. The consumer decides. (4) Fold server-driven `AccessDenied` (banned/suspended) into the same `AuthReason` enum — rejected: different axis (we confirmed you vs we can't confirm you); they share only the `BlockReason` presentation union.

**Status:** Locked. Shipped 2026-07-02 (see that entry for implementation deltas); settled architecture graduated to [`wiki/auth-and-network-errors.md`](./wiki/auth-and-network-errors.md) and the staged plan doc was deleted.

## 2026-07-01 — Session anonymity is read from the JWT `is_anonymous` claim, not supabase-kt's `user.identities` (AUTH-12)

**Decision:** `RealSupabaseAuthGateway.currentSession()` now derives `isAnonymous` from the access token's `is_anonymous` JWT claim (decoded by a pure `deriveIsAnonymous` / `isAnonymousFromToken` in `AuthClaims.kt`), falling back to the old `user.identities.isNullOrEmpty()` heuristic only when the token can't be decoded or omits the claim. The claim decode is signature-*unverified* on the client (the server verifies it; we only read a claim off a token we already hold).

**Why:** After a guest claims their account (Google/Apple link → `refreshSession()`), the refreshed JWT reliably stamps `is_anonymous=false`, but supabase-kt's `user.identities` list doesn't always repopulate on the client (device/version-dependent) — so a just-claimed user kept reading as anonymous and every "Save your progress" / "sign in and claim" prompt persisted (CARDS-76). The claim is the same signal the server trusts (`Authentication.kt`), so reading it makes client and server agree by construction. If supabase-kt ever guarantees `identities` repopulates synchronously after a link+refresh, the fallback alone would suffice and the claim read becomes belt-and-suspenders.

**Alternatives considered:** (1) Keep re-fetching / re-refreshing the user until `identities` populates — rejected: unbounded, still races the same non-deterministic client state, and the repo already forces one refresh on the link path. (2) Derive anonymity server-side and surface it on `/v1/me` — rejected as the primary signal: it adds a network round-trip to a decision the client already holds the authoritative token for, and leaves the offline/cold-boot session read (which never hits `/v1/me`) still wrong. `/v1/me`'s `isAnonymous` remains a consistent secondary read. (3) Make `RealSupabaseAuthGateway` unit-testable end-to-end — rejected: it wraps a real `SupabaseClient` with no test seam; extracting the claim decode into a pure function gives the testable unit (`AuthClaimsTest`) without booting supabase-kt.

**Status:** Locked. The claim decode is unit-tested; the end-to-end device behaviour is covered by `docs/QA.md` ONB-17.

## 2026-07-01 — App-root DialogHost renders in a top-most Popup so DS dialogs sit above bottom sheets (GAME-11)

**Decision:** The app-root `DialogHost` now draws its hosted entries inside an `androidx.compose.ui.window.Popup` (full-screen, focusable, non-clipping, and *not* self-dismissing — `DialogOverlay` still owns the scrim / back / outside-tap). Every DS `Dialog` / `BaseDialog` registers with `DialogHostState`, so this single change lifts all of them — including the shake "sun" feedback dialog — above Material's `ModalBottomSheet`. In `@Preview` inspection mode the host falls back to inline rendering, because Compose popups don't paint on the preview surface.

**Why:** `ModalBottomSheet` renders in its own platform window that floats above the entire main Compose composition, so the App-root `DialogHost` — composed *after* `AppNavigation` but still inside the same composition — was occluded by an open sheet (CARDS-6Y: shake feedback hidden behind a bottom sheet). Compositing a dialog above a sheet therefore requires competing at the *window* layer, not the composition layer. A popup created only while entries exist is opened after the sheet's window and wins the z-order. If sheets ever stop using a separate window (e.g. a fully in-composition sheet primitive), this popup indirection becomes unnecessary.

**Alternatives considered:** (1) Re-plumb only the shake→feedback presentation to a bespoke top-most overlay — rejected: it fixes one surface and leaves every other dialog able to hide behind a sheet; the reporter's own "should be a global setting" instinct points at the systemic fix. (2) Give the feedback dialog its own popup at its callsite — rejected as the same one-off, and it duplicates the scrim/animation the DialogHost already centralizes. (3) Re-order the App root Box so DialogHost draws last — rejected: composition order can't beat a separate platform window, so it wouldn't actually fix the bug.

**Status:** Locked. Not unit-testable (window z-order has no JVM-test surface); verified by compile + `docs/QA.md` GAME-11 on device.

## 2026-06-30 — Leaving a table is a synchronous cash-out; the leave response carries the settled balance (MP-29)

**Decision:** `DELETE /v1/rooms/{code}/me` now cashes out the leaver's table stack **synchronously, before freeing the seat**, and returns `200 LeaveRoomResponse(balance)` — the authoritative post-settlement wallet balance — when a stack settled; it stays `204` when nothing settled (lobby / bot-only leave, or an all-in-live deferral whose balance lands later over the socket). The client threads that balance up (`LeaveRoomOutcome.Success.settledBalance` → `PokerSession.leave(): Long?` → the VMs) and applies it via `ChipsRepository.setBalance(...)` instead of the old speculative `sync()`. The settlement decision itself is extracted into one shared `settleLeaver(...)` helper (`server/game/LeaveSettlement.kt`) that both the REST leave and the socket's `MemberLeft` reap call, so the "defer if all-in in a live hand, else cash out at `stackFor`" rule can't drift between the two entry points. `cashOut` is keyed + idempotent, so the REST leave and the socket reap firing for the same user settle exactly once. The client's single-shot reconcile latches (`walletReconciled` in both VMs) are gone; the leave-credit *confirmation* toast keeps a one-shot guard, but the reconcile itself may now retry.

**Why:** MP money is server-authoritative; the client only learned the settled balance via a separate `sync()` fired at exit, which raced ahead of the server's cash-out commit and returned the pre-settlement balance — and the one-shot latch then blocked any retry, so it stayed stale until the next foreground (the recurring "balance still shows the buy-in gone after I leave" cluster: CARDS-5R / 3E / 3G / 3W / 4C / 4G / 58 / 5C). Making the leave call itself the reconcile removes the race by construction: by the time `leaveRoom()` returns, the server has committed the cash-out and told us the number. If this reason goes away (e.g. a global socket that always delivers a terminal settled frame), the REST-body balance becomes redundant with that frame.

**Alternatives considered:** (1) Keep the speculative `sync()` but drop the latch so it retries — rejected: still a race (the first sync can read pre-settlement and the retry cadence is unspecified), and it doesn't make the leave *authoritative*. (2) Fold the settled balance into a terminal socket frame for all leave paths — kept for the *involuntary* teardowns (match-over / opponents-left / host-closed), which have no REST leave to answer, but rejected as the primary mechanism for a *voluntary* leave: there is no global socket (only a per-room one that's tearing down exactly when we leave), so REST is the reliable carrier. (3) Inject `TableSessionService` + `GameSessionRegistry` into `InMemoryRoomService` so `leave()` settles inline — rejected: that couples pure room-membership to wallet settlement and the service is `@SingleIn(ServerScope)` with many direct test constructions; the route handler is the right place for cross-service orchestration, matching how `matchmakingRoutes` already wires the same two collaborators.

**Status:** Locked. Involuntary-teardown paths (match-over / opponents-left / host-closed / kick) still reconcile via the socket + a `sync()` fallback rather than a REST body — folding their settled balance into the terminal frame is the remaining follow-up.

## 2026-06-30 — Wallet reconcile exposes an `isReconciling` flow (MP-30)

**Decision:** `ChipsRepository` gains `val isReconciling: StateFlow<Boolean>`, true from the moment a `sync()` starts (inside the sync mutex) until its round-trip resolves — success or failure. Home and Shop collect it and render the chip badge as "updating" (dimmed number + inline spinner) while true, via a new `isReconciling` param on the `ChipBadge` DS primitive. The interface member has a **default** that returns a shared constant `false` flow so the ~15 existing `ChipsRepository` fakes don't need touching; only the real impl and the two consumer VMs' fakes override it.

**Why:** Balances are server-authoritative, so there's a post-game window where the local balance is a pre-settlement guess the server hasn't confirmed. `observeBalance()` is `Long?` where null only means "not hydrated" — it can't express "hydrated but settling," so the UI showed a wrong-but-confident number the user trusts (worse than a spinner). This is the honest-window half of the MP wallet-staleness work; MP-29 removes the race itself, this surfaces the residual window rather than hiding it.

**Alternatives considered:** (1) Make `observeBalance()` return a richer type (`Balance(value, isSettling)`) — rejected: churns every read site and conflates two orthogonal facts (what the number is vs. whether it's confirmed); a separate flow keeps callers that don't care unchanged. (2) Add the member with no default and update every fake — rejected as needless churn across 15 files for a signal most fakes don't exercise; the constant-false default is the same "fakes needn't implement it" pattern `walletJustCreated`'s neighbours use. (3) Drive the "updating" look from a screen-local timer around the leave call — rejected: the repo is the one place that actually knows a sync is in flight (incl. session-rollover / foreground syncs the screen never initiated).

**Status:** Locked.

## 2026-06-30 — Auth gate distinguishes "offline" from "no account" (AUTH-11)

**Decision:** `RealAuthGateChecker` now injects `AppState` and, when a route's `Account` requirement is unmet and the session is unresolved but the device `isOffline`, gates with a new `GateReason.Offline` ("You're offline — your progress is safe, try again once you're back online") instead of `GateReason.NeedAccount` ("Account needed"). Offline is checked *after* the still-healing (`FinishingSetup`) branch, so an actively-creating guest keeps its setup copy.

**Why:** An onboarded anon guest who cold-boots offline can't refresh its Supabase session (5 attempts exhausted, `GuestSessionHealer` SKIP_OFFLINE), so the gate saw "no session" and told the user "account needed" — which an onboarded guest reads as "my progress is gone" (Sentry CARDS-6J). The gate had no way to tell "offline, can't confirm your account" from "genuinely no account." `AppState.isOffline` is a synchronous `StateFlow`, so the check stays a cheap non-suspending peek on the navigate path.

**Alternatives considered:** (1) Persist/restore the guest session locally so an offline cold boot still has an in-memory identity — larger surface (touches the Supabase session store and every gated path), and a real fix for the *identity* gap but not required to fix the *message* gap this case is about; left for a follow-up if solo-offline needs a live session. (2) Branch the copy inside each calling screen — rejected: the gate is the one central place that already owns the reason→copy mapping; per-screen branching would drift. (3) Read `hasUserOnboarded` from `AppCache` to be sure they're a returning guest — rejected as unnecessary coupling: `isOffline` + unresolved-session is already the exact "can't confirm" signal, and a genuinely-new offline user seeing "you're offline" is still more honest than "account needed."

**Status:** Locked.

## 2026-06-30 — Gold seat ring means "on the clock" only; the aggressor loses its ring (GAME-9)

**Decision:** An opponent seat's gold ring now signals exactly one thing — this seat is to-act (the pulsing "to act" ring, or the timer-enforced countdown ring). The solid gold *aggressor* ring (shown after a bet/raise/all-in and persisting through the street) is removed. The aggressor's "chips going in" meaning is still carried by their gold bet/raise/all-in **action chip** at the bottom-center of the seat.

**Why:** Two semantically-different gold rings on the same seat (to-act vs aggressor) are indistinguishable at a glance, so a bettor's lingering aggressor ring read as a turn indicator that never cleared (Sentry CARDS-6D). Collapsing the ring to a single meaning is the only treatment where a bettor who is no longer to-act can't be mistaken for "still your turn." If we ever reintroduce an aggressor emphasis, it must not be gold.

**Alternatives considered:** (1) Recolor the aggressor ring to a non-gold tone — rejected: the DS has no aggressor token, and `seatActive`/`chipGold` are both golds, so a swap wouldn't create enough distinction without inventing a color; the action chip already conveys "chips in," making a second aggressor affordance redundant noise. (2) Restyle it thinner/dashed — rejected: still reads as a ring around the avatar, i.e. still turn-adjacent; motion/weight alone didn't disambiguate. (3) Keep both but make the to-act ring pulse harder — rejected: doesn't fix a *static* aggressor ring on a seat that isn't acting.

**Status:** Locked.

## 2026-06-27 — iOS IAP = a Swift StoreKit 2 coordinator injected like AppleSignInCoordinator; JWS is the one purchaseToken (BILL-4)

**Decision:** iOS gets real IAP via `StoreKitBillingClient` (in `:libraries:billing:impl/iosMain`), which selects Fake-in-debug / real-in-release exactly like `PlayBillingClient` does on Android. The real arm is a thin Kotlin shell over a Swift `IOSStoreKitCoordinator`, injected through `IosAppComponent` + `iOSApp.swift` — the same seam as `AppleSignInCoordinator`, because StoreKit 2's `Product.purchase()` / `Transaction` are Swift-only `async` APIs that can't be driven from Kotlin/Native. The Kotlin/Native boundary is a non-suspend, callback-shaped `StoreKitCoordinator` interface (in `:libraries:billing`) trading in flattened primitive shapes (`StoreKitProduct`, `StoreKitPurchaseResult`) so the Swift side never constructs a Kotlin sealed type; the result→`PurchaseResult`/`BillingProduct` mapping lives in commonMain (`toPurchaseResult`/`toBillingProduct`) and is the unit-tested seam. Android binds a no-op `AndroidStoreKitCoordinator` so its graph still compiles (StoreKit is iOS-only), mirroring `AndroidAppleSignInCoordinator`.

**Key contract call — the JWS is the single `purchaseToken`.** Unlike Play (where the purchase token is both the server-validation proof and the consume key), StoreKit has two values: the signed JWS (what `/v1/billing/redeem`'s `AppStoreReceiptValidator` verifies) and the `Transaction` handle (what `Transaction.finish()` consumes). The shared `BillingClient` interface only has one `purchaseToken`, and the use case passes it to both `redeem(...)` and `consume(...)`. So the JWS rides as `PurchaseTransaction.purchaseToken`, the StoreKit `transaction.id` rides as `orderId` (the local idempotency key on the dark-launch credit path), and the Swift coordinator retains the unfinished `Transaction` keyed by its JWS — `finishTransaction(jws)` looks it up. One identifier flows end-to-end; no second field added to the cross-platform contract.

**Alternatives rejected:** (1) Conform a Swift class directly to the suspend/StateFlow `BillingClient` — rejected: bridging `suspend` + `StateFlow` + a Kotlin sealed `PurchaseResult` across the K/N boundary is exactly the friction `AppleSignInCoordinator` avoided with a plain callback; the flattened-primitive coordinator keeps the Swift conformance trivial. (2) Add a second `consumeToken`/`transactionId` field to `BillingClient.consume`/`PurchaseTransaction` so iOS could carry the JWS and the finish-handle separately — rejected: it'd reshape the cross-platform contract for one platform's quirk when keying the retained `Transaction` by its JWS solves it with zero interface change. (3) Decode the JWS client-side to extract the transaction id — rejected: needless crypto on the client; `transaction.id` is already in hand from the StoreKit `Transaction`.

**Status:** Shipped (code). Mapping pinned by `StoreKitCoordinatorMappingTest` (success→Apple transaction, JWS≠orderId, already-owned, success-without-verified-tx downgrades to Failed, cancel/pending/failed, product mapping). Verified Android `assembleDebug` (no-op binding in the graph), billing api+impl iOS Kotlin compile, `:apps:compose` iOS Kotlin compile + debug-framework link (SKIE exports `BillingStoreKitCoordinator` etc.), and `IOSStoreKitCoordinator.swift` type-checks clean against the framework. **Not yet** run against an Xcode `.storekit` config end-to-end — that's the developer-gated verification (a `Cards.storekit` config ships at `apps/ios/iosApp/Cards.storekit` ready to attach to the run scheme).

---

## 2026-06-28 — Create-table default buy-in is 1,000 (10% of the starter grant), split from the protocol default (ROOM-13)

**Decision:** The create-table screen now opens pre-selected on a new `RoomSettings.DEFAULT_HOST_BUY_IN = 1_000` instead of the old `DEFAULT_BUY_IN = 5_000`. The two constants are now distinct: `DEFAULT_HOST_BUY_IN` is the host-facing default selection; `DEFAULT_BUY_IN` stays the protocol fallback the server assumes when a create request omits a buy-in (and the value the matchmaker snaps a missing buy-in toward). Max players (6) and the Open-to-anyone default (off, i.e. private) were reviewed and left as-is.

**Why:** A first-time host has a 10,000-chip starter grant. The old 5,000 default committed *half their entire bankroll* to a single table — one bad beat and they're rebuying with nothing left, and they can't sit a second table. 1,000 (≈10% of the grant, 100 big blinds at 5/10 blinds, the classic small-stakes feel) leaves headroom for rebuys and exploration while still being a meaningful stake. The slider still lets a host dial all the way up to their balance, so nothing is lost for a player who wants higher stakes.

**Alternatives considered:** (1) Just lower the single `DEFAULT_BUY_IN` to 1,000 — rejected: it's also the wire/matchmaking fallback, and shifting that quietly changes nearest-tier snapping for buy-in-less matchmaking finds; the host-facing default deserves its own knob. (2) Keep 5,000 — rejected per the bankroll math above. (3) 2,500 (25%) — defensible, but 10% leaves more room for the rebuy + second-table loop a new player is most likely to want; pinned by a test as the upper bound of the sensible band so a future bump stays principled.

**Status:** Locked. Pinned by `RoomSettingsTest.defaultHostBuyIn_isASensibleFractionOfTheStarterGrant`.

---

## 2026-06-27 — Real IAP receipt validators are credential-gated + dormant-by-default; user binding via echoed account token, not orderId (BILL-2)

**Decision:** The BILL-1 `ReceiptValidator` seam now has real platform impls behind a single `StoreReceiptValidator` (`@ContributesBinding(replaces = [DevReceiptValidator::class])`) that dispatches by `Store`. `AppStoreReceiptValidator` verifies the StoreKit 2 signed-transaction JWS offline via Apple's official `app-store-server-library` (5.2.0) against the bundled Apple root CAs (shipped in `resources/apple-certs/`), then enforces our invariants: decoded `productId == expectedSku`, `appAccountToken == userId`, not revoked. `GooglePlayReceiptValidator` calls the Play Developer API's `purchases.products.get` (official `google-api-services-androidpublisher` + `google-auth-library-oauth2-http`) and enforces `purchaseState == Purchased` and `obfuscatedExternalAccountId == userId`. Both read credentials from a new `BillingConfig` (`BillingConfig.fromEnv`, mirroring `SentryConfig.fromEnv`) and stay **dormant** — refusing validation — until their credentials are set. `PurchaseReceipt` gained an `expectedSku` field: the route resolves the catalog product and hands the validator the platform store SKU to compare against the decoded transaction, because the catalog product id (`chip_pack_medium`) differs from the store SKU (`chips_medium`).

**Why:** A forged receipt must be rejected before any real-money sale. Apple's JWS is self-contained, so transaction verification needs only the bundle id + root certs — no App Store Connect API key — which keeps the Apple path verifiable in a sandbox/`.storekit` config. Google requires a server-side lookup, hence the service-account credential. Dormant-by-default fails closed: an unconfigured store rejects rather than trusting, and with BILL-5's `billing.realPurchasesEnabled` flag off (default) the client still credits locally and never hits the endpoint, so the gap is harmless until both the flag and the credentials are deliberately turned on.

**Alternatives considered:**
- **Hand-roll JWS x5c chain verification with the auth0 java-jwt lib already on the classpath.** Rejected: certificate-chain validation against Apple's PKI is exactly the security-critical code you don't reinvent; the official library is maintained, audited, and tracks Apple's environment/revocation rules.
- **Pin the grant to the store `orderId` instead of the echoed account token.** Rejected (and the now-wrong client `BillingClient` doc comment that claimed this is fixed): the order id doesn't bind a receipt to a user, so user A could redeem user B's receipt. Both stores echo the account token we set at purchase (StoreKit `appAccountToken` / Play `obfuscatedExternalAccountId`); requiring it to equal the authenticated caller is the real user binding.
- **Make the validators testable by mocking the Apple verifier / Google publisher.** Rejected: no mocking library on the server. Instead each validator takes an injectable decode/lookup seam (`TransactionDecoder` / `PurchaseLookup`) defaulting to the real call, so the post-verification invariants are unit-tested without a live signed receipt or API call, and the crypto/transport stays the library's responsibility.

**Status:** Locked. Live exercise against the real stores stays developer-gated on credentials + store listings.

---

## 2026-06-27 — Table-wide cosmetics = the host's *equipped* felt + card back, pinned on the room (SHOP-3)

**Decision:** A room carries two new opaque fields — `feltProductId` + `cardBackProductId` — set at create time from whatever felt + card back the host already had equipped, and echoed onto every room snapshot. The play surface renders the host's table cosmetics for *every* player (resolving the ids through the existing client-side `feltForProductId` / `cardBackProductId` switchboard), falling back per-slot to the player's own equipped cosmetic when the host set no override. The server stores + echoes the ids and never interprets them; the felt/card-back style mapping stays client-only. The resolution rule ("first equipped felt / card back, newest-first") lives once in `equippedTableCosmetics` (`:libraries:cards`), shared by the create path and the play surface.

**Why:** The owner directive is "the host's look is the table's look, to incentivize buying cosmetics." Pinning the host's *already-equipped* selection is the smallest honest read of "the host's selection already exists per-player; this makes it table-wide" — the host changes the table by equipping in My Items, the surface they already use, so there's no second cosmetic-picker UI to build, keep in sync, or drift. Keeping the ids opaque on the wire means a future catalog felt needs zero server change. If the "equipped = table look" model ever stops being the intent, the create path is the one seam to revisit.

**Alternatives considered:**
- **A dedicated felt/card-back picker inside the create-room screen.** Rejected for this slice: it duplicates the My Items equip surface, doubles the diff, and adds a second source of truth for "what's the host's look" that can disagree with what they have equipped. Left as a reviewer-triageable follow-up if product wants an explicit per-room override distinct from the host's standing equip.
- **Flip `isPersonalCosmetic(felt/cardBack)` to public.** Rejected: a non-host player's felt/card back is still personal (only the host's propagates), so the "Only visible to you" badge stays correct for the buyer browsing the shop. The table-wide propagation is a property of *being the host*, not of the cosmetic.
- **Carry the cosmetics on each seat rather than the room.** Rejected: it's a single host-level choice for the whole table, not per-seat; the room is the natural owner, and `mergeStakesFrom` already had the placeholder-snapshot machinery to keep them stable across a presence frame.

**Status:** Locked for the host-equipped model. The explicit in-create override picker is deferred (backlog-worthy if product wants it).

---

## 2026-06-30 — Create-room gets an explicit felt + card-back picker, replacing the equipped-read (SHOP-5)

**Decision:** The create-room screen now shows two horizontally-scrollable rows (Table felt, Card back) of **only cosmetics the host owns**, each a live `CosmeticPreview` tile, pre-selected to the host's currently-equipped look. The pick threads through the route (`LobbyRoute.feltProductId` / `cardBackProductId`, nullable) into `LobbyViewModel.CreateRoom`, which prefers the picked id per-slot and falls back to reading `equippedTableCosmetics(...)` only when a slot's pick is null (preserving any create path that doesn't go through the picker). The wire format + render path from SHOP-3 are unchanged — the picked ids pin onto the room exactly as the equipped ids used to. Owned-only is enforced by construction: the entry point builds the lists from `inventory ∩ catalog`, so a spoofed/unowned pick isn't reachable from the UI. An explicit pick of `felt_default` / `cardback_default` now *forces* the plain default table-wide (more expressive than SHOP-3's model, where a null slot meant "each player keeps their own").

**Why:** This is the explicit-picker alternative SHOP-3 deferred, now that the owner asked for it directly. It gives the host intent ("choose the table look") and a "from items I own" surface that reinforces buying cosmetics, without a new source of truth: the picker seeds from the equipped look, so the default behavior matches SHOP-3 unless the host actively changes it. Every seam reuses existing plumbing (`EdgeToEdgeRow`, `CosmeticPreview`, `cosmeticSlotFor`, `equippedTableCosmetics`), so the render + wire contract is untouched.

**Alternatives considered:**
- **Keep the silent equipped-read (SHOP-3 as-is).** Rejected per the owner directive — no in-flow choice, and the create screen can't show what options the host has.
- **A separate cosmetics-picker screen/sheet.** Rejected: two inline rows on the existing Rules card keep the create flow one screen deep and reuse the shelf primitive; a dedicated screen doubles navigation for a two-choice pick.

**Out of scope (known limitations, flagged for later):** server-side ownership validation of the picked ids (freemium, a spoofed cosmetic is pure vanity, not money) and forward-compatible rendering when a host picks a cosmetic newer than a viewer's client (unknown id still resolves to the default felt/back client-side — no crash, but the table looks different across client versions; the fix is the room carrying render data, not just the id).

---

## 2026-06-27 — Force-update gate raises on the next foreground transition, not mid-session (ENG-6)

**Decision:** The app-wide upgrade / maintenance overlay (`AppGuardGate` → `AppGuardState.from`, drawn by `AppGuardLayer` above the whole nav graph) is the live, screen-independent gate for the cross-version rule (CARDS-4S). It recomputes on every streamed-config emission, so bumping `upgrade.minSupportedVersionCode` above a running client's `VERSION_CODE` raises the blocking overlay over *any* screen — including an in-session play screen — **on the client's next foreground transition** (config is fetched on foreground, throttled; `OfflineFirstAppConfigRepository` deliberately does **not** poll mid-session). A client that stays continuously foregrounded mid-hand won't see the gate until it backgrounds/foregrounds. This is accepted as-is for V1; verified by `AppGuardStateTest`.

**Why:** The reactive wiring + z-order are correct (the gate is a pure function of config + version, called on each emission; the overlay draws after the nav content and swallows touches), so the only open question was cadence. Polling for config was deliberately removed in favour of foreground-transition refresh; re-adding it to cover the rare never-backgrounds-mid-hand client would regress that decision for a case that ENG-7 already handles defensively — a genuinely unparseable frame closes the room as `IncompatibleVersion` and exits the table gracefully even without the overlay. The overlay is the broad "time to update" net; the socket close is the per-frame safety mechanism. If that reasoning stops holding (e.g. we ship a breaking change that an in-game client could silently misread without choking), the mid-session-push hardening in backlog.md becomes load-bearing.

**Alternatives considered:**
- **Reintroduce interval polling of config so a foregrounded client re-resolves mid-session.** Rejected: contradicts the documented foreground-only refresh decision and adds steady network/battery cost for a corner case.
- **Push a "config changed / force upgrade" signal over the room WebSocket.** Deferred to backlog (not rejected) — the right shape if we ever need to cover the continuously-foregrounded client, but gated on a product call about adding a push channel.

**Status:** Locked (verification + accepted boundary). Revisit if a breaking gameplay change needs mid-session enforcement.

---

## 2026-06-27 — Client chip credit goes validate->grant->reflect, gated by one `billing.realPurchasesEnabled` flag (BILL-5)

**Decision:** The client purchase flow inverts from "credit locally on store confirm" to validate->grant->reflect, selected at runtime by a single config flag `billing.realPurchasesEnabled` (`RealPurchasesEnabled`, default **off**). When on, a finished store purchase POSTs its receipt to BILL-1's `/v1/billing/redeem` via a new `BillingRepository`, then the client sets its wallet to the server-returned authoritative balance (`ChipsRepository.setBalance`) — never claiming the chip amount itself — then acknowledges. A rejected receipt or unreachable server credits nothing and surfaces a failure. When off, the prior local-credit path is unchanged. `Fake`-platform transactions have no server store mapping, so the repo returns `Unavailable` and they never reach the real endpoint.

**Why:** The local-credit path had a double-credit window and let a forged receipt mint chips offline; BILL-1 gave us the server trust boundary but nothing called it. One flag, defaulting off, both inverts the credit path and ships real billing dark until the real store clients (BILL-3/4) and validators (BILL-2) are live — so prod keeps the safe local-only behaviour with zero further releases needed to flip it on per environment. If the dark-ship requirement goes away (post-launch, validators live), the flag becomes a permanent "real IAP" master switch rather than dead code.

**Alternatives considered:**
- **Two flags — a runtime `useServerCredit` separate from the real-vs-Dev client selection.** Rejected: the client selection is a compile/DI concern (`@ContributesBinding(replaces=…)`), and the credit path always moves together with "real purchases are live." Two flags is needless surface that can drift into an incoherent half-on state.
- **Auto-retry/queue a paid-but-unredeemed receipt inside the use case.** Rejected for this slice: the store still owns the purchase and a re-tap re-redeems idempotently, so the failure is recoverable; a background drain-on-foreground job is the right home for it (flagged as a follow-up), not the synchronous purchase path.

**Status:** Locked; default + client-selection superseded by 2026-07-07 (flag now defaults **on** and also picks fake vs real store client).

---

## 2026-06-27 — Server-authoritative IAP redemption: `/v1/billing/redeem` + `ReceiptValidator` seam + idempotent grant (BILL-1)

**Decision:** Real-money chip redemption is server-authoritative. `POST /v1/billing/redeem` takes `{ store, productId, token }`, runs the token through a `ReceiptValidator` interface, resolves the productId to a catalog `ChipPack` for its server-side `grantsChips` (the client never says how many chips it bought), then grants through `BillingRepository.redeem`. Idempotency anchors on a new `billing_transactions` table with `UNIQUE(store, order_id)`; the audit-row insert and the wallet credit commit in **one** `database.transaction` so a crash can't leave a credited wallet with no record (which a retry would re-credit) or vice-versa. The `ReceiptValidator` ships as a seam with a `DevReceiptValidator` default (trusts the token, uses it as the order id — for local StoreKit/Play test SKUs); BILL-2 swaps in the real Apple/Google validators via `replaces`. The grant pins to the user via the validator's bound account token, not the order id.

**Why:** The client credits chips locally today, so the server never sees the receipt and a forged one mints chips — the V1 ship-blocker BILL-1 names. Routing every chip movement through the existing `WalletLedger.applyInCurrentTransaction` (composed inside the billing transaction) keeps "exactly one place balances change" intact and gets the wallet's non-negative invariant + ledger dedup for free. The `ReceiptValidator` interface is the seam that lets BILL-1 land + be fully unit/integration-tested now while the credential-gated real validators (BILL-2) drop in with zero route changes.

**Alternatives considered:**
- **Catch the unique-constraint violation on the duplicate INSERT to detect a replay.** Rejected: a failed statement aborts the whole Postgres transaction ("current transaction is aborted"), so the post-failure balance read fails. The repo pre-checks `(store, order_id)` existence before inserting; the unique constraint stays as the backstop for the rare concurrent-redeem race, where the loser's transaction rolls back entirely and the client's retry reads the committed row as `AlreadyRedeemed`.
- **Key idempotency on the wallet ledger alone (no billing table).** Rejected: the ledger key is `(user_id, idempotency_key)`, but a redemption needs a `(store, order_id)` audit trail for abuse review and a record that survives independent of the chip math. The billing table is the redemption boundary; the ledger key (`billing.<store>.<orderId>`) is the second line of defence.

**Status:** Locked.

---

## 2026-06-27 — Undeserializable socket frames close the room as `IncompatibleVersion`, gated on missing-required-field vs unknown-type (ENG-7)

**Decision:** The runtime deserialization backstop promised by the CARDS-4S entry lives in the room socket frame decoder (`ReconnectingRoomSocket.decode`), not in a `Catching` wrapper at the game-state read site. The decoder splits two failure shapes: an **unknown discriminator** (a new frame type an old client doesn't dispatch) is dropped and the session keeps running — that is the additive-only convention working as designed; a **`MissingFieldException` on a known frame** (a required field removed/renamed — the breaking change the convention forbids) throws an internal marker that closes the connection terminally as the new `ClosedReason.IncompatibleVersion`. The play screen maps that reason to the "we're struggling to play this game; updating may help" message + a safe exit; the lobby and public-search VMs map it to their existing room-closed / connection-error states.

**Why:** A single `Catching` over the whole decode would conflate "ignore this additive frame" with "this build can't play here," and the natural place to tell them apart is the one function that already owns frame decode and the terminal-close control flow (it already had a `TerminalFrameMarker` for `room_closed`). Keying the breaking case on `MissingFieldException` matches the convention precisely: additions are nullable+defaulted (never missing), so only a removed/renamed *required* field trips it. Surfacing it as a `ClosedReason` reuses the existing close→message→exit plumbing rather than threading a parallel error channel through three VMs.

**Alternatives considered:**
- **Catch all `SerializationException` as incompatible.** Rejected: an unknown discriminator (additive) is a `SerializationException` too, so this would force a "you must update" message for a benign new frame type — the opposite of the additive-degrade tier.
- **Wrap the game-state read in the session/VM.** Rejected: by then the frame is already decoded or dropped silently; the decoder is the only point that sees the raw parse failure with enough context to classify it.

**Status:** Locked.

---

## 2026-06-27 — Game/state objects are additive-only; breaking changes gate on min app version, not per-room capabilities (CARDS-4S)

**Decision:** Cross-client version skew is handled by one two-tier rule. (1) Additive / cosmetic / optional fields degrade gracefully and never bump the version — new fields are nullable + defaulted, and release JSON's `coerceInputValues` coerces unknown values to the property default, so an old client renders the default (e.g. an unknown `felt_*` → `felt_default`) and plays on. (2) A breaking change to the game/state object — repurposing a field, changing an existing field's meaning, or a new rule an old client would misplay — requires raising `upgrade.minSupportedVersionCode` (the existing `:features:upgrade` force-update gate), rolled out *before* the breaking server change ships. The additive tier is the safety net for any in-session straggler between the config bump and the server change. As a runtime backstop beneath both tiers, the client wraps game-state deserialization in a `Catching` block and, on failure, shows a "we're struggling to play this game — it may have been created with a newer app version; updating may help" message instead of crashing or hanging (ENG-7).

**Why:** Breaking changes to core game state are rare and significant (a new variant, a different betting structure) — exactly what you'd want every client on anyway, so a force-update is the correct behaviour, not something to engineer around. Strict additive-only serialization handles the common case for free, so most skew costs nobody an update while the dangerous cases reuse a gate that already exists (`AppGuardState.from` evaluates the streamed config map live). Setting this convention pre-launch is near-free; retrofitting forward-compat behaviour onto an already-shipped launch cohort is not.

**Alternatives considered:**
- **Per-room capability gate** — `requiredCapabilities: Set<String>` on the room snapshot, a client-supported capability set, a config-driven version→capability map, and an "update to join this table" screen. Lets a breaking *gameplay* feature roll out to some tables while old clients keep using the rest of the app. Rejected for V1: substantial new infrastructure for a narrow case (a breaking game-rule change you specifically *don't* want everyone on) that may never arise. Kept as a future consideration if we ever need to ship per-table breaking features without a force-update.
- **Force-update for any change at all.** Rejected: would force a release for a new felt or any cosmetic; the additive-degrade tier exists precisely to avoid that.

**Status:** Locked.

**Follow-up:** ENG-6 (todo.md) — confirm the streamed `MinSupportedVersionCode` overlay reliably covers an in-game client. ENG-7 — the runtime deserialization-failure fallback shipped (see the 2026-06-27 entry below).

---

## 2026-06-26 — Room invites share a deep link via a platform `ShareLauncher`, code as a path segment (ROOM-7)

**Decision:** Sharing a room invite goes through a new `ShareLauncher` capability in `:libraries:navigation` (sibling to `WebLinkLauncher`), surfaced as `Router.shareText(text)` and backed by per-platform impls (Android `ACTION_SEND` chooser, iOS `UIActivityViewController`, JVM unsupported). The invite link is `cards://join/{prefilledCode}` — the code is a **path segment**, not a query param — built from one source of truth, `RoomInvite.linkForCode`, which the lobby deep-link registration also references so the share URL and the registered deep link can't drift. Every room already carries a shareable code regardless of visibility, so the affordance is identical for private/open/public rooms; "public" only adds Find-a-Table discovery on top.

**Why:** A share sheet is a fire-and-forget platform side effect with the exact shape of `openWebLink`, so it belongs on `Router` next to it rather than as a one-off in the lobby screen — any future invite surface (friends, achievements) reuses it. A path-segment code keeps the shared URL human-readable (`cards://join/ABC123`) versus a query string, and centralising the link string means the deep-link basePath and the share builder are provably the same.

**Alternatives considered:**
- **Build the share string + call platform APIs inline in `LobbyScreen`.** Rejected: composables don't own platform side effects here (clipboard is the lone exception, and even that is borderline); a share sheet needs the root view controller on iOS, which only the DI-wired impl can reach.
- **Query-param code (`cards://join?prefilledCode=ABC123`).** Works (Navigation matches it), but the shared link reads as machine output. Path segment is friendlier and still resolves through `routeDeepLink<LobbyRoute>`.
- **Server-issued short links / Universal Links (https://).** Deferred — needs an assetlinks.json / apple-app-site-association host and a link-shortening service. The custom `cards://` scheme is already wired on both platforms and ships today.

**Status:** Locked.

---

## 2026-06-25 — Placeholder ($0) room snapshots are dropped in the data layer, not the UI (MP-16)

**Decision:** The "don't show a $0 buy-in" rule lives as one domain invariant, `Room.preferRealOver(previous)` (backed by `Room.isPlaceholder`, i.e. `buyIn <= 0`), applied at every repo staging boundary: `RoomRepositoryImpl.upsertActiveRoom` (HTTP create/join/addBot) and `ReconnectingRoomSocket`'s `Snapshot` emission (the live lobby path). A placeholder snapshot never regresses a known-good room — the repo retains the last real one. The `LobbyScreen` `if (room.buyIn > 0)` band-aid was removed; the UI no longer defends against an impossible state.

**Why:** A $0 room is structurally impossible (create form seeds a default, server rejects out-of-range buy-ins), so `buyIn == 0` provably means "not a real snapshot" — the stale rebound that arrives after the sole other human leaves. The invariant belongs where snapshots are staged, not at each render site: the lobby `room` actually flows through the socket `Snapshot` path, so a repo-only guard would have left the band-aid load-bearing. Putting the rule once in the data layer means the band-aid (and any future render site) needs no `buyIn > 0` defense.

**Altitude:** Chose "don't regress a real room to a placeholder" over "drop $0 snapshots at one boundary." The narrower framing (guard only `upsertActiveRoom`) misses the real rebound path (the socket), and a guard scattered per-callsite is the band-aid we're removing. The general rule, expressed as a pure `Room` function and applied at both staging points, keeps the invariant in one greppable place.

**Alternatives considered:**
- **Guard only `upsertActiveRoom`.** What the todo literally pointed at. Rejected: the lobby's `room` is fed by the socket `Snapshot` emission, not `upsertActiveRoom`, so this alone wouldn't satisfy the acceptance and the band-aid would have to stay.
- **Keep the `LobbyScreen` guard.** Rejected: pushes an impossible-state defense into the UI; every future room-rendering surface would need to repeat it.
- **`distinctUntilChanged` on the snapshot flow.** Rejected: a placeholder isn't a duplicate, it's a regression; dedup wouldn't catch the real to $0 transition.

**Status:** Locked.

## 2026-06-25 — Match-over result is a play-screen dialog, not a new nav screen (MP-14)

**Decision:** The heads-up match-over "result screen" (MP-14) is a `MatchOverResultDialog` overlay rendered on the existing play screen, sequenced like the bust/showdown dialogs, not a new navigation route. The terminal `match_over_resolved` wire frame closes the socket as a new `ClosedReason.MatchOver(winnerUserId)` (the enum was promoted to a sealed interface to carry the winner id); the VM resolves the local win/loss role and surfaces `PlayPokerState.matchOverResult`, and the dialog's Done CTA fires the same `LeaveGameFromBust` teardown + route-off the bust dialog uses. The live countdown is likewise an on-table banner, not a screen.

**Why:** Every other hand-end result in this feature (showdown, solo bust, MP bust) is a dialog overlay on the play screen with the table visible underneath, and they share the leave/reconcile teardown. A standalone match-over route would fork that established pattern, need its own nav wiring + back-stack reasoning, and lose the "table still behind the scrim" continuity for no user benefit — the match-over is just one more terminal hand-end shape. Keeping it a dialog reuses the rebuy action, the leave-and-reconcile path, and the dialog DS primitives.

**Alternatives considered:**
- **Dedicated `MatchOverRoute` screen.** Real nav target, own VM. Rejected: heavier than the moment warrants, forks the hand-end-result pattern, and the AGENTS bottom-sheet/dialog guidance points the other way for transient terminal overlays.
- **Keep `ClosedReason` an enum, pass the winner id out-of-band.** Would need a parallel channel for the winner id alongside the close reason. Rejected: the reason is exactly where "who won" belongs; a sealed interface carries it cleanly and the other reasons stay data objects.

**Status:** Locked.

## 2026-06-24 — `DELETE /v1/rooms/{code}/me` (leave) is idempotent: 204 across the board

**Decision:** Leaving a room returns 204 whether the caller is a current member, was never a member, or the room is already gone. The route no longer maps `LeaveResult.RoomNotFound`→404 or `LeaveResult.NotInRoom`→409; the service still returns those distinct results, only the HTTP projection collapses them.

**Why:** The caller's goal is "I am not in this room." Once that's true, a non-2xx only reads as a dead leave button. CARDS-2R saw `DELETE /me` 409-loop for ~90s while a room settled after an opponent crashed (the surviving human's membership transiently read as gone), and CARDS-34 saw a re-issued leave 404 after the membership was already cleared — both surfaced as "leave didn't work." Idempotency makes a re-tap, a post-settlement leave, and a double-fire all succeed.

**Alternatives considered:**
- **Queue the leave server-side during settlement, keep 409 otherwise.** More machinery (a pending-leave set + drain) to preserve a distinction no caller acts on — the client already maps 404/409 to a success-equivalent and the lobby treats them as `resetToIdle(error = null)`. Rejected as over-built for the same end-state.
- **Fix only the client (treat 404/409 as success everywhere).** Already largely true, but leaves the server emitting misleading error envelopes other clients/tools would have to special-case. The honest fix is at the contract.

**Status:** Locked.

## 2026-06-24 — Branching: trunk-based, no release branches

**Decision:** Stay on trunk-based development. `main` is always shippable, short-lived branches merge into `main`, releases are tags on `main` cut by release-please. No release branches, no GitFlow, no long-lived `develop` line (the `develop` branch we use is a worker-staging area for the nightly bot, not a release-stabilization branch).

**Why:** release-please is aligned with TBD. Solo dev + store-submitted app + occasional store rejection means the retag cost is real but manageable (~1-2 retags per version at worst). Release branches would add real machinery — dual release-please configs, a merge-back ritual — to solve a problem we hit maybe once or twice per version. The smoother-ritual options (one-shot retag action, skip-play signal on tag push) are ~1 hour of work and cover 80% of the pain without changing the model.

**Alternatives considered:**
- **GitFlow (classic).** Long-lived `develop` + `release/*` branches + `master` for production. Designed for periodic boxed releases on a schedule; the original author has since added a disclaimer it's outdated for most teams. Rejected: high coordination overhead for a solo dev shipping continuously to two app stores.
- **Release branches on top of trunk-based (the practical middle ground).** Cut a `release/vX` branch at version freeze; only stabilization commits go there; merge back to main. Earns its keep with multiple in-flight versions or LTS support. Rejected: we don't have multiple in-flight versions yet, and the dual release-please config would be a tax on every release.
- **GitHub Flow (TBD's simpler cousin).** Effectively what we do. The distinction-without-difference vs. TBD is rhetorical.

**Revisit when:** we hire a second developer, ship multiple major versions needing long-term support, or move to a cadence where v-next is actively underway while v-current is in Apple review. The full essay-form rationale (the four-model walkthrough, what companies actually do, what solo devs actually do, the case for/against release branches in this repo specifically) was preserved in git at `docs/branching-and-release-strategy.md` before its deletion in this commit.

**Status:** Locked.

---

## 2026-06-24 — Banned-account gate lives in the JWT validate→challenge flow, not a post-auth plugin

**Decision:** The server blocks a banned caller (native `auth.users.banned_until`) inside the existing JWT provider's `validate`/`challenge` path: `validate` resolves the user id, calls `ModerationRepository.banStatusFor`, and on a live ban stashes a locked `AccessDeniedResponse` on the call + returns no principal; `challenge` renders that stash as `403 {reason, until, appealUrl}` (else the usual `401`). A `BanGate(moderation, appealUrl)` is threaded into `installAuthentication`. Reasons are `banned` only today (the native flag carries no reason); the lookup fails **open** on a DB hiccup. Wire fields are camelCase to match the rest of the server JSON contract (`MeResponse.isAnonymous`), not the todo's illustrative `appeal_url`.
**Why:** Responding from an `on(AuthenticationChecked)` application-plugin hook does **not** halt the routing pipeline — the route handler still runs and overwrites the response (observed: banned calls returned `200`). The auth provider's validate→challenge is the one place Ktor reliably short-circuits routing (it's how the `401` already works), so a banned caller provably never reaches a handler.
**Alternatives considered:** *A standalone `createApplicationPlugin` on `AuthenticationChecked` that responds 403* — rejected: doesn't short-circuit (proven by a red test). *Throwing a typed exception caught by `StatusPages`* — rejected: exceptions thrown from the auth hook didn't propagate to StatusPages (still `200`). *A new moderation table for suspended-vs-banned + appeal URL* — deferred: the native flag only carries banned-until, and a richer split isn't needed for the minimum "don't let banned users keep playing" slice.
**Status:** Locked (server half). Client half — parse the `403` and route to `BlockingErrorScreen` instead of the generic session-expiry screen — is the remaining slice in `docs/todo.md`.

---

## 2026-06-20 — Friend graph: one canonical-pair row + an `acted_by` direction marker

**Decision:** Model `friend_relations` as one row per *unordered* user pair — `user_a` is always the lexicographically smaller UUID (a DB `CHECK (user_a < user_b)` enforces it) — with `state ∈ {requested, accepted, blocked}` and an extra `acted_by` column recording the user who set the current state (the request sender, the blocker). The repository canonicalises every pair before reading/writing by comparing the lowercase-hex UUID string, which orders identically to Postgres's `uuid <` operator.
**Why:** The spec required the row to be "unique regardless of direction" (no both-(x,y)-and-(y,x)). A single canonical row satisfies that, but then nothing records who requested whom or who blocked whom — which the inbox ("requests *to* me") and block semantics both need. `acted_by` is the minimal addition that restores direction without a second row. Comparing UUID *strings* (not `java.util.UUID.compareTo`, which is signed-bits) is what keeps the Kotlin canonicalisation in lockstep with the SQL `CHECK` — get this wrong and you get either duplicate-direction rows or constraint violations.
**Alternatives considered:** *Two directed rows per pair* — rejected: breaks the uniqueness requirement and doubles every read. *No direction column, infer from a separate requests table* — rejected: a second table for what one column carries. *`UUID.compareTo` for canonical order* — rejected: its signed-long comparison disagrees with Postgres `uuid <` for high-bit ids, silently desyncing app and DB.
**Status:** Locked. (Schema + endpoints shipped; the recently-played-with send gate is the one remaining slice, blocked on the recently-played-with record.)

---

## 2026-06-19 — Opponent level over the wire freezes per session (mirrors badges/avatar)

**Decision:** MP opponents' levels are rendered from a new `Seat.xp` field the server snapshots once at hand-start (`RoomSocketRoutes.handleStartHand` resolves each member's `ProgressionRepository.find(userId)?.totalXp`), copied onto the engine `Seat` and preserved across hands in `GameSession.requestNextHand` — exactly the path `badgeProductIds` and `avatarEmoji` already take. The client derives the level locally via `levelProgressFor(seat.xp).level` in `TableUiState.badgeFor` and `occupantsFor`. Level is therefore **frozen for the lifetime of the session**: a player who levels up mid-session keeps their start-of-session level on opponents' screens until a fresh session.

**Why:** The todo flagged a real choice — re-resolve XP on every `RequestNextHand` so levels tick up mid-session, or freeze per session like badges. Freezing is the consistent, lower-risk call: it reuses the existing avatar/badge resolution seam verbatim (one resolve site, no new repo plumbing into the `GameSession`/registry `requestNextHand` path, which has no repository access today), and a stale-by-one-session opponent level is cosmetic and self-corrects next session. Re-resolving fresh would mean threading `ProgressionRepository` down into the registry's next-hand path purely to make a cosmetic pill tick — not worth the coupling for V1.

**Alternatives considered:** (1) **Re-resolve on `RequestNextHand`** — rejected for V1: cosmetic benefit, real coupling cost (repo into the registry/session next-hand path). Revisit if a "leveled up at the table" celebration ever wants live opponent levels. (2) **Send the derived level (Int) over the wire instead of raw XP** — rejected: XP is the canonical value and the client already owns the curve (`levelProgressFor`); sending XP keeps one source of truth and lets the curve change client-side without a server change. The richer tapped-opponent Player Card (badges + title + level) in `backlog.md` reuses this same `Seat.xp`.

**Status:** Shipped — `Seat.xp` plumbed end-to-end; level renders on opponent seats. Needs a server deploy to populate XP (pre-deploy, `find` returns the row or null and the pill omits gracefully).

---

## 2026-06-15 — Launch shape: monetized + full public (V1)

**Decision:** V1 ships as a **full public** launch (not a closed beta) **selling chip packs (real-money IAP) from day one.**
**Why:** This is the chosen rollout for V1; it's logged because it's the gating decision that puts several items on the **hard critical path** a free-or-beta launch could have deferred. If we ever switch to free-at-launch (billing flagged off) or a beta-first track, most of the consequences below relax.
**Consequences (now critical-path, not deferrable):**
- **Server-side IAP receipt validation + server-authoritative purchase ledger** before any sale — the client currently trusts the receipt and credits chips locally. ([todo.md §C Billing integrity](./todo.md).)
- **Store IAP products + pricing + store API credentials** (developer-todo) — these gate the receipt-validation work, and have lead time, so start them first.
- **Full legal/compliance up front:** ToS/Privacy, store data-safety disclosures, age/content ratings, support + web-deletion URLs, LLC/insurance (developer-todo legal).
- **Prod DB backups / PITR** before real balances exist (developer-todo dashboard).
- **Public-MP quality gates** (per-turn timer, orphaned-room forfeit — todo.md B3) — no beta to shake them out.
**Recommended first code item:** DB-backed config Phase 1 ([todo.md §C](./todo.md)) — unblocked now, gives an IAP kill switch + live `minSupportedVersionCode`, and is a launch-day safety net. Receipt validation jumps to the top once store IAP products + credentials exist.
**Status:** Locked (revisit only if Elijah switches to free-at-launch or beta-first).

---

## 2026-06-09 — Central, declarative auth-gate on navigation

**Decision:** A route declares what identity it needs via `Route.authRequirement` (`None` / `Account` / `ClaimedAccount`). `DelegatingRouter.navigate()` consults an injected `AuthGateChecker` and, when the requirement isn't met, transparently substitutes a shared `AuthGateRoute` (a bottom sheet) for the requested route — copy/CTA chosen from a `GateReason` (finishing-setup / need-account / need-claimed). First applied to `LobbyRoute` + `PlayMultiplayerRoute` (`Account`).

**Why:** Gating is a cross-cutting concern that should be declared once per feature and enforced in one place. `navigate()` is the single choke point for all navigation, so enforcing there is proactive (blocks before the screen renders *and* before any authed call fires) and uniform. Adding a gate to a new feature is one constructor arg on its route — no per-screen guard code.

**Decoupling:** `AuthRequirement` / `AuthGateChecker` / `AuthGateRoute` live in `:libraries:navigation` (just markers + an interface). `RealAuthGateChecker` (in `:navigation:impl`, which gained an `:libraries:identity` dep) caches `AuthState` + `GuestAccountCreator.state` from their flows so the check is a synchronous peek (navigate isn't suspend) and fails *closed* before auth resolves. It's an `AutoInit`, not an `AppEventListener`, to avoid the `AppEventBus` DI cycle. The gate sheet lives in `:apps:compose` because its CTAs span onboarding + claim.

**Alternatives considered:**
- *Throw `AuthError`, catch → error page* — rejected: reactive (you've entered the feature / fired the 401 before bouncing), scattered across call sites, control-flow-by-exception.
- *A `RequireAccount { … }` composable wrapper per screen* — rejected (and explicitly disliked as a web-ish pattern): per-feature, and still reactive (navigate-then-bounce flicker) rather than proactive.

**Status:** Locked. Note: route-gating covers *navigations*, not in-screen *actions* — real-money purchase buttons (an in-screen action) still need a VM-level `isAnonymous` check; `ClaimedAccount` is ready for any checkout *route*.

---

## 2026-05-30 — Multiplayer host = first connected member (implicit auto-promotion)

**Decision:** The lobby's "effective host" is computed client-side as `room.members.firstOrNull { it.isConnected }?.userId`, not the server-tagged `room.hostUserId`. The host badge, the "Start hand" CTA, and the snackbar promotion notification all key off this computed value. When the original host disconnects (`isConnected = false`), the next-listed connected member becomes effective host automatically with no server change.

**Why:** The acceptance criterion for V1 multiplayer ("two humans play a full hand") implies one player can start the hand, and that role must survive a disconnect mid-session. The two viable shapes for host-departure were (a) auto-promote silently, (b) kill the room. Killing every room when the host steps away is hostile UX (everyone in the middle of a hand gets bumped). Auto-promoting is the obviously-warmer option — the question was whether to add server state for it or derive it client-side. Deriving from `members.firstOrNull { isConnected }` is a one-liner, requires zero server changes, and preserves in-progress hands (the engine is server-authoritative; promotion only matters for the *next* `StartHand` frame).

**Alternatives considered:**
 - **(a) Server-side promotion** — add `currentHostUserId` to the room state, server reassigns on disconnect, broadcasts updated snapshot. Cleaner conceptually but couples a UX call to a wire-format change for no client-visible delta over the derived approach.
 - **(b) Kill the room on host leave** — closes every in-flight hand whenever someone hosts then drops their connection. Punishes the other players for the host's network blip.
 - **(c) Promote with a grace period** — let the original host reconnect within N seconds before promoting. Needs a server timer + state; not warranted before we see this happen in real playtests.
 - **(d) No promotion (any seated player can start)** — collapses the "who starts" UX to a free-for-all. Cluttered ("two players both see the Start button"). Rejected for V1 — the host concept is the simplest mental model.

**Status:** Locked for V1. Migrate to server-driven host if we ever need it to be observable from non-clients (analytics, server-side moderation, tournaments) or if the silent auto-promotion turns out to confuse remaining players in real playtests.

---

## 2026-05-30 — Trace MP broadcasts via span links on a `TracedGameEvent` envelope

**Decision:** The per-recipient `ws_send` fan-out spans link back to the intent that caused them using OpenTelemetry span *links*, not parent/child reparenting. The originating span context rides on a `TracedGameEvent(event, originSpanContext)` envelope wrapping `GameSession.events` (`SharedFlow`); the socket publisher reads it off the envelope and `addLink`s it onto the `GameEventOccurred` `ws_send` span. The conflated game-state `StateFlow` leg is left unlinked for now.
**Why:** The fan-out is genuinely asynchronous — there's no central broadcast loop; each socket independently collects a shared flow, so a single state mutation produces N sends across N coroutines. A span *link* is the OTel-correct primitive for "this work was caused by, but is not a synchronous child of, that span." Reparenting would force a fake single-parent tree onto a one-to-many async relationship and require threading a live `Context` through the collectors. Putting the context on an envelope (vs. on the domain `GameState`/`GameEvent`) keeps tracing out of the gameplay types.
**Alternatives considered:** (a) Reparent sends under `submit_intent` — wrong shape for async fan-out, and conflation means a `StateFlow`-driven send can't be attributed to one exact intent. (b) Put the context on `GameState` itself — pollutes the domain type that's also persisted to `room_sessions.state_jsonb`. (c) Do the `StateFlow` leg too — deferred because conflation collapses rapid updates, making per-value attribution approximate; sliced into `docs/todo.md`.
**State-snapshot leg (landed 2026-05-30):** Applied the same envelope pattern to the `GameStateSnapshot` leg via a sibling `TracedState(state, originSpanContext)` `StateFlow` — chosen over converting `GameSession.state`'s type (which would ripple through ~30 readers and the persistence path) so every existing `state` reader stays untouched and tracing stays off the domain types. Accepted the approximate-attribution caveat alternative (c) named: `StateFlow` conflation may collapse rapid mutations, so a recipient attaching mid-burst links only to the latest state's origin. The precise per-intent chain is still captured on the un-conflated events leg.
**Status:** Locked for both gameplay legs (events + state snapshot). Lobby snapshots stay unlinked by design.

---

## 2026-05-29 — Multiplayer: snapshot-only state, OTel for debugging

**Decision:** Server-authoritative MP state lives in a single `room_sessions(session_id UUID PRIMARY KEY, state_jsonb JSONB, updated_at TIMESTAMPTZ)` row, overwritten inside the per-session mutex on every mutation. Drop the event-sourced `game_events` write path (it shipped 2026-05-28 against the prior direction and never had a reader). Debugging visibility ("every move on every hand") is provided by OpenTelemetry traces on the server — one trace per `SubmitIntent`, spans for the engine pipeline, attributes for `session_id` / `user_id` / `hand_id`. Sentry covers crash + error capture (single project, platform-tagged).

**Supersedes the 2026-05-27 "Multiplayer: event-sourced game state + persisted room membership" entry.**

**Why this over the prior path:**
- The event log's *only* V1 consumer was crash recovery. A single-row snapshot table accomplishes that in ~20 lines.
- Hand history / spectator hydrate / replay-as-a-feature aren't V1 scope. Designing for them now pays the complexity tax for a future we may not build.
- Per-hand event volume (~15–30 rows) scaled to a real MP userbase = millions of rows per day without a pruning policy. We don't have a pruning policy.
- OTel traces give us *better* "every move debug" visibility than `game_events` would have. Spans carry timing + attributes + cross-service correlation; `game_events` rows are just append-only Postgres tuples.
- The animation-pop on reconnect (without the rolling tail) is a one-frame visual jank, not a correctness issue. The 5-minute disconnect grace means the snapshot a reconnecting client reads is always fresh.

**Alternatives considered:**
- **Keep event-sourced.** Rejected: pays for features V1 doesn't ship; doesn't actually give us "every move debug" (OTel does that better).
- **Hybrid (snapshot-only + rolling tail of last ~50 events for reconnect animation replay).** Parked. Add only if reconnect smoothness becomes a real user complaint — the write path is already mostly written.

**Telemetry:**
- **Sentry — single project, platform-tagged.** Tag every event with `platform=ios|android|server` + `release=<version>`. Multi-project = fragmented alerts + harder cross-platform regression triage.
- **OpenTelemetry — server only for V1, traces *and* logs.** Ktor instrumentation + OTLP exporter handles both signal types. One trace per `SubmitIntent`, spans for validate → engine-resolve → state-mutate → broadcast → per-recipient WS-send. Server logs also flow through the OTel logs SDK so they're auto-tagged with the current `trace_id`, enabling trace ↔ log correlation in Grafana. Client-side OTel deferred; client errors flow through Sentry.
- **Where signals land.** Confirmed via Fly's community thread on Grafana data sources: Fly's bundled `fly-metrics.net` is multi-tenant and locked down to its built-in data sources — users get Editor-only access, can't add Tempo or external endpoints. Their managed Quickwit deployment is also wired for logs only (no Traces tab in their Grafana). So Fly's bundle gives us logs (Quickwit / VictoriaLogs) + metrics (Prometheus); traces have no home there. **Decision: Grafana Cloud is our daily Grafana.** Logs (Loki) + traces (Tempo) ship there via OTel; Fly's Prometheus added as a remote datasource using `flyctl auth token` so infra metrics stay queryable from the same UI. Fly's `fly-metrics.net` stays available for the canned infra dashboards but isn't where we live day-to-day.
- **Collector preference order** if Grafana Cloud is ever outgrown: self-hosted Grafana + Tempo + Loki as a Fly app (Fly's own suggested workaround for the locked bundle), then Honeycomb (paid, best trace-query UX). Captured in [`developer-todo.md`](./developer-todo.md).

**What changes in the spec / code:**
- [`todo.md §B`](./todo.md) rewritten around the snapshot-only direction; `B0` collapsed to one snapshot bullet, `B1` reduced to "snapshot-on-reconnect," `B5` parks the rolling-tail option.
- The shipped `game_events` write path retires in a follow-up commit (P2 in `§B0`). The table either drops or is kept bookmarked for a future hand-history feature.
- New `§C Observability` section in `todo.md` tracks the Sentry + OTel wiring; new dashboard items in `developer-todo.md` track the project / endpoint provisioning.

**Status:** Locked.

---

## 2026-05-29 — RLS enabled (deny-all) on per-user tables

**Decision (landed):** Flipped RLS on for every per-user table — `profiles`, `wallets`, `wallet_events`, `inventory`, `equipment`, `user_messages`, `products`, `room_sessions`, `game_events` — **with no policies**. This is "default-deny against the PostgREST `anon` and `authenticated` roles" — exactly what we want because the client never hits PostgREST directly (all data flows through the Ktor server's service-role JDBC connection, which bypasses RLS).

**Why this isn't the "inert policies false sense of security" trap flagged in the 2026-05-23 entry below:** there are no policies. The wall is hard. Anon clients with the public key can no longer pull rows from `https://yuqrfhdoejonclgbixlw.supabase.co/rest/v1/...`. Authenticated users with a valid JWT also can't (they have no business hitting PostgREST in our architecture). Only service-role connections get through, which is Ktor.

**Triggered by:** Supabase dashboard warning *"This table can be accessed by anyone via the Data API as RLS is disabled."* The warning is correct; the original deferral conflated two different RLS problems:
- **(A) PostgREST anon/authenticated data-API hole** — closed by this entry via deny-all.
- **(B) Per-user policy enforcement** (`USING (auth.uid() = user_id)`) — still deferred per the entry below. Requires the per-request DB role architecture; not worth it for V1, and would still be inert under the current service-role connection.

**Verification:**
```bash
curl "https://yuqrfhdoejonclgbixlw.supabase.co/rest/v1/wallets" \
  -H "apikey: <anon_key>" -H "Authorization: Bearer <anon_key>"
```
Before: returns rows. After: `[]` or permission error. Ktor routes continue working unchanged.

**Status:** Locked.

---

## 2026-05-23 — Split `IdentityRepository` into `AuthRepository` + `ProfileRepository`

**Decision:** The single `IdentityRepository` is split into two narrower repositories with a one-way dependency:
- **`AuthRepository`** owns the Supabase user lifecycle + access token end-to-end. Operations: `current()` / `observe()` (resolved-only — no in-flight sentinel), `accessToken()`, `refreshAccessToken()`, `retry()`, plus sign-in/up/OAuth/link/delete/sign-out flows. There is no separate `AuthTokenProvider` — auth is the producer.
- **`ProfileRepository`** owns `/v1/me` + the local profile cache. Collects `authRepository.observe()`; on every emission, resolves to `Profile.Authenticated` (server) or `Profile.Fallback` (cache → localId UUID) via `Catching { server }.fold(success → write cache, failure → read cache)`. Cache is fallback, not first-frame.
- **`Profile`** is now sealed: `Authenticated` vs `Fallback`. Compiler-enforced gating at call sites — shop hard-gates on Authenticated; offline-browsable surfaces accept either.

`IdentityRepository`, `Identity`, `IdentityState`, `IdentityCache`, `SupabaseIdentityRepository`, `AuthTokenProvider`, `NoOpAuthTokenProvider`, `SupabaseAuthTokenProvider` deleted entirely.

**Why:** Three things conflated under one repo:
1. Auth state changes rarely (sign-in, sign-out, refresh). Profile state changes on every edit and on every server resolve. Different lifecycles, different consumers, different failure modes.
2. The `IdentityState.Unknown` sentinel forced every caller to handle a "we don't know yet" branch. The new design pushes that to the call boundary (`suspend current()` or `.first()` on `observe()`), which is the right place for it.
3. The optimistic cache emit at construction made every consumer race the server resolve — `SignedIn(cached)` would fire before `/v1/me` landed, producing stale-state UI flashes. Cache-as-fallback (only on `onFailure`) removes the race by design.

The trigger was a `401 Unauthorized` on every cold-boot `InventorySync.sync()` call. Investigation revealed the underlying type was over-broad; the fix is the architecture, not just the bug.

**Alternatives considered:**
- **In-place rework of `IdentityRepository`.** The original 2026-05-21 boot-gate decision was a partial fix in this direction (idempotency tightening + `loadTokens` timeout). It worked, but the underlying API kept conflating auth and profile concerns and forced every consumer to learn both. The split is the right shape; the boot-gate fix is superseded.
- **Single repo with two interfaces (`AuthRepository`/`ProfileRepository`) implemented by one class.** Considered to keep the wiring simpler. Rejected: the lifecycles really are different, and having one class implement both means every test fake has to stub both surfaces even for tests that only touch one.
- **Keep `IdentityState.Unknown` and only split the operations.** Rejected: the sentinel was the source of the UI flash bugs. Removing it forces consumers to declare the wait — which is good — and the suspend / replay-1 pattern is mature enough that the ergonomic cost is negligible.

**Status:** Locked. Supersedes the 2026-05-21 "Identity boot gate + network-client token wait" entry below — the `AuthTokenProvider` it added is gone (replaced by `AuthRepository.accessToken()`), the `IdentityCache` it leaned on is gone (replaced by `ProfileCache`), and the optimistic-cache-emit-with-idempotency-recheck contract it tightened is replaced by the simpler suspending `current()` contract.

---

---

## 2026-05-20 — Drop proactive smart-claim prompts; add app-store review prompts in their place

**Decision:** Stop pushing anonymous users to claim. Remove the five-trigger smart-claim-prompts table (first MP win, first Epic+ achievement, 5K balance, first shop visit, Level 10). Claim remains available passively (static Profile card; inline-only at the moments where claim is actually required — host a public room, add a friend). In parallel, *add* app-store review prompts that fire at the positive-moment triggers we just freed up (Epic+ achievement unlock, Level 10, session-end-net-positive), gated by install-age + session-count + 90-day-no-prompt + last-hand-not-a-bust. Use native APIs (SKStoreReviewController / Play In-App Review) only — no self-built rating dialog.

**Why drop claim prompts:** The original case for proactive claim prompts was anti-farming on the starter grant. That exploit is now closed by device-fingerprint deduplication (product-spec §6.1 — the spec doc was deleted in the 2026-06-24 docs restructure; history in git) — claim adds nothing to it. The remaining benefits of claim (durability, friends, leaderboards, public-room hosting) are *for the user*, not for us, and best-effort recovery via fingerprint + iCloud Keychain / Block Store already covers the common case. Pushing users to claim was begging for a conversion metric that wasn't load-bearing — a §10 brand-check violation.

**Why add review prompts:** Those same positive moments (Epic+ achievement unlock, Level 10, net-positive session end) are *legitimately* good moments to ask the user for a kind word — they're feeling good, they've invested, they're not interrupting anything. The native review APIs handle their own throttling (iOS 3/year, Android similar), so calling at the trigger moment doesn't mean prompting at the trigger moment — the OS decides. We add a 7-day install-age gate and a 90-day no-prompt gate as belt-and-suspenders, plus a "last-hand-not-a-bust" check so we never ask after a frustrating moment. App-store rating is load-bearing for ASO (v1-mvp.md §1 target: ≥ 4.3 — doc has since been deleted) in a way claim conversion never was.

**Alternatives considered:**
- **Keep some claim prompts, drop others** (e.g., keep only "first shop purchase" since cosmetic durability is the most concrete pitch). Rejected: any proactive prompt is begging when the underlying need is already met by fingerprinting. Cleaner to drop the surface entirely and let inline-when-required carry the message.
- **Build our own "rate Cards!" star-rating dialog.** Rejected: the App Store explicitly discourages it, self-built rating sheets erode trust, and the native APIs already handle the hard parts (throttling, dismissal, no-commitment).
- **Don't ask for reviews at all.** Rejected: ASO matters, the target rating was ≥ 4.3, and the native APIs are extremely low-cost / low-risk when gated to positive moments. Not asking would leave organic discovery on the table.

**What changed in the spec** *(product-spec.md has since been deleted — 2026-06-24 docs restructure; history in git)*:
- product-spec §2.1 — "Smart claim prompts fire at meaningful moments" callout removed; replaced with "Claim is opt-in, never pushed."
- product-spec §6.1 — "Smart claim prompts (not gating)" subsection rewritten as "Claim is opt-in (no proactive prompts)" with the rationale and the inline-only surface table.
- product-spec §2.6 — new section for review-prompt triggers, eligibility gate, never-trigger list.
- v1-mvp.md §1 — "anonymous → claimed conversion" downgraded from a ≥ 20% target to directional-only. (v1-mvp.md has since been deleted; tracked here for the historical record.)
- v1-mvp.md §2.2 + §2.6 — Phase 3 must-haves updated; new §2.6 for review prompts.

**Status:** Locked. The smart-claim-prompts design in the original 6.1 is superseded.

---

## 2026-05-20 — Reject "emojis cost chips" as a chip sink

**Decision:** Table-side emoji blasts stay free. The chip-sink instinct is right; emojis are the wrong lever.

**Why:** Emojis are the social-signal feature that makes the table feel alive. Adding cost suppresses usage, which suppresses the social experience, which suppresses the loss-aversion-on-busts loop that actually drives chip purchases. We'd lose more revenue (and a lot of brand warmth) than the sink would generate.

**Better chip sinks to prefer first:**
- MP buy-in / ante — the natural sink in a poker game.
- Tip the dealer at hand end (product-spec §4.1.5 — doc since deleted).
- Profile rename / title change cost.
- Custom avatar slots, name color, name glow, profile decorations (shop catalog §4.3).

**Alternatives considered:**
- **Flat cost per blast.** Rejected as above.
- **Tiered cost (rare emojis cost, common ones free).** Same suppression effect on the common-tier social signal that does the work.

**Revisit when:** the preferred sinks (especially MP buy-in) prove insufficient to keep chips a flowing resource. Default position remains: do not charge for emojis.

**Status:** Locked.

---

## 2026-05-18 — Identity pivot (REVERSED): back to Supabase Auth on the client

**This supersedes the 2026-05-18 "Identity pivot: server-managed device-keyed identity" entry above.** The earlier reversal of the original 2026-05-13 Supabase-Auth design was made on the assumption that "build claim flow ourselves" was a 2–3 day effort. On a more honest re-estimate (Sign in with Apple's email-privacy-relay handling, name-only-on-first-signin trap, server-side JWKS verification, Google Credential Manager flow on Android, account-linking edge cases), it's 5–7 days plus indefinite maintenance of edge cases.

Phase 3.1 (Apple/Google claim flow) was V1 scope (per the v1-mvp.md doc that existed at the time of this decision; the V1 scope frame then moved to product-spec §9, itself since deleted in the 2026-06-24 docs restructure), so this is a near-term cost, not a deferred one. Supabase Auth handles all of the above out of the box; `supabase-kt` (already in `libs.versions.toml`) is a first-class KMP client. The right call is to commit.

**The new shape:**

| Concern | Owner |
|---|---|
| Sign in (anonymous, Apple, Google, magic-link, etc.) | Supabase Auth, called via `supabase-kt` directly from the client |
| Token issuance + refresh | Supabase Auth (server-side, transparent to us) |
| Token storage on device | `supabase-kt`'s `SettingsSessionManager` (uses multiplatform-settings) |
| JWT validation on our server | `ktor-server-auth-jwt` configured with `SUPABASE_JWT_SECRET` (HS256) |
| Profile (display name, avatar emoji, future game state) | Our Postgres `profiles` table, FK to Supabase's `auth.users(id)` |
| Profile bootstrap on first sign-in | `GET /v1/me` is get-or-create: if no profile row, generate username + emoji and insert |
| Game logic, chips, XP, achievements, rooms | Our Ktor server (server-authoritative, unchanged) |
| Realtime game state during a hand | Our Ktor WebSockets (server-authoritative, unchanged) |

**What we throw away from the prior server-managed-identity design:**
- `JwtTokenService` + Auth0 java-jwt direct usage for minting
- `refresh_tokens` table (Supabase handles refresh)
- `identities` table (Supabase's `auth.users` replaces it)
- `device_links` table (Supabase has no device-keyed recovery; users either claim or accept the orphan-on-reinstall behavior)
- `POST /v1/identity` route (Supabase Auth replaces it)
- `POST /v1/auth/refresh` route (Supabase Auth replaces it)
- Client `IdentityAuthTokenProvider`, `TokenStoreImpl`, `IdentityApi`, `DeviceIdProvider` Kotlin Twin and its Android/iOS impls

**What we keep:**
- Postgres + Hikari + Exposed + Flyway + Testcontainers scaffolding (still valuable for our own data)
- `UsernameGenerator` + `EmojiAvatarGenerator` (called by `/v1/me` on first miss)
- `profiles` table — schema mostly unchanged; FK now points at `auth.users(id)` instead of our own `identities(id)`
- Server's Ktor structure (plugins, routes, observability, error envelope)
- `:libraries:identity` interface module (the contract stays clean; only the impl swaps)
- Onboarding feature module shell — VM now drives Supabase sign-in instead of `/v1/identity` POST
- Network client lazy-provider cycle fix (still correct for any auth backing)

**Anonymous → claim → sign-in conceptual model:**

Supabase splits these into two distinct operations and we expose both:

1. **Claim (link Apple/Google to current anonymous account):** `supabase.auth.linkIdentity(provider)`. Preserves all data (chips, XP, inventory). Fails if that OAuth identity already belongs to another `auth.users`.
2. **Sign in to existing (switch accounts):** `supabase.auth.signInWithOAuth(provider)` (or `signInWith(IDToken)` for native flows). Switches the session. **Anonymous data is orphaned** (no auto-merge) and eventually cleaned up by a TTL sweep.

V1 UX:
- Primary path: "Claim" button → `linkIdentity` → happy or "this OAuth is already on another account — sign in there? (you'll lose guest progress)" prompt.
- Secondary path: "I already have an account" → `signInWithOAuth` → explicit confirmation about losing guest data.

We do **not** build automatic account-merge logic for V1. Picking the "claim first" default for the common case is enough; users who explicitly switch accounts accept the trade-off.

**Trade-offs we accept by re-adopting Supabase:**

- Vendor lock to Supabase Auth + Postgres. Migration cost down the road = export `auth.users`, write a one-time script to map to a new identity provider, swap `supabase-kt` for whatever replaces it. ~1 week of work if we ever do it. Acceptable for V1.
- Anonymous accounts orphaned on `signInWithOAuth` to a pre-existing account. Sharp edge — V1 acceptable. Document a TTL cleanup task to delete anon-only `auth.users` >30 days inactive.
- Our server validates Supabase JWTs but doesn't talk to Supabase Admin API (yet). Future work might add admin operations (account deletion compliance, user lookup) — needs `SUPABASE_SERVICE_ROLE_KEY` server-side then.

**Required Supabase project configuration (manual steps):**
- Authentication → Settings → "Allow anonymous sign-ins" → **on**.
- Project Settings → API → record JWT secret (server `.env` → `SUPABASE_JWT_SECRET`).
- Project Settings → API → record `anon` public key (client config → `SUPABASE_ANON_KEY`).
- Phase 3.1: Authentication → Providers → enable Apple, Google with the respective OAuth credentials.

**Status:** Locked. The earlier "server-managed device-keyed identity" entry is now historical; reading the log top-to-bottom, the third entry on this topic (this one) is the live decision.

---

---

## 2026-05-18 — V1 client token storage: file-backed cache, not OS-encrypted

**Decision:** The client stores its server-issued JWT access + refresh token pair in the same `:libraries:storage` file-backed cache used for `AppData` (DataStore on Android, file-backed JSON on iOS). **Not** EncryptedSharedPreferences (Android) or Keychain (iOS).

**Why this is acceptable for V1:**
- All identities in V1 are anonymous. A stolen refresh token grants access to a device-bound anonymous account with no PII, no real money, only play chips. The user's recovery path is "reinstall, get a new identity."
- The OS already sandboxes app storage. A non-rooted/jailbroken device with screen-lock is well-defended; a rooted/jailbroken device with tokens in Keychain isn't materially safer than with them in DataStore.
- Android encrypted storage is straightforward; iOS Keychain from Kotlin requires either cinterop boilerplate or a Swift Twin. Landing both alongside the rest of V1 auth wasn't worth the time at this risk level.

**When this becomes unacceptable (and the trade-off resets):**
The moment Apple/Google "claim" lands (Phase 3.1). A claimed account binds to a real human and the refresh token unlocks their persistent state across devices — at that point a leaked token has user-visible consequences.

**Upgrade path:**
- Add `androidx.security:security-crypto` to `:libraries:identity:impl` androidMain deps. Bind an `EncryptedSharedPreferencesTokenStore` with `@ContributesBinding(replaces = [TokenStoreImpl::class])` in the same source set.
- Add an iOS Keychain wrapper. Easiest route: Swift Twin (per `docs/practices/swift-kotlin.md`) — interface stays in commonMain, Swift implements it and passes it into the DI graph via `IosAppComponentFactory.create(...)`. Bind with the same `replaces` annotation in iosMain.
- The interface (`com.dangerfield.cards.libraries.identity.TokenStore`) doesn't change; only the wiring does. Existing on-device tokens get re-written into the new store on the next refresh (or first run after the upgrade).

**Status:** Accepted V1 trade-off. Bump to OS-encrypted storage before the claim flow ships. **Update 2026-07-04:** the reset condition has fired — claim (Apple + Google) shipped in June, and the session still lives in supabase-kt's default `multiplatform-settings` store (plain SharedPreferences / NSUserDefaults). Tracked as **AUTH-16** in [todo.md](./todo.md). Note the upgrade path sketched above predates the Supabase re-adoption (the `TokenStore` types it names are gone); the modern fix is a custom `sessionManager` on the Auth plugin.

---

---

## 2026-05-14 — XP earning formula and local-only persistence (V1)

**Decision:** XP scales with **engagement intensity**, not outcome. The base formula (multiplayer rate, halved for bots) per finished hand is:

| Source | Amount | Condition |
|---|---|---|
| BASE | +10 | every finished hand (even a fold) |
| INVESTMENT | +1 per BB committed, capped at +20 | chips voluntarily put in this hand |
| SHOWDOWN | +10 | reached showdown |
| HAND_STRENGTH | (categoryOrdinal + 1) × 2 (1..20) | hand shown at showdown — winning or losing |

Bots earn 0.5× of every component (per the locked anti-farm rule). Multiplayer earns 1.0×. The `wonPot` flag is **not** an input — winning and losing the same hand at the same engagement level earn identical XP.

**Persistence:** `total_xp` is **server-authoritative as of Phase 3 Slice 1 (2026-06-14)** — Model 2 (optimistic-local + server-reconciled), mirroring the chips wallet. The client still computes XP per hand with `XpCalculator` and accrues it offline; `ProgressionRepositoryImpl.sync()` flushes the `xp_events` ledger to `POST /v1/me/progression/sync` and reconciles the local total to the server's value. So XP now survives reinstall / account switch / cross-device. **Lifetime hand counters** (`progression` singleton: handsPlayed/won/folded/…) are **still client-local** — they reset on a switch and aren't re-hydrated yet (see todo).

**Why this shape:**
- "Scale by hand strength / pot size" (per user) felt better than flat per-hand, but the engagement-intensity framing keeps the decoupling-from-outcome invariant intact.
- Hand-strength bonus at showdown rewards "showing up and showing a real hand" — naturally tracks skill and play depth without rewarding luck.
- Cap on investment (20 BB) prevents one all-in lottery hand from dwarfing a session of solid play.
- Local persistence now (vs. waiting for Phase 3) means the XP detail sheet ships with real, growing numbers; users see progress from day one. Migration to server is a one-shot import once auth lands.

**How to apply:**
- New XP sources must follow the rule: amount may depend on what the player did, never on what the opponent did or who won.
- When tuning numbers (everything in `XpCalculator.kt`), preserve order-of-magnitude — a normal hand should feel like "10-30 XP" against bots and "20-60 XP" in multiplayer.
- Level thresholds remain deferred (per the previous entry) until we have a session's worth of real XP numbers to anchor them.

**Status:** Locked for V1. **Phase 3 Slices 1 + 2 landed (2026-06-14):** `total_xp` (Slice 1) and the achievement *earned set* (Slice 2) are server-authoritative (Model 2); the XP formula + achievement criteria stay client-side and `level` stays derived from `total_xp`. Remaining: graduate the lifetime hand counters + achievement progress counters, and claim-time backfill (Slice 3) — see `todo.md`.

---

---

## 2026-05-14 — Chips, rank, XP are three separate concepts

**Decision:** Cards has three independent progression/value axes. They do not collapse into each other.

1. **Chips** — buy-in currency.
   - **Multiplayer:** persistent, "sacred" (no random refills, no daily free spins). Going broke = rate-limited recovery grant (one-shot, server-enforced) per the V1 plan's bottom-out path.
   - **Bot mode:** practice chips. Auto-rebuy to `startingStack` between hands if the seat busted (already shipped — `LocalBotsSession.lastSeatsForRotation`). No real consequence.

2. **Rank** — Elo-style skill rating, **multiplayer-only**.
   - Bots don't move rank because they're static heuristics — beating Jane 100 times says nothing about your skill vs humans.
   - Floors around 800 (real Elo behavior), can't hit zero.
   - For V1 (bots only), displayed but with a "Play multiplayer to earn rank" hint. Doesn't change.

3. **XP** — lifetime engagement counter, **both modes**.
   - Always goes up. Cannot decrease, cannot bottom out.
   - Bot games earn at **0.5×** the multiplayer rate (per the V1 plan's anti-farm rule).
   - Drives level progression / achievements / cosmetics unlocks (future).
   - This is the "I made progress" signal every session, decoupled from win/lose.

**Why:** Every successful poker app (Offsuit, PokerStars, even Zynga) separates these. Collapsing them — e.g., "rank = chips won" — creates the "I went broke, I'm starting over" experience that kills new-player retention. Three lanes means a beginner can lose chips, see XP go up, see rank stay flat, and still feel like they're moving forward.

**How to apply:**
- Treat any new feature touching one axis as not touching the others. A chip refill doesn't affect XP. An XP bonus doesn't move rank. Etc.
- When rendering profile/home: show all three, never merge into one summary metric.
- For Phase 3 persistence: the `xp_events` ledger from the V1 plan covers XP. Chips and rank go in their own server-authoritative tables.
- For V1, surface XP as a number; level/progress-bar UI lands when we have enough data to know what XP thresholds feel right.

**Status:** Locked for V1.

---

---

## 2026-05-13 — Client/server boundary: server-first, auth is the only exception

**Decision:** The mobile client talks directly to **Supabase Auth** for the Apple/Google sign-in flow and that's it. Everything else — profile, leaderboards, room create/join, game state, chips, XP, connections, AppConfig, the future hand history and notifications register — goes through the Kotlin Ktor server. The server is the only thing that talks to Postgres.

The split, concretely:

| Concern | Path |
|---|---|
| Sign in with Apple / Google | Client → Supabase Auth (direct, via the OS OAuth flow) |
| JWT validation | Server validates the Supabase JWT on every HTTPS request and every WS connect |
| Profile read/write, leaderboards, rooms, XP, connections, app config | Client → Ktor server (HTTPS, JWT-authenticated) |
| Realtime game state during a hand | Client ↔ Ktor WebSocket (one channel per room) |
| Postgres queries | Server only, via direct DB connection with the service role key |
| Supabase Realtime | Not used in V1. Possible future use for low-stakes row subscriptions (e.g. "friend started a game") but never for in-hand game state. |

**Why server-first:**

1. **Poker forces it.** Shuffle, deal, betting validation, hand evaluation must be server-authoritative. Half the code already goes through the server — making the rest match removes the split brain.
2. **Schema changes don't break clients.** When a column is added or renamed, the server adapts the response shape; old binaries keep working. Direct-to-Supabase welds each client version to its schema version, which is painful with App Store / Play Store update lag.
3. **Business logic stays in one place.** "Award XP on hand completion" touches multiple tables and must be atomic. One Ktor transaction is bulletproof; three Supabase calls from a phone are fragile (network drops, partial writes).
4. **Anti-abuse and provably-fair primitives need server enforcement.** Rate limiting, intent nonces, the shuffle commit-reveal protocol, turn-timer enforcement — none of these can be done with RLS alone.
5. **Migration optionality.** If we ever outgrow Supabase, swapping the server's DB driver is one PR. Direct-to-Supabase means every shipped client has `supabase.co` welded in.

**Why realtime through Ktor, not Supabase Realtime:**

Supabase Realtime broadcasts row changes. The game state during a hand lives in an in-memory coroutine on the server, not in a Postgres row — persisting every state transition just to fan it out would be wasteful and would expose intermediate states (the moment hole cards are dealt, they'd briefly land in a row before any RLS could hide them). Server-driven turn timers need code, not row triggers. Ktor WebSockets give us a per-room channel where the server publishes JSON diffs when it wants to. Standard pattern.

**Supabase's role in this architecture:**

We're using Supabase for:
- Managed Postgres (hosted DB, point-in-time recovery, backups)
- Auth (JWT issuer + Apple/Google OAuth dance)
- Maybe Storage later for avatar uploads

We're not using:
- PostgREST (the auto-generated REST API)
- Supabase SDK on the server (we connect to Postgres directly)
- Realtime (we have our own WS)

This makes Supabase feel like "managed Postgres + hosted auth" rather than "all-in-one backend," which is the right framing for an app with its own game-logic server.

**How to apply:**

- When adding a new client capability, the default answer is "add a Ktor endpoint" not "query Supabase directly from the client."
- The one exception is the Sign-in-with-Apple / Google flow, which has to happen client-side because Apple/Google's OAuth UI runs on-device.
- New realtime features inside a room (emotes, chat, sit-out signals) go through the existing per-room WS channel, not a new Supabase subscription.
- Realtime features *outside* a room (notifications about friends, leaderboard ticks) can use Supabase Realtime if it's the simpler answer, but evaluate per case.

**Status:** Locked.

---

---

## 2026-05-13 — "Sacred chips" principle

**Decision:** Going broke is a real consequence. No random refills, no daily login bonuses, no free spins. Bottom-out path: claimed users can request a one-time recovery grant if balance hits zero, server-rate-limited (e.g. once per 24h, decaying amount). Anonymous users get their initial float and that's it until they claim.

**Why:** Borrowed from Offsuit reviewer feedback ("chips feel sacred" cited as a positive). Reinforces seriousness of the game without monetization gates.

**Status:** Locked for V1.


---

## 2026-06-24 — Bounded room-socket reconnect on a healthy-session signal

**Decision:** `ReconnectingRoomSocket` only resets its reconnect-attempt counter once a connected session is *healthy* — defined as having delivered at least one decodable frame. A session that completes the WS handshake but drops before delivering any frame no longer resets the counter, so repeated instant drops climb the exponential backoff. After `MAX_RECONNECT_ATTEMPTS` (6) consecutive frame-less drops the socket gives up with a new terminal `ClosedReason.ReconnectFailed` instead of looping forever.

**Why:** A half-open server socket (left behind after the sole other human leaves a 2-player room) accepts the handshake and immediately drops it. The old loop reset `attempt` on every handshake success, so it spun connect→drop→reconnect at the 250ms floor with `attempt` stuck at 1 — an unbounded storm the user could only escape by mashing Back (CARDS-37). "Delivered a frame" is a clean, test-friendly health signal: a half-open socket delivers nothing, while a genuinely-working connection that blips once after minutes of play still resets fairly.

**Alternatives considered:** (1) Time-based health (session up ≥ N ms) — needs an injected clock and is harder to virtualize in the existing `StandardTestDispatcher` tests. (2) Capping the handshake-retry path too — left out to preserve the existing `consecutiveFailures_incrementAttemptCounter` contract and because the reported failure is the connected-then-dropped path, not 5xx handshakes. Deferred as a follow-up.

**Status:** Shipped.


---

## 2026-06-24 — Reconcile the wallet on leaving a real-chip MP table (MP-7)

**Decision:** `PlayPokerViewModel` calls `chipsRepository.sync()` immediately after `session.leave()` whenever the table is a real-chip multiplayer table (`XpMode.MULTIPLAYER`). Both leave paths (`LeaveTable`, `LeaveGameFromBust`) route through one `leaveAndReconcileWallet()` helper on `appScope` so the sync outlives the screen pop. Solo/bots practice tables skip the sync (no escrow moves).

**Why:** The server already cashes a leaver's final table stack back to the wallet on leave (`DefaultTableSessionService.cashOut`, keyed/idempotent — proven by `ChipEconomyPlayTest`). The bug was purely client-side reflection: the local wallet is a write-through cache that only hydrates on cold boot / warm foreground, so a player who won a pot and left saw their balance unchanged until the next foreground, when a partial resync surfaced a confusing phantom delta (CARDS-3C: "won 500, wallet unchanged, then +100 later"). Forcing a sync on leave lands the credited stack right away.

**Alternatives considered:** (1) Route the server's `CashedOut(refunded, balanceAfter)` to the client over the socket and apply it optimistically — richer (enables a credited-amount toast, MP-6 part 1) but needs new protocol plumbing on a fan-out leave path with no request/response tie; deferred. (2) Push a server-authoritative balance frame on every leave — same plumbing cost. The sync-on-leave is the minimal correct fix; the existing single-flight mutex on `sync()` collapses any overlap with the foreground resync.

**Status:** Shipped.

## 2026-06-25 — Server-authoritative player stats, streak carried as a snapshot (PROG-1)

**Decision:** Hand counters (played / won / folded / lost-at-showdown / bot hands), the no-bust streak (current + best), and the per-bot win map graduate to the server as a `user_player_stats` aggregate plus an append-only `player_stat_events` ledger keyed `(user_id, idempotency_key)` — the same Model-2 shape as play_style (V69) and wallets (V6). `GET /v1/me/player-stats` reads the snapshot; `POST /v1/me/player-stats/sync` flushes a batch of per-hand events the server folds idempotently (the `player-stats` namespace deconflicts from the pre-existing `/v1/me/stats` lifetime-opponents read). The summable counters and the per-bot map accumulate; the **streak is carried as a snapshot** on each event (the client's running no-bust streak after that hand), and the aggregate takes the latest applied value as `current_no_bust_streak` and the running max as `best_no_bust_streak`.

**Why:** Stats were device-only, so account-switch / reinstall reset them and the stats screen + achievement progress bars read wrong on a second device. Making stats the source of truth lets achievements become predicates over them — a new achievement points at an existing counter with no data migration. Streaks are order-dependent, so they can't be re-derived by summing a ledger the way the counters can; sending the client's post-hand streak value and folding it latest-current / max-best keeps the ledger idempotent without the server replaying hand order.

**Alternatives considered:** (1) Recompute the streak server-side from the ordered ledger — correct but forces the server to read+replay the whole event history on each sync and makes a mid-batch replay non-trivial; rejected for the snapshot fold. (2) Store per-bot wins as their own ledger/table rather than a JSONB map on the aggregate — heavier for a handful of keys; the map mirrors how small per-key state rides on an aggregate row elsewhere.

**Status:** Server slice shipped (table + migration V72 + domain/Postgres repo + DTO + routes + DI + delete cascade + tests). Client half (write-ahead-cache repo mirroring `PlayStyleRepositoryImpl`, then re-point the stats screen + achievement predicates) remains under PROG-1.


## 2026-06-25 — Persist last-known stacks in the session snapshot, not the table_sessions row (MP-13)

**Decision:** The per-player `lastKnownStacks` map a `GameSession` keeps (each player's stack as of the last hand they were seated for, retained after they bust + are dropped) is now persisted in the `room_sessions` snapshot — a new `last_known_stacks_jsonb` column (V74) carried on `SessionSnapshot` alongside the serialized `GameState`. The boot recovery sweep (`DefaultTableSessionRecoverySweep`) reads the live seat's stack first, then falls back to this persisted map before refunding the full escrow, so a busted-and-dropped player swept after a crash is cashed out their real 0 rather than minted their whole stake.

**Why:** The live-leave mint was already fixed in-memory via `GameSession.lastKnownStack`, but that map died with the process. After a crash the sweep rehydrated only the snapshot's `GameState`, which has no seat for a busted-dropped player, and fell through to a full-escrow refund — the same mint, one path over. The snapshot is the natural home: it is already written per-mutation inside the per-session mutex, already hydrated on restart, and the sweep already reads it via `snapshots.readByCode`. Co-locating the last-known stacks with the state means one durable write keeps both in step and the sweep needs no second lookup.

**Alternatives considered:** (1) Persist the last-known stack on the `table_sessions` row instead. Rejected: that row is per-user lifecycle bookkeeping written on sit-down / status flips, not on every hand boundary, so it would need a new write path on the gameplay hot loop and a second source of truth for "what did this player walk away with"; the snapshot already mutates at exactly the right cadence. (2) Recompute the stack from the snapshot's event history — there is no durable event log (snapshot-only state, see 2026-05-29), so nothing to replay. Round-trip safety: the column is `NOT NULL DEFAULT '{}'`, so pre-V74 rows and any insert that omits it read back as an empty map (no recorded stack → the sweep falls back to a full refund, the prior behaviour) rather than failing to deserialize.

**Status:** Shipped. Red/green proven by `Mp13CrashRecoveryConservationTest` (harness `restart()` + sweep); `PostgresSessionSnapshotStoreTest` pins the round-trip + the pre-V74 empty-map fallback.

## 2026-06-26 — Lazily seed a config flag from its manifest default when a rule first attaches (ENG-4)

**Decision:** The admin rule write (`PUT /v1/admin/config/rules/{id}`) now calls `seedFlagFromManifestIfMissing` before the rule upsert: if the flag has no DB row, the server materializes one from the flag's shipped manifest default and only then attaches the rule. A flag with neither a DB row nor a manifest entry still returns 409 `unknown_flag`. The admin client no longer mints a base override (`upsertFlag(seed)`) as a side effect of adding a rule, and `launchOp` now reloads on both success and failure so a rejected write can't leave the flag list looking stale.

**Why:** The targeting-rule write has an FK on the flag row. The client worked around it by writing a DB base override from the in-code default before every rule add, so a failed rule-add could leave behind a base override the operator never intended, and the resolve layers showed a "base value" the operator never set. Moving the seed server-side, sourced from the authoritative manifest default rather than a client guess, keeps the FK invariant intact while making "add a rule to a flag that only ships in code" a single honest operation. Reloading on failure fixes the separate no-render bug where a 400/409 set an error banner but skipped the refresh, so the just-added rule looked like it silently vanished.

**Alternatives considered:** (1) Relax the FK so rules can reference a flag with no row. Rejected: it splits the source of truth (a rule pointing at a non-existent flag) and complicates resolve, which already unions DB + manifest. (2) Keep minting the base override but do it server-side. Rejected: that still presents the shipped default as an operator-set "base value", which is exactly the confusion ENG-5 is chartered to remove; seeding silently from the manifest default (an audit `create_flag` row records it) is the honest middle ground.

**Status:** Shipped. Red/green proven by `ConfigAdminRoutesTest.upsertRule_forManifestOnlyFlag_seedsBaseFromManifestDefault_andSucceeds` (was 409, now 200 + seeded base) and `upsertRule_forUnknownFlagWithNoManifest_is409` (the no-manifest path stays an honest conflict).

## 2026-06-27 — Rewrite Terms/Privacy to professional coverage; 18+ age gate, arbitration + class-action waiver (AUTH-7)

**Decision:** Rewrote `pages/terms.html` and `pages/privacy.html` from the thin starter set to the full coverage a simulated-gambling app is expected to carry, in Downcard's plain-English voice. Terms now cover: amusement/non-gambling disclaimer, an **18+ age gate**, virtual-currency disclaimer, app-store terms (Apple as third-party beneficiary), third-party services (Supabase/Fly/Sentry named), suspension/termination + survival, warranty disclaimer, limitation of liability, indemnification, a **binding-arbitration + class-action-waiver** dispute-resolution block (AAA Consumer Rules, NY seat, small-claims + IP carve-outs, **30-day opt-out**, one-year limit), NY governing law, and severability/entire-agreement. Privacy adds: a sub-processor disclosure ("Where your data lives" — Supabase + Fly, US), retention, security, and a "Your privacy rights" section (access/correct/delete/export/object + regulator complaint). Children raised from under-13 to under-18 to match the age gate. `LegalUrls.LEGAL_VERSION` bumped `1 → 2` so the re-accept gate fires.

**Why:** The owner asked to match competitor (Offsuit) coverage. We deliberately did **not** copy Offsuit's text — it's copyrighted and describes their entity, practices, and third parties (ads, offer walls, real-money, social-login friend import) that Downcard doesn't have, which would make Downcard's docs factually false. Instead we wrote Downcard-accurate prose covering the same professional topics, and dropped the inapplicable Offsuit clauses (advertising/offer-wall, subscriptions, multi-state privacy appendix). 18+ chosen over the current 13 because the app carries a poker theme and a "simulated gambling" store rating; aligning the age gate sidesteps COPPA/teen-data scope.

**Alternatives considered:** (1) Copy Offsuit verbatim — rejected (copyright + factual inaccuracy, above). (2) Keep the NY-courts-only model — owner chose to add arbitration. (3) Keep 13+ — out of step with the poker rating.

**Caveat / follow-up:** The arbitration + class-action-waiver clause is the most legally consequential part and its enforceability turns on drafting; this is a reasonable standard version, **not** a substitute for counsel. A lawyer review before launch is tracked in `developer-todo.md`.

**Status:** Shipped (docs + version bump). No automated test — static legal copy; `LEGAL_VERSION` consumers all read the constant, so the onboarding re-consent tests stay green.

## 2026-06-27 — Move both Supabase projects onto new publishable/secret API keys; disable legacy JWT keys

**Decision:** Both projects (dev `yuqrfhdoejonclgbixlw`, prod `kzohlyvmnnvyabspzpbb`) now use Supabase's new API keys: the client ships the `sb_publishable_` key (`AppEnvironment.supabasePublishableKey`, replacing the legacy `anon` JWT) and the server reads an `sb_secret_` key as `SUPABASE_SERVICE_ROLE_KEY` (Fly secret). Legacy `anon` + `service_role` JWT keys are **disabled** on both projects. Prod's Fly app `cards-server-prod` now has its secrets set (DATABASE_URL/SUPABASE_URL/SUPABASE_SERVICE_ROLE_KEY/ADMIN_API_TOKEN) and is running.

**Correction — `fly secrets set` only restarts the existing image; it does NOT deploy new code.** Both `cards-server-dev` and `cards-server-prod` deploy on push to `main`, and the unslop copy (V78/V79) + all of PR #80's work is on `develop`. So neither live DB has the unslopped copy yet — verified via `GET /v1/products` on both (old copy still served). ENG-3 ships when PR #80 merges to main and both servers redeploy (Flyway then applies V78/V79).

**Why:** A prod `service_role` JWT was exposed during setup. Both projects had already migrated to JWT Signing Keys, so the legacy secret was verify-only and not simply regenerable; the clean fix Supabase recommends is moving to publishable/secret keys and disabling the legacy ones. Doing it on **both** envs (not prod-only) keeps dev/prod config identical — the operator's explicit requirement, and the right call to avoid drift. The server never decodes its key (it's a bearer credential to the Auth admin API), and user-token verification is via JWKS, so `sb_secret_` is a drop-in and disabling legacy keys breaks nothing.

**Status:** Shipped. Client change in `refactor(identity): move client onto Supabase publishable keys`. Server/key changes are infra (Fly secrets + Supabase dashboard), not source. Follow-up: the leaked legacy key is dead (disabled), so no rotation debt remains.

## 2026-06-27 — Complete Google sign-in via the browser OAuth flow (not the dormant native id-token path)

**Decision:** Google sign-in now logs a user in end-to-end through supabase-kt's **browser OAuth flow**. Three pieces:

- **Redirect config.** The Auth plugin is configured with `scheme = "cards"` / `host = "login-callback"` (`SupabaseClientFactory`), so supabase-kt builds `cards://login-callback` as the `redirect_to` it sends Google on Android + Apple targets. Flow stays the default **IMPLICIT** — the session tokens come back in the URL fragment.
- **Return trip.** The provider redirects to `cards://login-callback#access_token=…`. On iOS `.onOpenURL` already feeds every URL into the common `DeepLinkBridge`; on Android `MainActivity.onCreate`/`onNewIntent` now forward **only** the auth-callback URL into the same bridge (other `cards://` links keep going straight to NavHost, which reads `Activity.intent.data` itself). `App.kt`'s bridge collector tests each URL with `AuthRepository.isOAuthRedirect` and routes a match to `AuthRepository.completeOAuthRedirect(url)` instead of `navController.handleDeepLink`. That calls supabase-kt's stable common API `Auth.parseSessionFromUrl(url)` + `Auth.importSession(session, source = SessionSource.External)`, then emits the new `AuthState.Authenticated`.
- **Button + flag.** A reusable `GoogleSignInButton` (four-colour Google "G" + label, white brand surface) lands in `:libraries:ui` and replaces the plain `Button` on the sign-in + claim screens. `GoogleSignInEnabled` now defaults `true` (matching Apple).

**Why browser OAuth, not native id-token:** the gateway already has a **dormant** native Google path (`signInWithGoogleIdToken`) wired ahead of a token source — but there's no Credential Manager (Android) / GIDSignIn (iOS) integration producing that id token, so it can't sign anyone in today. The browser flow needs no Google SDK, no per-platform client-id plumbing, and reuses the deep-link bridge that already exists for verify-email. It's the smallest correct path to a working Google login. Native Google (one-tap, no browser bounce) stays a future polish — when a token source is added, `signInWithGoogleIdToken` is already there to call.

**Why `parseSessionFromUrl` + `importSession` over the platform `handleDeeplinks`:** supabase-kt's `SupabaseClient.handleDeeplinks` is platform-specific (`Intent` on Android, `NSURL` on Apple) and would force an `iosMain` source set into `:libraries:identity:impl` (which has none today). `parseSessionFromUrl`/`importSession` are **public, common, stable** API doing exactly what the platform helpers do internally for the IMPLICIT flow — so the whole return trip lives in `commonMain`, behind the existing `SupabaseAuthGateway` seam, and is unit-testable with the in-memory fake.

**Alternatives considered:** (1) Native Google id-token now — rejected: no token source exists, so it's not actually a login (above). (2) Switch to the PKCE flow — rejected for V1: PKCE adds a code-exchange round trip + a code-verifier cache and buys little for a mobile custom-scheme redirect; IMPLICIT is supabase-kt's default and the platform `handleDeeplinks` support it directly. Revisit if we ever serve the callback over https App Links. (3) Let the callback flow through NavHost as a route — rejected: it maps to no destination, so NavHost silently drops it and the session never lands; intercepting before the navigator is the correct seam.

**Caveat:** This is auth code that **cannot be device-tested in CI** — the OAuth round trip needs a real browser + the Supabase project configured (Google provider enabled + `cards://login-callback` added to the redirect-URL allowlist). Manual device QA is tracked in `developer-todo.md`; the dashboard config is a new item there too.

**Status:** Shipped (client). Unit tests in `SupabaseAuthRepositoryImplTest` pin the import-and-emit success path, the no-token → Cancelled path, and `isOAuthRedirect` matching. Android `assembleDebug`, iOS `compileKotlinIosSimulatorArm64`, and `detekt` (0 findings) all green. End-to-end sign-in unverified until device QA + Supabase dashboard config.

> **Superseded 2026-06-27 by "Google browser-OAuth: suspend until the redirect resolves" below.** The flow above emitted auth state right after the browser opened, which was wrong: the session hasn't arrived yet, and a link (claim) redirect carries no session to import at all. The entry below is the current design.

## 2026-06-27 — Google browser-OAuth: suspend until the redirect resolves (link ≠ sign-in)

**Problem (verified on device):** With supabase-kt 3.6.0, `auth.signInWith(OAuth)` and `auth.linkIdentity(OAuth)` only **open the system browser** and return immediately — the session arrives ~seconds later as the `cards://login-callback` deep link. The previous flow (entry above) called the gateway then immediately emitted auth state. So `signInWithOAuth`/`linkOAuthIdentity` reported a result **before the redirect**. For a claim that emitted the still-anonymous session → "Success" while nothing had changed → the "Save your progress" banner persisted. Then the deep-link handler `completeOAuthRedirect` ran assuming a sign-in session lived in the URL; a **link** redirect carries none → `emitAuthenticatedFromGatewayLocked called without a session` (`IllegalStateException`, caught + logged).

**Decision:** Make the OAuth start calls **suspend until the redirect resolves**, so the existing outcome-driven UI works unchanged (`ClaimAccountViewModel`/`OnboardingViewModel` still `when` on the return value and spin a spinner during the call). Mechanics in `SupabaseAuthRepositoryImpl`:

- **One in-flight handle.** A nullable `PendingOAuth` = a `CompletableDeferred<OAuthRedirectResult>` + the **flow kind** (`SignIn` vs `Link`). Starting a new attempt while one is pending resolves the old as `Cancelled` (so its starter unsuspends) and replaces it.
- **Start (two phases).** `signInWithOAuth` / `linkOAuthIdentity` launch the browser via the gateway **under the mutex**, park the handle, then `await` the deferred **outside the mutex** — awaiting under the lock would deadlock `completeOAuthRedirect`, which needs the same mutex to resolve us. No premature emit. A launch failure (browser couldn't open) returns the mapped failure without parking.
- **Finish.** `completeOAuthRedirect(url)` reads the pending kind:
  - **SignIn** → `parseSessionFromUrl(url)` + `importSession(session, source = External)` → emit `Authenticated` → resolve `Success`.
  - **Link** → do **not** parse the URL (no session in a link redirect). Call `Auth.retrieveUserForCurrentSession(updateSession = true)` (supabase-kt 3.6.0, `Auth.kt:357`) to refresh the now-linked, non-anonymous user into the current session → emit `Authenticated` (`isAnonymous = false`) → resolve `Success`.
  - **Any failure** → never throw out of this path; resolve the handle with the mapped failure (reusing the existing `RestException`/`HttpRequestException`/cancel mapping) and leave auth state as-is. A redirect with **no pending handle** (stray/duplicate) no-ops safely.
- **Backstop.** A generous (3-minute) timeout on the await; on expiry resolve `Cancelled` and clear the handle. Clean "user backed out of the browser" cancellation (the app foregrounds with no redirect) is a **known rough edge** — we deliberately did NOT build foreground-race detection now; the timeout is the backstop and the cancel UX will be refined during device testing.

**Why suspend-the-call over a fire-and-forget event:** the VMs are already outcome-driven (`when (authRepository.linkOAuthIdentity(...))`), and the spinner is gated on the call being in flight. Keeping the call suspended until the real result lands means **zero VM changes** and the spinner naturally covers the browser round trip.

**Why a single handle, not a queue:** there is exactly one OAuth attempt a user can have in flight (one browser, one consent screen). A second start means the user abandoned the first — resolving the old as `Cancelled` is the correct, simplest model.

**Gateway seam:** added `refreshLinkedUser()` to `SupabaseAuthGateway` (`RealSupabaseAuthGateway` → `retrieveUserForCurrentSession(updateSession = true)`); the in-memory fake implements it so the link path is unit-testable. `completeOAuthRedirect` on the gateway stays sign-in-only (parse + import).

**APIs used (re-verified against `auth-kt-3.6.0-sources.jar`, commonMain):** `Auth.retrieveUserForCurrentSession(updateSession: Boolean = false): UserInfo` (`Auth.kt:357`), `Auth.parseSessionFromUrl(url): UserSession` (`AuthExtensions.kt:54`), `Auth.importSession(session, autoRefresh, source)` (`Auth.kt:372`), `Auth.refreshCurrentSession()` (`Auth.kt:404`), `Auth.currentSessionOrNull()` (`Auth.kt:493`), `Auth.currentUserOrNull()` (`Auth.kt:501`). All match the spec.

**Flag:** `GoogleSignInEnabled.default` flipped back to `true` (matching Apple). Still gated on the Supabase Google provider + `cards://login-callback` redirect URL being configured, and a device test — tracked in `developer-todo.md`.

**Status:** Shipped (client). `SupabaseAuthRepositoryImplTest` pins: the regression (link must NOT return Success while anonymous — it suspends, resolves only when the redirect runs), link happy path (refresh-user → `isAnonymous = false` → `LinkIdentityOutcome.Success`), sign-in happy path (parse+import → `SignInOutcome.Success`), redirect-failure resolving the suspended call without throwing, and the no-pending-handle no-op. Android `assembleDebug`, iOS `compileKotlinIosSimulatorArm64`, and `detekt` green. End-to-end sign-in / claim / cancel unverified until device QA + Supabase dashboard config.

## 2026-06-27 — Anchor the level-up celebration watermark in the granter, not the Home gate (PROG-3)

**Problem (from feedback CARDS-4V):** A solo hand ended, the level-3 reward + two achievements were granted (logged), but no celebration ever presented — the user dropped straight to Home, "randomly not seeing the level up screens." The grant was correct; the fanfare was silently dropped, intermittently.

**Root cause:** Two independent observers of `observeProgression()` both seed a watermark on their first emission with the `0`-sentinel rule. `LevelUpRewardGranter` (an `AutoInit`, runs at app start) seeds `highestLevelRewarded`; `HomeViewModel`'s celebration gate seeds `lastCelebratedLevel`. The gate's first emission can arrive *after* a level-up earned this session — when it does, its `watermark == 0` branch seeds `lastCelebratedLevel` straight to the new level **without celebrating**, eating the moment. The granter wins the race in practice (it observes earlier), which is why the reward fired but the celebration didn't. The intermittency is exactly this ordering race.

**Decision:** Make the granter — the single, early, deterministic observer that reliably sees the pre-session level before the user touches a screen — anchor **both** watermarks in its seed step: when `highestLevelRewarded == 0`, it also seeds `lastCelebratedLevel` to the current level *if that one is also unset*. After this, any genuine level-up this session is `currentLevel > lastCelebratedLevel` on Home → celebration fires. The gate's own `watermark == 0` branch stays as a fallback for the unlikely case it observes before the granter.

**Alternatives rejected:** (1) Collapse the two watermarks into one — rejected: they are deliberately separate (a reward grant must be exactly-once regardless of whether the celebration was seen/dismissed; see the 2026-06-06 level-up addendum). (2) Seed the gate to a fixed level-1 floor — rejected: it would re-blast a celebration when switching into an already-leveled account (the `levelUp_switchIntoLeveledAccount_seedsToCurrent_noCelebration` invariant). Anchoring in the granter preserves both: the granter still seeds to the *current* level on a switch (no celebration), and only a real mid-session crossing surfaces one.

**Telemetry:** Added a once-per-decision Info line at the gate (`level-up celebration enqueued for level N` vs `skipped because watermark unset`), gated by the gate's `distinctUntilChanged()` so it never fires per-emission — closing the gap the case flagged (the log showed the grant but nothing about the celebration branch).

**Status:** Shipped (client). Pinned by `LevelUpRewardGranterTest` (seed anchors both watermarks; an already-seeded celebration watermark is left untouched) and `HomeViewModelTest` (gate first observing the leveled-up XP with the watermark pre-anchored still celebrates). Android `assembleDebug` green.

## 2026-06-27 — "Instant" game speed now snaps the per-action table transitions too

> **Superseded 2026-06-29 by "Game speed paces bot thinking only" below.** The whole animation-scaling layer this entry extends (`TableTempo`, the Instant tier, the real-chips override) was removed two days later.

**Problem:** With Game speed = Instant, hands against bots still felt slow even though bots no longer paused to "think." The card deal/reveal animations and bot think-time already honored the setting, but the per-action UI transitions did not.

**Root cause:** Several gameplay `AnimatedVisibility` blocks used hardcoded `tween(...)` durations that never consulted `TableTempo` — so they played at full length regardless of Game speed. The visible offenders, all on the per-turn critical path: the human action bar slide in/out (`TableActionBar`, ~260/220ms every turn), each bot's last-action label slide in/out (`OpponentsRow.LastActionOverlay`, every bot action), the acting-indicator chevron fade (`OpponentsRow.ChevronOverlay`), and the opponent stack on bust (`OpponentSeat`). Card deals (`BoardArea`/`PlayerArea`) and bot think (`BotTiming` via `gameSpeedProvider`) were already scaled, which is exactly why thinking felt instant but the table still moved.

**Decision:** Route those transition durations through the existing `TableTempo.duration(ms)` helper (the same pattern the card-deal animations already use): Normal unchanged (×1.0), Fast halved (×0.5), Instant → 0 (snap). No new API — just wrapped the literals.

**Deliberately left unscaled:** the bot think-time **floor** (`BotTiming.MIN_THINK_FAST_MS = 250ms`) — the user reported think-time already feels fine, and a hard floor below which bot moves "read as a glitch" is intended. Also untouched: reward/celebration flourishes (`HandRewardParticleOverlay`, `AchievementCelebrationSheet`), the ambient your-turn pulse, and non-gameplay surfaces (emoji tray, tutorial) — those are reward/ambient moments, not "waiting for the table," and whether Instant should strip them is a separate product call.

**Status:** Superseded by 2026-06-29 (below).

## 2026-06-29 — Game speed paces bot thinking only; animation scaling (`TableTempo`) removed

**Decision:** `GameSpeed` now carries only `botThinkScale` (Normal ×1.0, Fast ×0.5). The `TableTempo` / `LocalTableTempo` animation-scaling layer, the `animationScale` field, the **Instant tier**, and the `effectiveTableSpeed` real-chips override are all deleted. Deal/reveal/settle animations always play at their calibrated base pace; animation durations are inlined at their base values.

**Why:** Game speed used to retune two things at once — bot think time and the cosmetic animations — and scaling the animations made faster tiers feel jerky for no real benefit: the animations are what make play feel smooth. The only thing players actually wanted to trim was the bots' deliberation pause. With animations untouched, Instant collapses into Fast, and the real-chips override (which existed solely to protect animations in real games, GAME-8) loses its reason to exist.

**Supersedes:** the 2026-06-27 "Instant game speed snaps per-action table transitions" entry above. Commit `a1b00656`; the GAME-6 mid-deal-freeze regression test was removed with the tempo flip that caused it.

**Status:** Locked.

## 2026-06-27 — Google OAuth link/claim must refreshSession, not hydrateCurrentUser

**Problem (device):** From an anonymous account, Profile → "Sign in" → continue with Google → deep-link back to the app did nothing: no error, but the user stayed anonymous (the "Save your progress" banner persisted). Logs showed the link reporting success while the session was unchanged: `completeOAuthRedirect: finishing pending Link flow` → `Emitted Authenticated(userId=ca90116c…, isAnonymous=true, hasEmail=false)` → `Link Success` → `Claimed`. Same user id, still anonymous, no email.

**Root cause:** `SupabaseAuthRepositoryImpl.completeOAuthRedirect`'s `Link` branch finished the claim with `gateway.hydrateCurrentUser()` (`retrieveUserForCurrentSession(updateSession = true)` — a GET /user). On device that does NOT fold the just-linked identity into the local session: the cached access token still carries `is_anonymous=true`, and our anonymity is derived from `user.identities.isEmpty()` (`RealSupabaseAuthGateway.currentSession`), which GET /user with the still-anon token doesn't repopulate. So the emitted state stayed anonymous. The **native Apple link path already discovered this** and uses `gateway.refreshSession()` (a refresh-token exchange that mints a fresh JWT + user with the linked identity) — the OAuth link path was simply never switched over. (This corrects the earlier 2026-06-27 OAuth-link decision, which assumed a user-refresh was sufficient for the link redirect.)

**Decision:** The `Link` redirect branch now calls `gateway.refreshSession()`, matching the proven Apple path. `hydrateCurrentUser()` remains for what it's actually good at — hydrating a tokens-only session (OAuth sign-in import / storage load) whose `user` is null. Interface docs updated to say so.

**Test-first:** Reproduced red before fixing. The fake gateway had masked the bug by stubbing `onHydrateCurrentUser` to perform the claim; the link tests now model device reality — `onRefreshSession` performs the claim, hydrate-only leaves the user anonymous — and a new regression test (`linkOAuthIdentity_redirect_refreshesSession_evenWhenHydrateAloneLeavesAnonymous`) pins it. With the old `hydrateCurrentUser()` line, 3 tests fail; with `refreshSession()`, all pass.

**Status:** Shipped (client). `:libraries:identity:impl` `compileDebugKotlinAndroid` + full `testDebugUnitTest` green. Not yet re-verified on a device against the live Supabase Google provider.

## 2026-06-28 — MP per-hand opt-in: push back; adopt between-hands sit-out instead (MP-28)

**Owner proposal (2026-06-28, push-back explicitly invited):** Today an MP hand continues no matter what a player does, and a player who wants to leave is guaranteed to forfeit a posted blind. Proposal: require each player to opt in to each hand (or be auto-sat-out / booted) so leaving between hands is clean.

**Decision: push back on mandatory per-hand opt-in; adopt a between-hands sit-out / clean-leave instead.**

The grievance is real and worth fixing, but the proposed mechanism (an explicit "I'm in" confirmation before every hand) is the wrong shape for a casual freemium poker app:

- **It taxes the 99% case to fix the 1% case.** The vast majority of the time a seated player wants to keep playing. A mandatory gate before every hand adds a tap (and a timeout-to-boot risk) to every continuing player to spare the occasional leaver one blind. Real-money and play-money poker clients don't re-confirm each hand; they let you queue a *sit out* or *leave after this hand* that lands at the hand boundary. That is the established, lower-friction pattern players already expect.
- **The codebase already has the primitive for the better fix.** `SeatStatus.SittingOut` exists in the gameplay enum (`libraries/gameplay/.../Seat.kt`) and `GameEngine.startHand()` already honors it — only `seatStatus == Active && stack > 0` seats are dealt in and posted blinds. It is currently never *produced* (every seat is hardcoded `Active` at `GameSession.startHandLocked`), so the engine half of a sit-out is already built and untested-against.
- **Most of the owner's concern is already handled.** A player who leaves is removed from the *next* hand today (`GameSession.removePlayer`, called from the leave flow), so they are never auto-posted a *future* blind. The only blind anyone forfeits is one **already posted in the live hand** when they leave mid-hand (`forfeitSeat` folds them; chips already in the pot are gone, which is correct poker). So the narrow, real gap is: *a player has no way to signal "deal me out of the next hand" or "leave at the hand boundary" — their only between-hands exits are leave-now or bust.*

**Recommended implementation (the slice the owner should greenlight, deferred pending this direction call):** a server-authoritative **between-hands sit-out**, built on the existing `SeatStatus.SittingOut`:

1. New `ClientFrame.SitOutNextHand` / `ReturnToPlay` (the room socket already carries `StartHand` / `RequestNextHand` / `Rebuy` / `SendEmoji`, so this is one more frame, not new transport).
2. `GameSession` tracks a per-player "sitting out" set; `requestNextHand`'s occupant rebuild stamps those seats `SeatStatus.SittingOut` instead of `Active`, so the engine skips them for the deal **and** the blinds with zero engine change.
3. A queued **leave-at-boundary**: a "leave after this hand" intent that fires the existing `removePlayer` + a clean exit at the next boundary rather than a mid-hand `forfeitSeat`, so a player who decides to go during a hand isn't auto-posted into the *next* one either (this is the ROOM-4-secondary backlog item — they converge).
4. Client: a "Sit out next hand" affordance on the hand-result / between-hands surface (`HandResultDialogs`), and a `SeatView.isSittingOut` flag so other players see the away state.

**Why this is filed as a decision, not a shipped feature:** the owner reserved the *direction* for their own review ("if you push back I want it mentioned in the PR description"), and the UX shape (where the sit-out control lives, whether there's an auto-sit-out-on-timeout, whether a long sit-out auto-boots) is exactly the contested surface. Building the full frame + session state + client UI before that call lands risks a large multi-module change the owner waves off. The decision and the recommended path are the deliverable; the build is greenlit-and-go once the direction is accepted.

**Status:** Decision documented; push-back surfaced for the PR description per the owner directive. No code change. Recommended implementation deferred to a follow-up once direction is confirmed — tracked via the ROOM-4-secondary backlog item (they share the hand-boundary machinery).

## 2026-06-30 — One Home notification arbiter, not five independent gates (PROG-5 / PROG-6)

**Problem:** Every "when the user lands on Home, show X" moment (starter-grant welcome, level-up celebration, play-style unlock, chip odometer) was gated **independently** in `HomeViewModel` — each with its own `combine`/`first`, its own persisted flag, its own race. The level-up celebration lost two ways: a fresh-account seed swallowing a real crossing (the PROG-3 hazard), and being swept off Home before it played (the CARDS-67 report). Stacking another watermark patch would have deepened the class of bug rather than removing it.

**Decision:** Introduce a **pure** use case `GetHomeScreenNotification(snapshot): HomeNotification.Blocking?` (in `features/home/impl/.../notification/`). It takes a `HomeNotificationSnapshot` of persisted facts (derived level vs `lastCelebratedLevel`, `walletJustCreated` + `didSeeInitialGrantInOnboarding` + resolved welcome identity, play-style sample vs `playStyleUnlockSeen` watermark + threshold, balance vs `lastShownChipBalance`) and returns the single highest-priority pending **blocking** notification. `HomeNotification` is a sealed type split into **blocking** (`Welcome`, `LevelUp`, `PlayStyleUnlocked` — full-screen, mutually exclusive, one at a time) and **ambient** (`ChipDelta` odometer, coexists). `HomeViewModel` now runs one `combine(...) → snapshot → EvaluateNotifications` collector; the handler seeds unset watermarks via a separate `seedsNeeded()` (kept out of the arbiter so a silent seed can never eat a real crossing), then presents the blocking pick **only when Home is settled** (`homeResumed`, the same signal the chip odometer already uses). The "we showed it" write always **follows** the present: level-up advances `lastCelebratedLevel` via `MarkLevelUpShown` at navigate-time; welcome + play-style mark their flags at present-time. A pending notification that resolves while Home is off-screen latches in `latestNotificationSnapshot` and flushes on the next `ScreenResumed` — the exact case a celebration used to be swept away in.

**Priority:** Welcome (first-run, once) → LevelUp → PlayStyleUnlocked. The odometer is ambient and plays alongside a blocking celebration.

**Alternatives rejected:** (1) Keep the independent gates and add a "not while navigating away" guard to each — rejected: it multiplies the same race across every surface and drifts. (2) Fold the chip odometer into the arbiter's blocking band — rejected: the odometer is non-modal and *should* coexist with a celebration; it stays ambient with its existing, well-tested `homeResumed`-gated mechanics untouched. (3) Model welcome as derived state like level-up — rejected: welcome is a routed dialog with its own back-stack lifecycle, so an event is the right shape; the arbiter drives both from one snapshot regardless.

**PROG-6 rode in on this:** the play-style-unlock announcement is one `HomeNotification.PlayStyleUnlocked` variant + one rule in the arbiter + one `AppData.playStyleUnlockSeen` watermark + a routed `PlayStyleUnlockedRoute` dialog — no fifth bespoke gate, exactly as the item asked.

**Status:** Shipped (client). Pure arbiter pinned by `GetHomeScreenNotificationTest` (seed-vs-crossing, priority ordering, welcome preconditions, play-style threshold, ambient chip delta). VM behavior pinned by `HomeViewModelTest` — including the cited fix (`levelUp_crossingWhileHomeNotSettled_isNotConsumed_firesWhenHomeSettles`) and PROG-6 (`playStyleUnlock_crossingThreshold_firesOnceOnSettledHome_thenMarksSeen`). `:apps:compose:assembleDebug` + iOS `compileKotlinIosSimulatorArm64` green.

## 2026-07-03 — Launch single-instance; enforce it with a Postgres single-writer lock (server sharding)

**Problem:** The game server holds authoritative live state in RAM — `GameSessionRegistry` (`Map<code, GameSession>`) and `InMemoryRoomService` (`Map<code, Room>`), each serialized by a per-room `Mutex`. That state is write-through persisted to Postgres and both services *rehydrate a room from its snapshot on a lookup miss*. Question raised: with multiple servers, how do we route "join by code" (shard by code?) and "find a public room" (search across instances?) — or do we just launch with one server?

**What actually breaks with >1 instance:** not data loss (state is durable) but **split-brain**. Two instances can each hydrate the *same* room from the snapshot and hold divergent copies; a mutation on A is invisible to B's socket subscribers, because the `Mutex` only orders writes within one JVM. That loses bets and desyncs seats — worse than any throughput ceiling. A cards table is a few KB of bursty state, so we are not instance-limited on capacity; the only real reasons to run >1 instance are availability/blast-radius, and the persistence layer already softens those (clients reconnect and rehydrate after a crash/deploy).

**Decision: launch single-instance,** and *enforce* single-writer in code rather than trusting config:

1. **`SingleWriterGuard`** (`apps/server/.../db/SingleWriterGuard.kt`) — at boot, after `Database.connect`, acquire a Postgres **session-level advisory lock** (`pg_try_advisory_lock`) on a dedicated connection held for the process lifetime. Exactly one instance can hold it; a second (`fly scale count 2`, an added region, a blue-green overlap) fails to acquire within a bounded window (15s, to ride out a stopped predecessor's connection teardown) and **crashes on boot** instead of corrupting tables. Fly restarts the loser, which crash-loops harmlessly. Released on clean shutdown (and the shutdown hook pins the reference so the guard's connection isn't GC'd) for fast hand-off on redeploy. Wired only into the prod `module()` boot path, not `installApp` — integration tests don't take the lock.
2. **Fly deploy strategy pinned to `rolling`** in both `fly.toml` and `fly.prod.toml` (`[deploy] strategy`), with a comment forbidding blue-green: blue-green runs old+new concurrently, which the lock now turns into a deadlocked deploy rather than a corruption — either way undesirable. Operational rule: keep `fly scale count 1`.

**Rejected / deferred alternatives (the eventual multi-instance path, if load ever demands it):**
- **Shard by room code** via consistent hash + Fly `fly-replay` to route the socket/join to the owning instance — the clean, Redis-free answer for *private join by code*. Deferred: unnecessary at launch scale.
- **Cross-instance matchmaking search / fan-out** — rejected outright. Public discovery can't be solved by code-sharding (codes aren't known in advance). The right fix is to make `matchmakingCandidates` a **Postgres query** against the already-durable `rooms` table instead of an in-memory `rooms.values` scan, making discovery instance-count-agnostic; then join routes by code. Deferred with the same rationale.
- **Redis / pub-sub backplane** for cross-instance socket fan-out — not needed until we actually run >1 instance for fan-out; Postgres is already a dependency and covers the directory need.

The seam is already clean: join-by-code and matchmaking both go through `RoomService`, so the shard-later change is localized, not a rewrite.

**Status:** Shipped (server). `SingleWriterGuard` + boot wiring + `[deploy] strategy = 'rolling'` in both Fly configs. `:apps:server:compileKotlin` green. The lock's acquire/timeout is not yet covered by an integration test (would need a Testcontainers two-connection race); the SQL is a single `pg_try_advisory_lock` call. Multi-instance sharding remains deferred until load or availability genuinely demands it.

## 2026-07-04 — Supabase session moves to OS-encrypted storage (AUTH-16)

**Problem:** The 2026-05-18 decision accepted plaintext token storage *only until claim shipped*. Claim (Apple + Google) has been live since June, so refresh tokens for real, claimed accounts were still sitting in supabase-kt's default `multiplatform-settings` store — plain SharedPreferences on Android, NSUserDefaults on iOS.

**Decision:** Install a custom `sessionManager` on the Auth plugin: `SecureSessionManager` (identity impl, commonMain) serializes `UserSession` itself (`encodeDefaults = true`, matching supabase-kt's own serializer so `expiresAt` survives round trips) into a new `SecureSessionStorage` port (identity api, commonMain — plain synchronous string contract so Swift can conform trivially). Platform backends: **Android** `EncryptedSessionStorage` (EncryptedSharedPreferences, AndroidKeyStore AES-256 master key, anvil-bound in androidMain); **iOS** `IOSSecureSessionStorage` Swift twin (Keychain generic-password, `AfterFirstUnlock`, passed through `create(...)` per `docs/practices/swift-kotlin.md`).

**Migration:** the storage key reproduces supabase-kt's default `SettingsSessionManager` key derivation byte-for-byte (`sb-<host-with-dashes>-session`, pinned by test), and a `SettingsSessionManager` on that same key is kept as the *legacy* reader: first `loadSession()` that misses the secure store reads the old plaintext entry, writes it encrypted, deletes the plaintext — nobody gets signed out. `deleteSession()` clears both stores so sign-out can't leave a resurrectable plaintext copy.

**Alternatives rejected:** (1) `SettingsSessionManager(settings = encrypted Settings impl)` — multiplatform-settings has no Keychain backend without another dependency, and we'd still need the Swift twin; wrapping our own port is less machinery. (2) Kotlin/Native cinterop Keychain in iosMain — more boilerplate than the established Swift-twin pattern for zero benefit. (3) Static storage key — per-project-URL keying keeps dev/prod sessions apart once the planned env split lands, and is required for the migration to find the legacy entry anyway.

**Status:** Shipped. `SecureSessionManagerTest` pins round-trip, one-time migration, secure-store precedence, dual-store delete, and the key derivation. Android assembleDebug + iOS Kotlin compile green; Swift side builds via xcodebuild.

## 2026-07-08 — Orphan-sweep guards unified in a shared verifier (AUTH-18)

**Problem:** `docs/wiki/account-lifecycle.md` promises the never-delete-progress "Hard guards" on *both* orphan-sweep paths, but only the opportunistic install sweep implemented them (`DefaultOrphanInstallSweep.verifyCandidate` + a SQL IAP gate). The scheduled TTL sweep (`DefaultOrphanAnonymousSweep`, `POST /v1/admin/sweep-anonymous-users`) deleted every anon account older than the TTL unconditionally — a long-idle level-30 or paying anon account would have been wiped the day the cron gets wired.

**Decision:** Extract the guards into `OrphanCandidateVerifier` (server `data/`), injected into both sweeps. Guards, in order: IAP spend (new `WalletRepository.hasIapSpend`, `reason LIKE 'iap.%'`), engagement-grade inventory (non-starter, non-founding rows), meaningful XP (≥ 100, the level-2 threshold), active room seat. The verifier returns the first tripped guard as a `SkipReason` so the sweep logs *why* a candidate was preserved; `SweepResult`/the admin response gained a `skipped` count. The install sweep keeps its SQL IAP pre-filter and re-checks via the verifier — belt and suspenders; a gate regression can never delete a paying account.

**Alternatives rejected:** duplicating the checks into the anon sweep (that's the drift that caused this gap); pushing all guards into the TTL SQL query (room-seat state lives in RAM, and Kotlin-side policy stays migration-free, matching the original install-sweep rationale).

**Status:** Shipped. `OrphanCandidateVerifierTest` pins each guard; both sweep tests cover skip accounting; `PostgresWalletRepositoryTest.hasIapSpend_matchesOnlyIapPrefixedReasons` pins the ledger query against real Postgres.

## 2026-07-10 — No internal TestFlight channel; sandbox purchases separated in data (BILL-6)

**Problem:** TestFlight IAPs are always StoreKit sandbox — free chips. The owner's own test purchases minted 125,000 unpaid chips into the prod economy, and friends on the beta will do the same. Question was whether to isolate testers on a dev-backend "internal" build or keep everyone on prod.

**Decision:** Everyone stays on the prod TestFlight build. Real-vs-sandbox is a *data* distinction, not a *distribution* one: record the StoreKit environment at receipt verification (the server already knows it — `APPLE_STORE_ENVIRONMENT`) and segment sandbox mints out of economy/revenue dashboards. Rationale: (1) friends' gameplay is real pre-launch signal, and a dev backend is a wipeable demolition zone that would burn their progress; (2) sandbox purchases exist for as long as TestFlight does — post-launch beta testers included — so the data fix is mandatory regardless; the channel fix would only mask it pre-launch.

**Alternatives rejected:** dev-backend internal builds for friends (kills signal, doesn't survive launch); same-app-id dual build tracks (one-install-per-device juggling, build-mixup risk); in-app env switcher (one install mixing two backends is the exact confusion class just debugged).

**Status:** Shipped 2026-07-10. `billing_transactions.environment` (V83) is written from the validator's verdict; the Apple validator verifies against the configured environment with a sibling-environment fallback (so the launch-day `APPLE_STORE_ENVIRONMENT` flip to `Production` can't break TestFlight testers); Play license-tester purchases (`purchaseType = 0`) count as sandbox. The wallet ledger splits `iap.<product>` (real money) from `iap_sandbox.<product>` — the `iap.%` real-money gates (orphan sweeps, install siblings) now correctly ignore testers. V83 backfills all pre-existing purchases/ledger rows as sandbox (distribution was TestFlight-only). The `cards-economy` dashboard segments sandbox out of the revenue stats and labels sandbox purchase series. ECON-1 still covers ledger conservation.

## 2026-07-10 — One telemetry stack for both environments, tag-separated

**Decision:** Prod and dev both ship to the single Grafana Cloud stack (same OTLP endpoint/creds), separated by the `deployment_environment` resource attribute — auto-derived from `FLY_APP_NAME` (`cards-server-prod` → `prod`). Matches the single-Sentry-project-with-environment-tag pattern the rest of the project uses. Root cause of the dark prod backend was simply missing secrets on the prod Fly app (`OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_EXPORTER_OTLP_HEADERS`, `SENTRY_DSN` — set on dev only when Grafana was first wired); ENG-17 tracks setting them. Client-side product events will ride the same pipe via a KMP OTel SDK exporting through a server relay (ENG-18) — never direct-to-Grafana with credentials in the app binary.

**Status:** ENG-17 shipped 2026-07-10 — owner set the three secrets on `cards-server-prod`; prod logs/traces verified in Loki/Tempo with `deployment_environment=prod`, and the prod server's Sentry is live. ENG-18 (client events) still filed.

## 2026-07-10 — Sync triggers rebuilt on a level-based `runWhen` primitive (ENG-20)

**Problem:** The 07-09 prod incident: `UserScopedSyncCoordinator` was edge-triggered off the app-event bus's single replay slot, so a fast launch whose boot events evicted the `UserChanged` replay never synced, connectivity returning never re-synced, and a sync blocked on late auth was never retried.

**Decision:** Repositories wait on **conditions (levels)** with a small set of re-fire **edges**: a generic `runWhen(key, refireOn, retry) { work }` `CoroutineScope` extension in `:libraries:flowroutines` (null key = off; non-null-at-subscribe fires — kills the boot race; key change by data-class equality cancels + refires; conflated trailing coalesce; bounded exponential retry while the key holds). App-state wiring lives in one `SyncTriggers` class (`activeAccount` level from `AuthRepository.observe`, `warmForeground` + `cameOnline` edges); the coordinator starts one independent `runWhen` per `UserScopedSyncer` (Option A — the six syncer repos unchanged, policy stays in one file, a failing wallet sync retries alone). PlayStyle and InAppMessage migrated off their hand-rolled listener triads (PlayStyle contributes `UserScopedSyncer`; InAppMessage runs its own no-retry `runWhen` pass); `AppEvent.AccountClaimed` deleted end to end — a claim is now just the `isAnonymous` flip in the key. Auth stays a replay-1 SharedFlow: the bug was many concerns multiplexed through one replay slot, not edges per se. Added during implementation: `AppEventBus.liveEventStream()` (replay-free sibling) because a re-fire edge reacting to the *replayed* pre-subscribe foreground event double-fired at activation — edge consumers read live, level consumers read levels.

**Alternatives rejected:** per-repo `runWhen` in each `init` (6 copies, forgetting one is a silent no-sync bug); a declarative per-contributor trigger spec (all six want the identical spec); converting auth to a `StateFlow` with a `Resolving` sentinel (contract churn for nothing the level key doesn't already give); reusing networking's `RetryPolicy` for the long-horizon retry (wrong layer/dependency direction — `sync()` keeps its inner idempotent network retry, `runWhen` covers the gate opening late).

**Status:** Shipped. Full plan in `docs/plans/eng-20-runwhen-triggers.md`. 11 `RunWhenTest` semantics tests + 10 wiring tests over real `SyncTriggers` (including the prod-incident boot-race regression) green; Android/iOS/server compile green. Phase 3 (bus replay 1→0, `ConnectivityEdgeDispatcher` deletion, identity listener migration) in backlog; the pre-existing user-switch clear window filed as ENG-21.

## 2026-07-10 — Wallet-ledger conservation invariant (ECON-1)

**Problem:** The lazy wallet create set `balance = 10,000` without a `wallet_events` row, so the ledger couldn't explain the starter chips — prod balances summed 146,000 against a 126,000-chip ledger (exactly 2 wallets × the missing 10,000 starter row; verified against prod before shipping).

**Decision:** `SUM(wallets.balance) == SUM(wallet_events.delta)` is now a pinned invariant. `WalletLedger.createWithStarter` writes the `starter_grant` ledger row in the same transaction as the wallet insert, using `insertIgnore` (`ON CONFLICT DO NOTHING`) for both inserts instead of the old catch-unique-violation-and-continue — a constraint violation aborts the surrounding Postgres transaction, which would poison callers composing the lazy-create with their own writes (the billing repo comment already documented that hazard). V84 backfills the missing rows keyed `(user_id, 'starter_grant')` so re-runs no-op. The invariant is enforced twice: `WalletLedgerConservationTest` (red-first; every mutation path incl. replays and rejected debits) and a "Ledger conservation drift" stat on `cards-economy` with red thresholds at any non-zero value.

**Alternatives rejected:** deriving balances from the ledger (`balance` as a materialized sum) — bigger surgery than V1 needs and the wallets row is load-bearing for the CHECK constraint; a scheduled reconciliation job — the dashboard stat gives the same signal without new infrastructure.

**Status:** Shipped. Server suite green; audit of every `WalletsTable` mutation confirms all paths (billing, table sessions, wallet API) already funnel through `applyInCurrentTransaction`, which journals.

## 2026-07-10 — Chip balance derives from snapshot + pending outbox, never blended (PROG-11)

**Problem:** The client kept one mutable balance that optimistic writes bumped and every sync's authoritative overwrite stomped. A grant landing while a sync request was in flight vanished from the display until the next sync (the owner's 07-09 "my 500 chips disappeared" report), and a server-refused event silently shrank the balance with no message.

**Decision:** The local `chips` row now holds only the last authoritative **server snapshot**, and the displayed balance is always derived: `snapshot + SUM(pending wallet_events deltas)`. `addChips`/`subtractChips` only enqueue outbox rows (visible immediately via the fold; duplicate idempotency keys can't double-count by construction); `setBalance` is a pure snapshot overwrite (MP settle and IAP redeem keep pending grants riding on top — strictly better than the old stomp). Rejected events (`InsufficientChips`) drop their rows AND announce a `ChipSyncRejection` that the App root surfaces as an error snackbar, mirroring the existing profile-edit-rejection pattern. No Room migration: same column, new meaning; a stale blended value self-corrects on first sync.

**Alternatives rejected:** patching the stomp window with locking around `getAll()`→`setBalance()` (shrinks but can't close the race, and does nothing for display-time folds); posting the outbox row-by-row with per-row balance application (chattier protocol for the same derivation the client can do locally).

**Status:** Shipped. Red-first tests: the mid-sync grant survives the authoritative overwrite (failed at 10,000 vs 10,500 before the fix), rejection announcement, plus the full rewritten chips suites (34 tests). Plan + investigation in `docs/agent/feedback-cases/2026-07-09-chips-vanish-on-restart.md`. QA: new `PROG-11` device test.

## 2026-07-10 — User-switch quiesces sync work before the data wipe (ENG-21)

**Problem:** `SupabaseAuthRepositoryImpl.emitLocked` awaits `clearFor(previous)` before emitting the new user, but the sync loops only cancel the old user's work when the *new* emission reaches them — which is after the wipe. A sync mid-flight for the departing user could land its writes into freshly-cleared stores, leaking the old user's data into the new user's session (reproduced red-first: `expected:<[]> but was:<[u1-data]>`).

**Decision:** The clear now has an explicit quiesce phase. A new `UserScopedWorkStopper` multibinding runs **before** every `UserScopedClearer` in `DefaultUserScopedDataReset`, each awaited. The one contributor, `UserScopedWorkRegistry`, tracks the jobs running user-scoped background work: the coordinator's per-syncer `runWhen` cycles and the in-app-message pass wrap their work in `registry.tracked(userId) { … }`, which registers the cycle's job (covering pending retry backoff and refire edges, not just the current attempt — pinned by a new `RunWhenTest` on the cycle-job contract). `stopWorkFor(userId)` cancels those jobs and joins them, so the wipe starts only after the old user's work has actually stopped. Stopper failures are swallowed-and-logged like clearer failures so a bad participant can't block the auth transition.

**Alternatives rejected:** in-stream markers (emitting an interim null / a `clearing` flag combined into the sync key) would also cover the microsecond gap where a cycle's first attempt hasn't dispatched yet, but every variant examined had a "user never syncs again" failure mode on re-sign-in — worse than the residual window, which is dispatch-tick wide and documented on the registry. Per-user `CoroutineScope`s owned by the reset were the same mechanics with more machinery.

**Status:** Shipped. Red-first user-switch test plus registry/reset/runWhen suites green; Android + iOS compile green.

## 2026-07-10 — Client app events ride the KLog tree system, direct to Grafana Cloud (ENG-18)

**Problem:** No product analytics exist: anything only the client sees (matchmaking back-outs, onboarding drop-off, backend-unreachable errors) never reaches Grafana, and the class of events that matters most must survive a backend outage.

**Decision:** Implemented plan PR 1 (`docs/plans/client-app-events-otel.md`). One blessed entry point — `Logger.logEvent(name, attrs)` in `:libraries:core` — marks an Info entry with a well-known extra; a new `GrafanaLogTree` in a new single-module `:libraries:telemetry:impl` (no api sibling; the extension is the public surface, and the experimental opentelemetry-kotlin 0.5.0 dependency gets its own blast radius) forwards only those entries to the Grafana Cloud OTLP gateway via `batchLogRecordProcessor` + `otlpHttpLogRecordExporter`, with a custom Ktor client carrying basic auth (the exporter has no headers param). Same entry reaches logcat + Sentry breadcrumbs through the existing trees for free. `session_id`/`install_id` stamp every record (never resource attrs — sessions roll over mid-process). Remote-config kill switch (`telemetry.appEventsEnabled`) + per-session sampling (`telemetry.appEventsSampleRate`, stable session-id hash so funnels stay joinable), both evaluated per-forward. Credentials are hard-coded placeholder constants (Sentry-DSN precedent, logs:write-only token); until the owner pastes them the tree is never planted. Starter events: `app.launched`, `room.joined/left/join_failed`, `hand.completed`, `purchase.completed/failed/cancelled`.

**Alternatives rejected:** relaying events through our backend (defeats the survive-backend-outage requirement; owner call 2026-07-10 approved the shipped-token trade-off); a new injectable event-tracker interface (call sites already hold loggers, and "ship all client logs to Grafana" later is just widening this tree's filter); `exporters-persistence` for offline durability (accepted loss for behavioral analytics; the processor-factory seam makes it a later one-line swap).

**Status:** PR 1 shipped: module + tree + config + 8 tests green on Android and iOS; Loki verification blocked only on the owner-minted token. PR 2 (taxonomy sweep), PR 3 (Warn+ forwarding), PR 4 (dashboards) remain under ENG-18.

## 2026-07-17 — Deterministic auth-outcome state machine: SignedUp / SignedIn / Linked (AUTH-22)

**Problem:** Nothing classifies what an authentication *was*. The auth layer collapses net-new sign-up, existing-account sign-in, and anonymous→identity link into one `SignInOutcome.Success` / `LinkIdentityOutcome.Success`. Downstream code reconstructs "was this new?" from `ChipsRepository.walletJustCreated` — a wallet-subsystem side effect that is best-effort (false on offline sync), one-shot, in-memory, and trips on any wallet (re)creation. This single weak proxy caused three user-visible bugs: the welcome dialog showing the wallet *balance* as a "gift" and firing for a pre-existing account (AUTH-23), the daily-bonus confusion, and account-deletion→Continue-with-Apple minting a net-new account but skipping onboarding (the native Apple path branched on "is an anonymous session present" — false after deletion — and assumed "existing account" → Home).

**Decision:** Introduce a single deterministic classifier that yields exactly one of `SignedUp` (net-new account), `SignedIn` (existing account), or `Linked` (anon guest linked an identity), and drives all downstream behavior — onboarding-vs-home routing, the starter-grant welcome, and a link-confirmation dialog — uniformly for Apple, Google, and email. `Linked` is known statically (we called a link op on an anonymous session). `SignedUp` vs `SignedIn` must come from a server-authoritative "brand-new account" signal (surface `isNewAccount` off `ProfileRepository.findOrCreateResult` on `GET /v1/me`, parity with the existing `walletCreated`/`progressionCreated`, or read Supabase `auth.users.created_at`) — NOT the `walletJustCreated` proxy. `is_anonymous` (JWT claim) stays the reliable link-vs-fresh discriminator. Ties to AUTH-19 (stable identity across anon→link and post-deletion).

**Phased:** (1) **done this session** — the native Apple path now classifies new-vs-existing via `isBrandNewAccount()` like the OAuth path instead of assuming "existing", so a post-deletion net-new Apple account runs through onboarding (2 red-first VM tests). (2) welcome shows the real `STARTER_GRANT` and fires only on `SignedUp` (AUTH-23). (3) formalize the typed `AuthOutcome` from the auth layer + the server `isNewAccount` signal, unify all providers. (4) link-confirmation dialog on `Linked` (AUTH-24). Tracked in `docs/todo.md` AUTH-22/23/24.

**Alternatives rejected:** hardening `walletJustCreated` in place (couples account-newness to wallet creation and to a best-effort sync; can't be made deterministic under offline/churn); inferring new-vs-returning from `identities`/`created_at` on the client (supabase-kt doesn't expose it reliably post-link on device, per AUTH-12).

**Status:** Phase 1 shipped (Apple onboarding-skip fix + tests). Phases 2–4 tracked. Related ops: the daily welcome-week bonus is gone from source but a stale server/queued message still delivers it — see `docs/developer-todo.md` → Server ops.

## 2026-07-17 — Link-confirmation dialog: instant links now, email deferred (AUTH-24)

**Decision:** Phase 4 of the auth-outcome work (a confirmation on the `Linked` outcome) ships for the **instant** links — Google OAuth and native Apple — as a routed `AccountLinkedRoute(providerLabel)` dialog that floats over Profile after the claim (mirrors the Home `WelcomeDialogRoute` pattern). `ClaimAccountEvent.Claimed` carries the `OAuthProvider` so the copy can name it. Two deliberate scope cuts: (1) **email is deferred** — its identity isn't linked until the user confirms the emailed link, and at the confirm point (`VerifyEmailViewModel.routeAfterConfirmation` → `NavigateToHome`) the anon-guest-link case is indistinguishable from a returning user with a previously-unconfirmed email without threading a new "started from an anon link" flag through `VerifyEmailRoute` + the onboarding VM/entry point; tracked as the rewritten AUTH-24 bullet. (2) The dialog is **not** shown for `OnboardingViewModel.finishAppleSignIn`'s anon-guest link, because that path flows into the full onboarding (PickIdentity → starter-grant reveal) which is its own confirmation and the guest has no earned progress to reassure about.

**Alternatives rejected:** firing the dialog on every `VerifyEmailEvent.NavigateToHome` (would wrongly congratulate a returning sign-in); adding the dialog to the onboarding link branch for literal "all providers everywhere" parity (redundant mid-onboarding); a snackbar instead of a dialog (too transient for a milestone the whole claim screen exists to reach).

**Status:** OAuth + Apple shipped with a VM test pinning the provider on the event. Email slice tracked in `docs/todo.md` (AUTH-24, ID retained).

## 2026-07-17 — Email link-confirmation dialog lands on Home, not Profile (AUTH-24)

**Decision:** The deferred email slice of AUTH-24 ships by threading a `guestLink: Boolean` through `VerifyEmailRoute` → `VerifyEmailViewModel`. `ClaimAccountEvent.NavigateToVerifyEmail` now carries the flag: true for a genuine anonymous-guest email link, false for the sign-up fallback (the anon session had already rolled over). On confirmation a guest-linked email short-circuits the new-vs-returning check and emits `NavigateToAccountSaved`, which `OnboardingFeatureEntryPoint` maps to `enterTab(Home)` + `AccountLinkedRoute("Email")` — reusing the existing dialog. This required a new module edge `:features:onboarding:impl → :features:profile` (api) for the shared route; no cycle since profile:impl only depends on onboarding's api.

**Alternatives rejected:** floating the dialog over Profile like the OAuth claim (the email confirm can arrive via a cold-launch deep link with no Profile underneath, so Home + fresh stack is the robust landing); persisting the pending-link state in `AppData` so the cold-launch deep link (`cards://auth/confirmed`, which can't carry the route flag) also shows the dialog — deferred as a follow-up, since the warm path (app alive, `AppResumed` on the live screen) covers the common case and the flag defaulting false only ever *omits* the dialog, never shows the wrong one.

**Status:** Shipped with VM tests (guest-link → `NavigateToAccountSaved`, marks onboarded, overrides `isNewAccount`) and ClaimAccount tests pinning `guestLink` true on the link path / false on the fallback. AUTH-24 fully retired.

## 2026-07-18 — Email confirmation flow: verify screen stops trapping, cold-launch link imports the session (AUTH-25/AUTH-26)

**Problem:** With email confirmation required, a brand-new email sign-up has NO session until the link is tapped. Two failures fell out of that. (AUTH-25) `VerifyEmailScreen` fires `AppResumed` on mount; `refreshSession()` with no session returns `SessionExpired`, and the handler routed that straight to `NavigateBackToSignIn` (clear back stack) — so the "Check your email" screen bounced the user to "Welcome back" with a dead back button the instant it appeared. (AUTH-26) The confirmation email redirects to `cards://login-callback`, which `App.kt` hands to `AuthRepository.completeOAuthRedirect`; that only resolved an in-memory pending-OAuth handle, so after a mid-signup app kill the handle was gone and the link was discarded ("no pending OAuth handle — ignoring stray redirect"), stranding the confirmed account unauthenticated.

**Decision:** (AUTH-25) On the verify screen, treat "no session" as "not confirmed yet" for a brand-new sign-up (`guestLink = false`): `AppResumed` → `SessionExpired` is silent (stay and wait), and the explicit "Check verification" tap surfaces the `StillPending` nudge instead of bouncing. A guest linking an email (`guestLink = true`) did have a live session, so a genuine expiry there still routes back to sign in. (AUTH-26) When `completeOAuthRedirect` finds no parked starter, attempt a direct session import from the URL fragment (the same parse+import+hydrate the sign-in path uses) — guarded to the session-less state so a stray redirect can never hijack a live account. A confirmation link carrying tokens establishes the session; a genuine stray/duplicate redirect (no tokens) fails the parse and leaves auth state untouched.

**Alternatives rejected:** persisting the in-memory pending-OAuth handle across cold start (more moving parts than parsing the session the link already carries, and only covers the killed case); pointing email confirmation at the `cards://auth/confirmed` nav deep link instead of `cards://login-callback` (that path can't import the token fragment, so it would land on the verify screen with still no session); having `VerifyEmailViewModel` distinguish expiry via an auth probe (the `guestLink` route flag already encodes "did a session ever exist").

**Status:** Both shipped with red-first ViewModel/repository tests. Residual (backlog): on the *killed-then-relaunch* case the imported session resolves the user into onboarding on the next auth resolve, not necessarily the same frame — the app boots session-less to `OnboardingRoute` before the import lands. The dominant path (app backgrounded, not killed, returning from the browser onto the live verify screen) routes forward immediately.

## 2026-07-19 — Grant-on-replay relaxes the BILL-11 account binding, gated to StoreKit replays (purchase recovery)

**Problem:** A paid receipt whose `appAccountToken` belongs to a different one of the user's own accounts (the reinstall-before-sign-in case) validated as `apple_account_mismatch` and was finished with no grant (BILL-13). BILL-11 deliberately kept the strict binding and earmarked relaxing it for the real-money review. Without relaxation, a genuine paying user who reinstalls and browses anonymously before signing back in loses the purchase.

**Decision:** When a receipt is signature-valid, for the right product, paid, and not revoked, and the ONLY failure is the account binding, the redeem route grants to the current caller and the client finishes it. The validators now return a dedicated `ReceiptValidation.AccountMismatch(orderId, environment, receiptOwner)` (revocation + transaction-id checked before the binding) so the route has what a relaxed grant needs. Guardrails, per `docs/wiki/purchases.md`: (1) only for a **StoreKit-replayed** transaction — the client sets `replayed = true` only on the outstanding-drain path, never on an interactive buy, so a pasted-receipt attack in the normal buy flow is never eligible; (2) rate-limited per user (`RelaxedGrantRateLimiter`, best-effort, in-memory, single-writer-consistent); (3) logged, and recorded with a distinct wallet reason `iap.<product>.replay` that stays under the `iap.%` prefix so a relaxed grant still counts as real-money spend and protects the account from the orphan sweep. Grants stay idempotent on the transaction id, so relaxing the binding never mints free chips — it only decides which of the user's own accounts gets the one grant.

**Security note (for review):** this relaxes the BILL-11 binding. The residual vector is a caller who already possesses another user's genuine signed receipt moving that single grant to their own account; bounded by idempotency (one grant per transaction id, ever), the replay gate, the per-user rate limit, and zero cash-out value of chips. Google's validator now also honors the install lineage (was Apple-only).

**Alternatives rejected:** trusting the client `replayed` flag as the sole boundary (it isn't — idempotency + rate-limit + no cash-out are the real bounds); a durable rate limiter (schema churn for a best-effort throttle whose backstop is idempotency and whose audit trail is the billing-events record); a distinct reason outside the `iap.%` umbrella (would drop a paying user's orphan-sweep protection).

**Status:** Server grant-on-replay + client `replayed` flag shipped with unit + route + Testcontainers tests. Sign-in-to-claim nudge for anonymous callers, the persisted billing-events record, wedged escalation, and the Grafana billing-health panel are the follow-on phases.

## 2026-07-19 — Purchase recovery: billing_events is the disposition log; wedged escalation is server-side off its attempt_count

**Decision:** Persist every redeem attempt that has a store transaction id in a new `billing_events` table (V88), one evolving row per `(store, transaction_id)` upserted on each attempt (bumps `attempt_count`, rewrites the latest caller / reason / final_action). This is distinct from `billing_transactions` (the atomic grant + idempotency record): `billing_events` records attempts whether or not they granted, so it is the disposition log and the source of truth for support and the Grafana Billing Health dashboard. Writes are best-effort so audit never blocks a grant.

The **wedged escalation** (goodwill grant past a retry cap) lives entirely in the redeem route and reuses `billing_events.attempt_count` as the counter, rather than adding client-side per-transaction attempt persistence or a new escalate endpoint. Every server-reached drain of a non-granting disposition (anonymous claim-sign-in, rate-limited mismatch) bumps the count, so the server can decide when a purchase has been re-attempted enough and escalate in place — returning a normal 200 grant the client finishes. Truly-unreachable-server transients never reach the server and never escalate, which is correct: those aren't wedged, the server just needs to come back.

Grant kinds unified under a `GrantKind` enum (Normal / GrantOnReplay / Goodwill) driving the wallet-reason suffix (`` / `.replay` / `.goodwill`), all under the `iap.%` real-money-spend prefix so recovered purchases still protect the account from the orphan sweep. User messaging for every drain outcome rides the existing server-authored `user_messages`, keyed per (transaction, outcome) so the idempotent `create` dedupes the every-launch drain.

**Alternatives rejected:** client-side attempt-count persistence + a dedicated `/v1/billing/escalate` endpoint (more moving parts and a second money-path surface, when the server already upserts the count per attempt); a distinct wedged reason outside `iap.%` (would drop the orphan-sweep protection on a paid purchase); enqueuing messages on the interactive buy path (the shop already celebrates those in the moment — gated to the `replayed` drain path instead).

## 2026-07-19 — Release-channel values renamed local / beta / store (was dev / beta / prod)

**Problem:** The release channel (a build-provenance label stamped into the About row and telemetry) used the values `dev` / `beta` / `prod`. Two of those collided with unrelated axes: `dev`/`prod` are also the `AppEnvironment` backend values (which server + Supabase project the build talks to), and a local *release* build carries the checked-in `dev` channel — so the About string read `0.1.0 (1) · dev` on a release build, which looked like it was reporting the backend or the build type. It was doing neither: channel, build type (debug/release), and backend env are three independent things.

**Decision:** Rename the channel values to `local` (any build off a dev machine — CI sets no override, so `versions.properties` default applies), `beta` (TestFlight / Play internal, unchanged, set by `beta.yml`), and `store` (public release, set by `release.yml`). `store` was chosen over keeping `prod` (re-collides with `AppEnvironment.Prod`) or `release` (collides with the build type). The concept stays — it's the only signal that separates a TestFlight-tester crash from a real store-user crash, since both are `release`-type builds on the same version during a rollout. Only the names change.

**Blast radius:** `versionDisplay()` now shows a clean version only for `store` (everything else appends `· <channel>`). The Sentry `environment` tag (`channel-platform-buildtype`) shifts `prod-*`→`store-*` and `dev-*`→`local-*`. Grafana/Loki `deployment.environment` is build-type-driven (`isDebug ? dev : prod`), NOT channel-driven, so it is unchanged and the triage-skill Loki queries keyed on it still work. Historical `docs/agent/` logs keep their old `dev-ios-debug` strings on purpose (they record what those builds actually reported). Any Sentry saved-search / alert or Grafana panel that filters the `environment` tag on `prod-*`/`dev-*` must be updated in Sentry/Grafana Cloud — out of repo.

**Alternatives rejected:** dropping the channel entirely (loses the beta-vs-store distinction, which build type and version number can't recover during an overlapping rollout); inferring the track from the version number (the same version is on beta and store simultaneously mid-rollout, so it can't disambiguate).
