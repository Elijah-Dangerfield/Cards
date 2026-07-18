# Backlog

Ideas and follow-ups we want to remember but aren't doing right now. Append-only; move items into `decisions.md` once shipped or formally rejected.

---

## Multiplayer wallet/payout test coverage across table sizes + leave timing

**Idea (owner directive 2026-06-28, Sentry [CARDS-61](https://elijah-dangerfield.sentry.io/issues/CARDS-61)):** Owner wants much broader testing around multiplayer games of varying sizes and player behavior — betting, checking, leaving mid-hand — to verify the correct wallet actions fire: that the pot is split as expected, that the right players get paid and others don't, and that opting out of the next hand settles a player out with exactly the expected amount. This is a test-coverage / scenario-harness effort (the poker scenario harness exists for the play screen), not a single bug fix; spans engine, server settlement, and client wallet reconcile. Concrete bugs found along the way (e.g. MP-26, MP-27) get their own todos.

**Status:** Backlog. Cross-cutting QA/test-harness investment; pull when MP money handling gets a dedicated hardening pass.

---

## More achievements + early-stage pacing rebalance

**Idea (owner feedback 2026-06-22, Sentry [CARDS-1A](https://elijah-dangerfield.sentry.io/issues/CARDS-1A)):** The achievement set is thin and early-stage achievements come too easily / too many fire up front, which risks spamming a new user. Two threads: (1) design a much larger achievement catalog (owner figures we could easily have ~100), and (2) rebalance early-game pacing so we don't dump a pile of trivial unlocks on the user in their first session. Needs a content/design pass on the achievement list + unlock curve, not just code.

**Status:** Backlog. Content + design call; pull when the achievement system gets a dedicated pass.

---

## Player-style — backend-backed so opponents can see it

**Idea (owner feedback 2026-06-22, Sentry [CARDS-J](https://elijah-dangerfield.sentry.io/issues/CARDS-J)):** Make a plan to implement the player-style metric (TIGHT/LOOSE × PASSIVE/AGGRESSIVE), backed to the server so *other* players can see a given player's style — not a client-only computation. Needs a real tightness/aggression metric defined + a server store + exposure on the room/profile snapshot. Pairs with the existing "PlayStyleBlob reuse" and "Player Card — Phase 2/3" backlog items below — the cross-player plumbing is the same wire those need.

**Status:** Backlog. Product + backend design; do alongside the play-style metric data work the Player Card phases already wait on.

---

## Per-seat positioned MP emote blasts

**Idea:** MP emotes ship rendered as a single center-screen `EmojiBlastOverlay` attributed to the emitter's avatar (see [decisions.md](./decisions.md) 2026-06-19). A richer treatment positions each opponent's blast *over their seat* at the table, so a busy table reads who reacted at a glance and two near-simultaneous emotes don't collide on one center slot. Needs per-seat blast state (a `Map<seatIndex, EmojiBlast>` instead of the single `emojiBlast` slot) and the table render loop to anchor each overlay to its seat's layout coordinates — the overlay already takes emitter attribution, so the work is positioning + multi-blast state, not a new component.

**Status:** Backlog. Polish on top of the shipped center-blast emote; the wire path + attribution already land it correctly.

## Bot bet-sizing tells

**Idea:** Have bots treat the human's bet size as a *signal* (in addition to the existing pot-odds math). Right now bots only react to bet size mathematically — a big bet costs more to call, so marginal hands fold. They don't interpret "this is a 3× pot overbet from a tight player, that means something."

**Sketch:**
- Compute the bot's expected bet size for its own perceived strength on this street.
- If the human's bet is materially larger than that, nudge the bot's estimate of opponent strength upward (calls less, folds marginal hands more).
- If the human's bet is materially smaller, nudge it downward (calls wider).
- Personality-weight the sensitivity: David and Gina pay attention; Mike basically ignores it; Steve and Jane react mildly.
- Add a small random `bluff-suspicion` term so the bot occasionally calls anyway — keeps it from feeling deterministic and lets the human bluff get through.

**Guardrails:**
- Keep the per-action equity shift small (think 5–15%, not 50%). It's a flavor, not an exploit.
- Don't apply to all bots equally — variance across personalities is what makes the table feel alive.
- No memory of the human's prior bluffs beyond what `OpponentTracker` already captures. We don't want bots to develop a "tell book" on the player.

**Tradeoff:** Done too aggressively, the bots start nailing every bluff and the table feels paranormal. Done too subtly, no one notices. The magnitude and the per-personality split is where the work is.

**Status:** Backlog. Revisit when the table feels "mathematically right but emotionally flat" — i.e., when the existing pot-odds bots feel too consistent across personalities.

---

## Daily / return-visit reward (non-streak)

**Idea:** A small, optional reward for returning to the app — *without* the daily-streak loss-aversion framing we explicitly rejected in 2026-05-16 (product-spec Appendix C.1; the spec doc was deleted in the 2026-06-24 docs restructure). The motivation isn't "punish you for skipping a day"; it's "be warm when you come back."

**Sketch directions to consider (pick one — they're not additive):**
- **Variable surprise.** Every Nth return visit (probabilistically) drops a small chip bonus + a custom message. No counter shown — surprise, not obligation.
- **Weekly play-streak** (was product-spec Appendix B item 17). Consecutive *weeks* with ≥1 hand played. Lower-pressure than daily.
- **"Welcome back" only after gaps.** Reward triggers if it's been ≥7 days since last play — re-engagement, not retention pressure.
- **First-hand-of-the-day chip-coin.** Tiny bonus on your first hand played each calendar day. No counter, no streak number, just a one-time "+50" badge that hand. Easy to add, easy to remove.

**Guardrails:**
- **No counter, no streak number, no "you'll lose your X if you skip."** That framing is the exact thing we said no to.
- **No notification spam.** Reward is discovered when you open the app, never pushed to your lock screen.
- **Reward small enough to not affect economy balance.** This is warmth, not a chip faucet.

**Tradeoff:** Even the gentlest version drifts toward daily-obligation framing if scoped wrong. The decision math is whether retention numbers justify the risk to the brand. Revisit after V1 ships and we have D2/D7 data — if retention is healthy without it, leave it alone.

**Status:** Backlog. Considered + deferred 2026-05-20. Re-affirmed deferred 2026-07-07 during the chip-economy review (out-of-chips sheet shipped instead as the broke-player path; bust protection + achievement/level grants are the only faucets at launch). If economy dashboards show busted players churning rather than earning back or buying, this is the first faucet to reach for — sized under half a Casual buy-in per day, per the guardrails above.

---

## Level rewards past 20 — revisit with live economy data

**Idea (owner directive 2026-07-07):** `RewardChips.LEVEL_CHIPS` stops at level 20 (47,500 total chips through the ladder), so a committed player's last recurring earn path goes quiet exactly when they're most invested. Likely shape: sparse chip rewards at 25/30/40/50 sized around one buy-in of the tier that cohort actually plays — but whether they're needed at all depends on whether level-20+ players are net winners (don't need grants) or grinders (do).

**Decision inputs (check ~2026-10-07, three months post-TestFlight):**
- How many users have reached level 20, and their balance distribution (economy dashboard: balance-in-buy-ins by level cohort).
- Whether level-20+ players keep playing without grants (retention curve past the last reward) or fall off at the cliff.
- Faucet-vs-sink totals — if the economy is already inflating, extending the ladder makes it worse; pair with the rake lever if so.

**Guardrails:** amounts must stay proportional to time-at-level (the quadratic XP curve does the throttling); don't retro-grant on ship (the `highestLevelRewarded` watermark seeds forward, same as the celebration watermark).

**Status:** Backlog. Blocked on live data, not on code — needs the `cards-economy` Grafana dashboard (in progress 2026-07-07) and a TestFlight cohort aging into it.

---

## Felt-aware button color adaptation

**Idea:** When the user equips a colored felt, the play-screen action buttons can clash. Either pin the buttons to a felt-independent surface, or token the buttons against a `surfaceOnFelt` color that the felt itself defines.

**Sketch:**
- Each felt declares its own `surfaceOnFelt` + `onSurfaceOnFelt` pair in its catalog entry.
- The play screen's action button strip reads from `LocalCurrentFelt.surfaceOnFelt` (composition local set at the play screen root) instead of `AppTheme.colors.surfacePrimary`.
- Fallback to surface tokens when no felt is equipped or the felt doesn't declare an override.

**Tradeoff:** Right now the play surface is dark enough that the clash is mild. The fix isn't expensive but it's tokens + catalog metadata, not a one-line change. Worth doing when we have time to extend the catalog schema, or when we ship a brighter felt that makes the clash actually painful.

**Status:** Backlog. Deferred 2026-05-20 — not a V1 blocker; current felt palette is forgiving.

---

## Audio infrastructure (sound cues, BGM)

**Idea:** Add a small KMP audio playback layer so "Your turn feedback = Sound" (and future cues — winning a hand, achievement unlock, table ambience) can actually play a tone instead of being a no-op. Today the setting persists but only the Vibrate option is wired (via Compose `HapticFeedback`); Sound is recorded in `AppData` but the human just gets silence.

**Sketch:**
- New `:libraries:audio` KMP module with an `AudioPlayer` interface.
- Android `expect` → `actual` impl backed by `SoundPool` or `MediaPlayer` (short cues = SoundPool).
- iOS `actual` impl backed by `AVAudioPlayer`.
- Bundle a tiny library of WAV/MP3 cues in `compose-resources` — start with one "your turn" chime.
- Inject via DI; hand it to `PlayBotsScreen` (or a `TurnFeedbackPlayer` wrapper) so it can `play(Cue.YourTurn)` when `state.turnFeedback == Sound`.

**Tradeoff:** Audio adds binary size + a small init cost. Worth it for the "sound" preference to be honest, plus opens the door to other game cues later. Until then, "Sound" is effectively a no-op (Mute and Sound behave the same).

**Status:** Backlog. Vibrate works now via Compose haptics; ship Sound when it's worth the platform-actuals overhead.

---

## Route default `popExit` = reversal of `enter`

**Idea (raised 2026-05-20):** After the cover-and-uncover NavHost rewire, every existing `Route` subclass still has to declare `popExit` explicitly to mirror `enter`. The default in `Route(...)` is hardcoded `AnimationType.SlideOutToRight`, which is fine for horizontal-slide routes but wrong by default for `SlideUp` / `FadeIn` / etc. — a new route that forgets to declare `popExit` will pop horizontally regardless of how it entered.

Worth deriving the default — `popExit: AnimationType = enter.reversal()` (the existing `opposite()` is a *mirror* not a *reversal*, so it needs renaming or a sibling). Then every per-route `popExit` declaration that matches the derivation can be dropped.

Blocker on doing it now: `opposite()` currently maps `SlideInFromRight → SlideOutToLeft` (mirror), but the reversal we want for back-out is `SlideInFromRight → SlideOutToRight` (back the way it came). Renaming / fixing the mapping is a semantic call worth a deliberate pass rather than tucking into the wiring change.

**Status:** Backlog. Captured 2026-05-20 alongside the NavHost cover-and-uncover wiring.

---

## Sweep remaining game-table corner literals → `Radii` tokens

**Idea (raised 2026-05-20):** `Radii.R700` (16.dp) was added during the RankDetail cleanup. `R800` (20.dp), `R900` (24.dp), and `R1000` (28.dp) were added 2026-05-21 alongside the non-game-table follow-up (`FeatureCard`, `AchievementMedallion`, `LobbyScreen`). The non-game-table 16.dp callsites were migrated 2026-05-21 (`MyItemsScreen`, `StatsScreen` ×3, `FeatureCard`, `QaMenuScreen` ×5 including the 10.dp `R400` ones). Remaining literals all live on game-table surfaces, which were tuned by hand for the play screen — worth a deliberate visual sweep rather than blind replace:

- `BoardArea.kt:94/98` — 16.dp (→ `R700`)
- `HandResultDialogs.kt:272` — 16.dp (→ `R700`)
- `PlayerArea.kt:102/103` — 20.dp (→ `R800`)
- `PlayerArea.kt:240/245` — 16.dp (→ `R700`)
- `HandRankingsCheatSheet.kt:266` — 28.dp (→ `R1000`)
- `HandRankingsCheatSheet.kt:382/422` — 20.dp (→ `R800`)
- `RaiseSheet.kt:206` — 28.dp (→ `R1000`)
- `TableActionBar.kt:138/167` — 28.dp (→ `R1000`)

**Status:** Backlog. Non-blocking DS drift; pull when next opening the play-table surfaces.


---

## Anti-farming on the starter chip grant (uninstall-reinstall exploit)

**The exploit (raised 2026-05-23):** Today a user can uninstall + reinstall to mint a new anonymous Supabase user → server `WalletRepository.findOrCreate` grants a fresh 10K starter. Repeat indefinitely. Nothing in the chain checks "is this a device we've already paid out."

**What the spec said — already V1-scope, just not built.** Product-spec §6.1 "Anti-farming on the starter grant" (doc deleted in the 2026-06-24 docs restructure) called for one-starter-per-device-fingerprint. So this isn't a new idea; it's a tracked gap from spec to implementation.

**Update 2026-05-29 — scope-cut for V1, full design preserved here:** This gate is **not** shipping in V1. Per [decisions.md 2026-05-29 — V1 scope: install_id only](./decisions.md), the V1 wallet starter mints unconditionally on every fresh anon — the exploit stays open at the wallet layer, and the disincentive is purely intrinsic (farmer loses their old account + all its progress every loop). When this becomes a real complaint / revenue concern, two upgrade paths are pre-designed:

- **Option B (~3 days):** add `identifierForVendor` (iOS) / `Settings.Secure.ANDROID_ID` (Android) to the request; gate `WalletRepository.findOrCreate` with `WHERE platform_device_id = X AND starter_granted = TRUE`. Closes the casual same-device reinstall vector. Doesn't survive factory reset or new-device migration, which is fine — those are different humans most of the time. Two one-line platform reads, no KMP keychain work. Also unlocks same-device revival on reinstall as a bonus (the old `recovery-and-orphaned-accounts.md` design doc was deleted 2026-06-24 — see git history).

- **Option C (~1–2 weeks):** add a `recovery_id` column on `profiles`, generated client-side and persisted via iCloud Keychain (`kSecAttrSynchronizable=true`) + Android Block Store. Survives reinstall *and* device migration (rides the user's platform account, not hardware). Anti-farm gate becomes `WHERE recovery_id = X AND starter_granted = TRUE` — same human across all their devices gets one starter. Detailed design was in the deleted `recovery-and-orphaned-accounts.md` (git history); the full pre-scope-cut design (Welcome-back screen, splash boot tree, recovery endpoint) is preserved in git at `13b84b37` for when this is on the table.

Direction A and B below predate the scope-cut and remain as alternative implementation sketches if either Option above is ever picked up.

**Existing scaffolding worth knowing about:**
- `AppIntegrityVerifier` interface exists in `apps/server/.../domain/AppIntegrityVerifier.kt` but binds only to `NoOpAppIntegrityVerifier` — every request passes. KDoc says "enforce before first invited-real-users release."
- `AppIntegrityFingerprint` exists but only carries `platform` + `clientVersion` — not a real device fingerprint.
- Server's wallet starter is in `WalletRepository.findOrCreate`, keyed solely on `userId`. No fingerprint check today.

**Two directions — they're alternatives, not additive:**

### Direction A: build the fingerprint plumbing (the spec's path)

**Tier 1 — Device ID (cheap, defeats casual exploit):**
- Android: `Settings.Secure.ANDROID_ID` is per-app-per-signing-key-per-device since API 26; survives uninstall + reinstall; resets only on factory reset.
- iOS: `identifierForVendor` survives single-app reinstall; resets when *all* apps from the same vendor are wiped.
- Send to server (header or JWT custom claim). Server keeps `starter_grants_by_fingerprint` table; `findOrCreate` checks before granting the starter and grants 0 chips on a duplicate.
- Defeats: casual reinstall farming on the same device.
- Doesn't defeat: factory reset, rooted spoofing, multi-device farming, jailbroken devices.

**Tier 2 — iOS DeviceCheck (the "2 bits that survive reinstall" trick):**
- Apple's DeviceCheck API gives you 2 bits of storage per `(app, device)`, server-managed via Apple's endpoint, that persist across uninstall + reinstall + (often) restore.
- Use bit 0 = "this device has claimed the starter grant." Survives `identifierForVendor` reset.
- iOS-only — no Android equivalent. Android stays on Tier 1 + Play Integrity for the rooted-device case.

**Tier 3 — Play Integrity / App Attest (V2 shape, not for the starter specifically):**
- Confirms the request came from a genuine, unmodified Cards APK / iOS build.
- Doesn't directly answer "have we seen this device" — that's still the fingerprint table's job.
- Decline starter on devices that fail integrity verdict (emulator, repackaged APK). Per the existing `AppIntegrityVerifier` KDoc, enforcement is "before first invited real users" — broader than just the starter grant.

### Direction B: the design dodge — reshape the starter so the exploit has no payload

The spec frames the 10K as a first-impression gift on first contact. If we changed the trigger from "first install" to one of:
- **First hand played** — user has to actually play a hand before the grant lands.
- **On claim only** — anon users get 0 starter; the 10K is the claim incentive.
- **Drip-grant** — 1K on first contact, 1K each day for the next 9 days (subject to the same orphan-anon TTL).

…then reinstalling to claim a fresh starter requires actual play / actual claim, which a script-farmer isn't getting value from.

This is a real product call — the 10K-on-first-contact is part of the "Card Hall, you belong here" first-impression vibe per the brand notes. Reframing it loses that moment.

**Honest scope read:** V1 has no real money, no leaderboard, and `DefaultOrphanAnonymousSweep` eventually deletes idle anon accounts. Worst case of doing nothing is mild server load from scripted account creation. Not a security crisis. The choice between A and B is partly about how much we trust the anon-orphan sweep + rate-limit to hold the line for invited-users-only V1, vs. wanting a clean story before public launch.

**When to revisit:** before V1 ships publicly (per the spec) OR if scripted anon-creation rates show up in the orphan-sweep metrics. Whichever lands first.

**Status:** Backlog. Pairs with the existing "App integrity attestation — planned" entry in `docs/decisions.md`.

---

## Eager delete of orphan anon account on guest sign-in

**Idea (raised 2026-05-23):** When an anonymous guest signs *in* (not up) to an existing real account via `SignInViewModel`, the local session swaps to the real account and the anon `auth.users` row is orphaned. Today the orphan is cleaned up lazily by [`DefaultOrphanAnonymousSweep`](../apps/server/src/main/kotlin/com/cards/server/data/DefaultOrphanAnonymousSweep.kt) once it ages past `config.orphanAnonTtlDays`. We could clean up eagerly instead — fire a delete for the just-orphaned anon `userId` *only after* the sign-in to the new account has succeeded, so a mid-flow failure can never blow away a guest's progress.

**Sketch:**
- Snapshot the current anon `userId` before calling `identityRepository.signInWithEmail(...)` / `signInWithOAuth(...)`.
- On `SignInOutcome.Success` (and only then), POST a delete request to the server for that anon `userId`. The server-side handler reuses the same `SupabaseAdminClient.deleteUser` + `profileRepository.delete` path the sweep already uses.
- On any non-success outcome (`InvalidCredentials`, `NetworkError`, `Cancelled`, etc.), do nothing — the guest's anon account is still their live session.
- Treat the delete as best-effort: a failure here doesn't block navigation, just leaves the orphan for the sweep to pick up later.

**Tradeoff:** Sweep already handles this on a TTL, so it's a UX-invisible cleanup tightening — no user-facing change. Worth it for keeping the anon table small and avoiding a window where a churned guest still exists server-side. The "only after success" gate is the load-bearing detail — the V1 risk is a bug that fires the delete before sign-in confirms and leaves the user signed out with nothing.

**Status:** Backlog. Pairs with the existing `DefaultOrphanAnonymousSweep`; pull when the next identity-layer pass opens.

---

## Live chip updates inside the welcome dialog after slow-network open

**Idea (raised 2026-05-24):** The welcome dialog now opens regardless of whether `/v1/me/wallet` has hydrated (see `WelcomePayload.chips: Long?` and the `ChipRevealPlaceholder` fallback in [WelcomeDialog.kt](../features/home/impl/src/commonMain/kotlin/com/cards/features/home/impl/WelcomeDialog.kt)). When chips arrive late, the dialog still shows the em-dash placeholder until dismissal — the route param is static. Home shows the real balance the moment they dismiss, so the user-visible gap is narrow, but a long fresh-install round-trip would leave the placeholder visible the whole time the user reads the dialog.

**Sketch:**
- Lift `ChipsRepository` into the dialog destination scope (it currently lives in Home's scope) and have the dialog observe the live balance flow.
- Or: add a small dialog-scoped `WelcomeDialogViewModel` that owns the observer and re-renders the reveal once chips land.
- Either way, the navigation argument stays static (kept for back-stack restoration) but the dialog reads through the live source.

**Tradeoff:** Adds plumbing for a narrow window most users won't hit. The placeholder is acceptable per the original todo framing. Pull only if device QA shows the placeholder feels long enough to notice on real-world slow networks.

**Status:** Backlog. Confirmed as follow-up, not in the slice that landed 2026-05-24.

---

## Earn-source attribution on My Items "Earned" rows

**Idea (raised 2026-05-24):** Earned cosmetics in [MyItemsScreen.kt](../features/profile/impl/src/commonMain/kotlin/com/cards/features/profile/impl/items/MyItemsScreen.kt) now render an "Earned" badge, but don't surface *what* earned them ("from Comeback Kid achievement", "from Bronze league"). `InventoryItem` doesn't carry the source id today.

**Sketch:**
- New wire field on the server's grant path — `earnedFromKind` (achievement / league / RFT) + `earnedFromId` (the source row id).
- Client-side resolver from `(kind, id)` → display string (achievement name, league tier, etc.) — most of the source name catalogs already exist on the client.
- Render the line under the subtitle on earned rows; keep the inline `EarnedTag` chip exactly as it is for equippable rows.
- Pairs with the still-open "celebratory unlock dialog at the moment of earning" item already tracked in `docs/todo.md` under "Catalog gating — unlock-only vs purchasable" — same source-name resolver feeds both.

**Status:** Backlog. Earned-badge rendering already shipped; this is the deeper prestige attribution.

---

## Offline-aware retry — defer requests until connectivity returns

**Idea (raised 2026-05-24):** The new `RetryPolicy` system (in [`:libraries:networking`](../libraries/networking/src/commonMain/kotlin/com/cards/libraries/networking/retry/RetryPolicy.kt)) retries on `HttpRequestTimeoutException` by default — but a timeout doesn't actually tell you whether the user is offline or the server is slow. On a truly offline device the retry loop just burns its budget (4–11 attempts depending on policy) hitting the same DNS / TCP failure, then surfaces the error. The user-facing UX is the same as a single failure with extra latency.

**Sketch:**
- New `Backoff.OnceOnline` (or an orthogonal `awaitOnline = true` flag on `RetryPolicy`) that suspends the next attempt on a `ConnectivityObserver.online.first { it }` instead of (or in addition to) the time-based backoff.
- Or, more honestly: this isn't really a retry-loop problem — it's a deferred-execution problem. The real shape is a small persistent queue ("when online, fire request X") with idempotency guarantees, so a write that the user kicked off offline still lands when they reconnect, even if the app got killed in between. That's significantly more architecture (WorkManager on Android, BGProcessingTask on iOS, schema for the queue, idempotency keys on the server) and deserves its own design pass.

**Tradeoff:** The cheap version (await-online before each retry) is a marginal UX improvement — it just makes the same retry happen at a better moment. The expensive version (deferred queue) is what actually matters for the "user did this offline, expects it to land" case. Pick based on what we're actually trying to solve.

**Status:** Backlog. Today's retry policy is honest about its limitation in the [`RetryPolicy` header](../libraries/networking/src/commonMain/kotlin/com/cards/libraries/networking/retry/RetryPolicy.kt). Pull when an offline-write scenario actually surfaces.

---

## Country / segment-scoped `/v1/app-config`

**Idea (raised 2026-05-24):** Today the server returns one global config blob — [`AppConfigSource.read()`](../apps/server/src/main/kotlin/com/cards/server/domain/AppConfigSource.kt) takes no parameters; every client gets the same JSON. The `ClientContext` (platform, app version, CF country header) is parsed on the request but not threaded into the config source.

**Sketch:**
- Change the source signature: `suspend fun read(): JsonObject` → `suspend fun read(ctx: ClientContext): JsonObject`.
- `AppConfigRoutes` passes `call.clientContext()` through.
- Source impls decide what to scope on — country code for price floors / store availability, user id for A/B cohorts, app version for feature flags tied to client capabilities.
- Client-side merges overrides on top of whatever the server sends; no client changes needed.

**Tradeoff:** Each scope dimension adds complexity at the source. The current global blob is honest about its limitation — no per-region pricing, no A/B testing. Add it when one of those needs lands.

**Status:** Backlog. Surfaced during a `docs/todo.md` cleanup pass — the user asked whether we could give different users different configs, today we can't.

---

## Preview button on shop felt tiles

**Idea (raised 2026-05-24):** Today the shop renders each felt as a tile with its name + price + maybe a thumbnail. The user can buy a felt without seeing how it actually looks behind the cards / chips / player avatars in-context. Add a Preview button on each felt tile that previews the felt rendered at the play-table surface so the user can see what they're about to buy.

**Sketch:**
- Per-tile "Preview" affordance — bottom sheet or full-screen take-over.
- Render a minimal play-table preview (felt color + a few chip stacks + a placeholder hand) with the chosen felt applied via the existing cosmetic system.
- "Apply" button on the preview if the user already owns the felt; "Buy + apply" if they don't.

**Tradeoff:** Bespoke preview surface per-cosmetic-type drifts toward a maintenance pit — but the felt is the highest-stakes purchase visually (it dominates the play screen), so it's worth a one-off for now. Generalize only if other cosmetic categories (card backs, avatars) feel similarly purchase-blind.

**Status:** Backlog. Surfaced during the 2026-05-24 todo cleanup — user suggested it during the charcoal-default correction.

---

## Friends activity feed

**Idea (raised 2026-05-24):** A feed of things your friends have just done — "Jake won a 12K pot", "Jake leveled up to 14", "Vivienne unlocked Comeback Kid", "Steve sat down at a Standard table." Lives somewhere on Home (likely above or below the existing FriendsStrip / RecentlyPlayedWithStrip) or on a dedicated Activity tab. Same "warm signal that the world is alive" instinct as the existing ActivityTicker, but scoped to people the user actually knows.

**Sketch:**
- Server emits activity events into a per-user log when a friend hits a notable threshold — big pot won, level up, achievement earn, league promotion, rare cosmetic equipped, sat at a notable stake. Each event carries `(friendUserId, kind, payload, occurredAtEpochMs)`. Fan-out is the friend graph; only the user's friends' events land in their log.
- Client `FriendActivityRepository.observeFeed(limit = 20)` returns newest-first; the Home shelf renders the top N with a "see all" tap-through to a paginated screen.
- Event copy templated client-side from a small renderer (`kind` → composable row), so localization + per-event treatment stays in one place. Avatar + display name come from the friend profile cache.
- De-noise: collapse rapid-fire events from the same friend ("Jake won 3 hands" instead of 3 rows), cap obvious-flex events (don't render every +5 chip win), skip duplicates if the user was at the table.

**Notable thresholds — first pass:**
- Pot won ≥ N chips (tier-aware: 5K Casual / 10K Standard / 50K High).
- Level up (every level, or just every 5th — design call).
- Achievement unlocked (rarity ≥ RARE; COMMONs are too noisy).
- League promotion (when leagues ship).
- Limited-drop cosmetic equipped within the drop window.

**Hard deps:**
- The friends / social-graph system already on `docs/todo.md` §A — without a real friend graph, there's no feed to populate.
- Server-side event-emission hooks at hand-resolution / progression / inventory paths. Cheapest if these reuse the existing achievement / XP grant code paths and just write an additional row.

**Tradeoffs:**
- Privacy: friends seeing each other's stack swings is the appeal *and* the risk. Default-on is the right call for "warm", but a per-event-kind opt-out in Settings ("hide my level-ups from the feed", "hide my big pots") keeps the user in control. Don't ship without that lever.
- Voice rules (from the since-deleted product spec, still the house style): no urgency, no "X people are waiting!" pressure. The feed is observational, not push-marketed.
- Noise: this surface dies the moment it feels like a Twitter timeline. Aggressive de-duping + thresholds + a hard cap on shelf size matter more than picking the right event kinds.

**Status:** Backlog. Strictly downstream of the friends/social-graph system; pull once the friend graph is real.

---

## ProfileRepository → InventoryRepository import

**Idea:** When the V18 starter-inventory work lands (see `docs/todo.md` → Catalog gating), `ProfileRepository.findOrCreate` will need to write the default inventory rows inside its profile-insert transaction — either by holding an `InventoryRepository` reference or by hand-writing the inventory SQL. A layer that was previously "just profiles" then reaches into products + inventory. Defensible in the moment ("give a new user their starter kit" is a profile-creation concern), but worth revisiting once the starter kit grows or `ProfileRepository`'s responsibilities feel too broad.

**Sketch directions when revisiting:**
- **Postgres trigger.** `AFTER INSERT ON profiles` fires a stored procedure that writes the starter inventory rows. App layer stays clean; schema enforces atomicity. Cost: defaults live in SQL; harder to test; trigger logic harder to evolve as the starter kit grows.
- **Starter-kit seeder service.** A separate `StarterInventorySeeder` class invoked from the route after `findOrCreate` (using a `wasCreated` flag, or an "is empty" check). `ProfileRepository` goes back to single-concern; the seeder owns the cross-domain knowledge.
- **Status quo from V18:** `ProfileRepository` imports `InventoryRepository` directly. Greppable, testable in Kotlin, minimal moving parts. Sufficient until the starter kit grows past a handful of items.

**Tradeoffs:**
- Triggers move correctness into the schema, where it can't be bypassed by a buggy code path. Price: schema changes are slower, and trigger debugging is harder than reading a Kotlin transaction block.
- A seeder service is the most "clean architecture" answer but adds a layer when the V1 starter kit is three rows. Premature.
- Status quo is the right V1 trade. The cleanup item exists so we don't forget the layering smell.

**Status:** Backlog. Pick up when the starter kit grows past a handful of items, when a second consumer of "create user's default X" lands (e.g. default chip wallet), or when `ProfileRepository` starts importing a third cross-domain repo.

---

## Sweep remaining raw `Color.White.copy(alpha=…)` in poker visuals

**Idea:** The DS-first sweep took FeatureCard off raw white. Poker-artifact files still use it: [`AchievementMedal.kt`](../libraries/ui/src/commonMain/kotlin/com/cards/libraries/ui/components/achievement/AchievementMedal.kt) (rim + back ink), [`CardBackStyle.kt`](../libraries/ui/src/commonMain/kotlin/com/cards/libraries/ui/components/poker/CardBackStyle.kt) (card-back borders), and [`PlayingCard.kt`](../libraries/ui/src/commonMain/kotlin/com/cards/libraries/ui/components/poker/PlayingCard.kt) (specular + pip line).

These read more like poker visuals than DS surfaces, which AGENTS.md rule #4 carves out to `PokerPalette`. Either declare a `PokerPalette.SpecularWhite` / `PokerPalette.CardBackBorder` token and route the callsites through it, or accept the carve-out as documented behaviour. Either is fine; both are better than the current "everyone half-believes the rule applies."

**Status:** Backlog. Judgement call about whether these are surfaces or poker artifacts.

---

## Auth — password-reset recovery deep-link landing screen (`ResetPasswordScreen`)

**Idea:** The forgot-password send-email flow shipped via [`ForgotPasswordRoute`](../features/onboarding/src/commonMain/kotlin/com/cards/features/onboarding/AuthRoutes.kt) wires Supabase's `resetPasswordForEmail` end-to-end, but the link Supabase sends in the recovery email lands on the redirect URL configured on the Supabase dashboard — currently localhost, same hole the verify-email link sits in. Once the redirect URL is configured (tracked under `docs/developer-todo.md` "Supabase dashboard — production redirect URLs"), build a `ResetPasswordScreen` that:

- reads the access token from the deep-link URL fragment (the magic-link redirect sets `#access_token=…&refresh_token=…&type=recovery`),
- exchanges it for a Supabase session via the auth gateway,
- lets the user pick a new password (mirror `SignUpScreen`'s password + confirm fields),
- calls `supabase.auth.updateUser { this.password = ... }` to set the new password,
- on success, navigates to home (mark `hasUserOnboarded = true` if not already).

**Hint:** the existing `LinkEmailIdentityOutcome` mapping shape is the model — add `UpdatePasswordOutcome` (`Success` / `WeakPassword` / `SessionExpired` / `NetworkError` / `Unknown`) and a `AuthRepository.updatePassword(newPassword)` method. The screen lives in `:features:onboarding:impl` alongside the existing `ForgotPasswordScreen` and routes from a new `ResetPasswordRoute(token: String)` registered on `OnboardingFeatureEntryPoint`.

**Status:** Backlog — gated on the dashboard redirect-URL entry, not on engineering.

---

## Investigate iOS pre-splash black frame on cold boot

**Idea:** On a fresh iOS install, observed a brief black screen before the launch-screen splash renders on cold boot. Don't know yet whether it's iOS's own pre-launch frame, our `LaunchScreen.storyboard` taking a tick to load, or something in `iOSApp.swift` blocking the first paint. Worth a focused look before assuming a fix.

**Sketch directions when revisiting:**
- Confirm whether the black frame is iOS-managed (between `application(_:didFinishLaunching...)` and the launch storyboard rendering) or app-side. A screen recording on a fresh install + Instruments → App Launch trace would localize it.
- If app-side: check whether `iOSApp.init` does synchronous work (DI graph construction via `IosAppComponentFactory.create`, Sentry init) on the main thread before the launch storyboard yields. The Compose framework load (`ComposeApp.xcframework`) also happens here.
- If iOS-managed: launch storyboard background color / image alignment is the only lever — confirm `LaunchScreen.storyboard` matches the Compose splash's background so the transition is invisible.

**Status:** Backlog. No known impact beyond the visual; flagged for an investigation pass next time someone touches the iOS launch path. Don't preemptively "fix" — the answer might be "iOS does this and there's nothing to do."

---

## Guard `ChipsRepositoryImpl.applyDeltaInternal` against duplicate idempotency keys

**Idea:** [`ChipsRepositoryImpl`](../libraries/cards/impl/src/commonMain/kotlin/com/cards/libraries/cards/impl/ChipsRepositoryImpl.kt) writes to two tables on every local chip mutation: `walletEventDao.insert(...)` (primary key on idempotency key, `OnConflictStrategy.IGNORE`) and `chipsDao.applyDelta(...)` (no key awareness). If the same idempotency key arrives twice, the wallet event is dedup'd to one row but the local balance is double-applied. The next `setBalance` from the server's authoritative sync reconciles, so end-state is correct — but the optimistic window shows a wrong balance to the user.

**Why it's a latent foot-gun, not a real bug today:** callers don't re-issue `addChips` / `subtractChips` with the same key. The retry path is the sync loop, which uses `setBalance` (not delta), so the duplicate-key case is currently unreachable from production code. Pinning the current behaviour in `ChipsRepositoryImplTest.addChips_withDuplicateIdempotencyKey_dropsTheSecondEvent_butStillAppliesDelta` keeps a future change deliberate.

**Sketch:** one-line check in `applyDeltaInternal` — query `walletEventDao` for the idempotency key before applying the delta; if present, no-op the delta (the event was already accounted for). Update the pinned test to assert single-application.

**Status:** Backlog. Defensive, not blocking — there's no observable user-visible bug today. Pick up if `ChipsRepository` ever grows a caller that could re-issue with the same key (offline retry queue, multi-write replay, …).

---

## OpenTelemetry log tree on the client

**Idea:** Today the only structured client telemetry leaving the device is Sentry breadcrumbs + events ([`SentryLogTree`](../libraries/cards/impl/src/commonMain/kotlin/com/cards/libraries/cards/impl/AppTelemetry.kt)). That covers error monitoring, but not dashboards — we can't slice "claim-failure rate per app version" or "warn-level frequency on degraded network" without an aggregated log/metric pipeline. The Grafana Cloud OTLP endpoint we're already planning to use for server traces (see [developer-todo.md → Dashboard / external-service config](./developer-todo.md)) accepts client OTLP just as cleanly — slot the clients in by planting a third tree alongside Kermit and Sentry.

**Sketch:**
- New `OpenTelemetryLogTree(otel: OpenTelemetry, defaults: DefaultLogAttributes)` next to the existing `KermitLogTree` + `SentryLogTree` in [`libraries/cards/impl/.../AppTelemetry.kt`](../libraries/cards/impl/src/commonMain/kotlin/com/cards/libraries/cards/impl/AppTelemetry.kt). Planted at the same boot site via `KLog.plant(...)` — the existing tree contract carries everything we need (`LogEntry.level`, `scope.tags`, `throwable`); the tree just maps to the OTel SDK's logs/events API.
- `isLoggable` filter: **Info and above in release**, Debug in debug builds. Mirror the cutoff `SentryLogTree.minBreadcrumbLevel` already uses so the two trees stay in lockstep.
- `DefaultLogAttributes` snapshot installed once at boot, refreshed on auth/connectivity change. Lookup is synchronous (logging is hot), so cache a `MutableStateFlow<DefaultLogAttributes>` and read `.value` at log time. Fields:
  - `platform` (`"android"` / `"ios"`) — known at install
  - `app.version` + `app.version_code` + `app.release_channel` — from [`BuildInfo`](../libraries/core/src/commonMain/kotlin/com/cards/libraries/core/BuildInfo.kt)
  - `device.model` + `device.os_version` — platform expect/actual
  - `install_id` — from [`CachedInstallIdProvider`](../libraries/cards/impl/src/commonMain/kotlin/com/cards/libraries/cards/impl/CachedInstallIdProvider.kt) (`AppCache.installId`)
  - `user.id` + `user.is_anonymous` — updated via the same `AppTelemetry.setUser(...)` hook Sentry uses
  - `connectivity` (`"online"` / `"offline"`) — last-known value from [`ConnectivityObserver`](../libraries/networking/impl/src/commonMain/kotlin/com/cards/libraries/networking/impl/ConnectivityObserver.kt), refreshed on every flow emission
  - `session_id` — minted per-process so a dashboard can collapse "events from one app launch"
- One-pass cleanup at planting time: audit existing `logger.d` / `logger.v` callsites and **promote to Info** anything we'd want to query in prod (e.g. claim-account submit outcome, IAP outcomes, websocket open/close, room-join start/finish, anon→claimed transitions); **demote to Debug** anything that's just engineer-trace noise (every flow emission, every state mutation, per-frame work). The filter is the dashboard's bill of materials — what flows up is what we can graph.

**Obfuscation watch:**
- R8/ProGuard collapses class names, so any tag/attribute derived from `::class.simpleName` ends up `a$b` in release. Use stable string keys at the callsite (`logger.withTag("auth.claim")`, not `logger.withTag(this::class.simpleName!!)`). Same goes for the `LogEntry.tag` chain — none of it should leak the obfuscated name.
- Exception types in `Throwable.recordException(...)` *are* the obfuscated name. The OTel SDK already records the message + stack; for the dashboard to be useful, log the operation name as a separate attribute (`op = "supabase.linkEmailIdentity"`) so queries don't depend on the post-R8 class.

**Dashboards this enables (worth designing for):**
- **Funnel:** anon → claim-submit → verify-email-link-tapped → claimed. Today we can only see crashes; with structured Info logs each step is a row.
- **Error rate per app version × platform.** Catches release regressions before Play / TestFlight crash-free rate does.
- **Degraded-network sessions.** Filter `connectivity = "offline"`-tagged warns/errors to estimate how much of the support backlog is network rather than bug.
- **WebSocket session quality.** open-duration, reconnect count, close-reasons grouped by `room.code` (server side already traces these — pairing client + server traces via trace ID gives end-to-end).
- **IAP outcomes by store.** `store = google/apple`, `outcome = success/cancelled/store_unavailable/...`. Already structured in `ShopEvent.PurchaseFinished` — surface it.

**Tradeoffs:**
- One more SDK on the cold path. Kotlin OTel for KMP is still moving — the JVM SDK is stable, Kotlin/Native is newer; on iOS we may want a thin OTel-over-URLSession exporter rather than the full Kotlin/Native SDK if size or boot time bites. Validate with a release-build APK/IPA size delta before committing.
- Cost — Grafana Cloud free tier (50 GB logs / 14-day retention) is generous, but Info-level from every client adds up faster than server traces. Tune sampling (`isLoggable` per-tag) if we cross the threshold.
- Doubles up on Sentry breadcrumbs for the same line. That's fine — Sentry stays the crash-attribution surface (it has stack-symbolication + alerting we don't want to rebuild); OTel is the queryable analytics surface. Split of concerns is intentional.

**Status:** Backlog. Pairs with the Grafana Cloud OTLP signup in [developer-todo.md](./developer-todo.md) — that endpoint is the destination. Pick up alongside the next observability batch (server already has `ws_send` span-link plumbing landed; client-side OTLP would close the trace ↔ log correlation across the wire).

---

## Stale-intent semantics on the room socket's outbound buffer

**Idea:** The reshape in [`ReconnectingRoomSocket`](../libraries/rooms/impl/src/commonMain/kotlin/com/cards/libraries/rooms/impl/ReconnectingRoomSocket.kt) buffers outbound [`ClientFrame`](../libraries/rooms/src/commonMain/kotlin/com/cards/libraries/rooms/ClientFrame.kt)s in a 32-slot channel; the writer drains them on the live WS, and queued frames re-send across a reconnect. Server-side nonce dedupe handles the duplicate-after-reconnect case. But `ClientFrame.SubmitIntent` carries a *per-turn-state* action — bet 200, fold, call — and the engine state has moved on by the time the WS comes back. Sending the stale intent after the reconnect either gets rejected by the validator (best case) or applied to a different street than the user thought they were acting in (worst case).

**Why it's mostly latent today:** the user can't see "your turn" while disconnected (the gameplay state flow stops), so they don't usually submit intents during a drop. The hole is when the WS drops *while* a `SubmitIntent` is mid-flight in the outbound buffer — the gameplay UI thinks the action was sent, the user sees the act-button greyed out, the server never receives it; on reconnect it does receive it but the engine has either moved past their seat or finished the hand.

**Sketch:**
- Split outbound semantics by frame: `StartHand` / `RequestNextHand` are idempotent over the lifetime of a hand → buffer normally. `SubmitIntent` is bound to *this connection* → drop it on disconnect (don't re-send), and surface the dropped intent as a synthetic `GameplayFrame.IntentAck(accepted=false, error="connection_lost")` so `RemotePokerSession.submit()` can resolve its `CompletableDeferred` with a rejection.
- Implementation: a per-connection scratch channel for connection-bound frames (drained-or-dropped with the writer), separate from the durable outbound buffer for idempotent frames. Or: tag frames at the call site (`send(frame, connectionBound = true)`) and let the socket route them.
- Alternative: keep a single buffer but inspect each frame on writer-restart and drop intents older than the current `GameState.lastSequence`. More server-coupled.

**Tradeoffs:**
- Adds complexity to the socket layer that today's V1 doesn't strictly need (intents rarely race a disconnect).
- The drop-on-disconnect path requires `RemotePokerSession` to wire the synthetic ack into its nonce table — modest, but a real coupling.
- Without this, the only safety net is the engine validator rejecting actions that don't fit the current state. That's a real (if ugly) backstop.

**Status:** Backlog. Pick up when MP playtests show the symptom or when the engine validator's rejection messaging is verified non-confusing for end users. Pairs with [Lobby host-promotion banner](./decisions.md) — anything that gates on "did my last action go through?" wants this fixed.

---

## PlayStyleBlob reuse — Profile's style banner + seat-tap sheet

**Idea:** The new `PlayStyleBlob` in `:libraries:ui` is the canonical TIGHT/LOOSE × PASSIVE/AGGRESSIVE visual. Profile still renders its own `StatsStyleBanner` with a `DecorativeBlob` for the "Sharp & Steady" example; the in-game seat-tap sheet would also benefit from the same quadrant. Once a real tightness/aggression metric exists, fold both surfaces onto `PlayStyleBlob` so the visual reads identically everywhere and the example/placeholder names line up.

**Status:** Backlog. Pick up alongside the data work for a real play-style metric — same diff.

---

## Surface a reason when a multiplayer intent is rejected / the room closes

**Idea:** Two recently-hardened MP paths now fail *safely* but *silently* — they no longer crash or strand the user, but they also don't tell them what happened:

- **Rejected / timed-out intent.** `PlayPokerViewModel`'s `Submit` now wraps `session.submit()` in `Catching {}` and logs on failure, so a server rejection ("not your turn" on a turn-race double-tap) or a 10s ack timeout is a logged no-op instead of an app crash. But the user just sees their action quietly not happen. `RemotePokerSession`'s KDoc already promises "the VM maps this to a UI-level 'not your turn' surface" — that mapping still doesn't exist. Wants a new state field/event + a transient surface (toast / inline pill), not the haptic-only `TurnFeedback` channel.
- **Room closed out from under us.** `PlayPokerEvent.RoomClosed` now pops the play screen when the server GC's the room or rejects the subscription. The pop is silent; the lobby/home it lands on re-observes and shows the closed-room state itself, but there's no transient "this room was closed" message on exit.
- **Wallet debit refused on sync.** `ChipsRepositoryImpl.syncLocked()` handles a server `InsufficientChips` outcome by dropping the pending event, logging a warning, and resetting to the authoritative balance — the user's chip total silently jumps with no explanation (`docs/wiki/wallet.md` documents the silence).

**Why grouped:** all the same shape — a server-side "no" the client currently absorbs without a word. A shared lightweight "transient game-event surface" (toast/snackbar bound to one-shot VM events) would cover both and any future case.

**Status:** Backlog. Pick up when MP playtests show the silence is confusing; the intent-rejection half is the KDoc's outstanding promise.

---

## Player Card — Phase 2: opponent cards over the wire

**Idea:** Make a tapped *human opponent's* Player Card show their real identity — equipped badges and title, and level — not just name + avatar. Today none of that flows to other seats: `SeatView` carries name/emoji/handsAtTable, `equippedTitle`/`equippedBadgeEmoji` exist only on the local human seat, and remote-human `seatBadge` (level) is null pre-fetch. Phase 1 (see [decisions.md](./decisions.md) 2026-06-06) ships the owner-facing card + the shared `PlayerCard` component; this is the cross-player half.

**Sketch:**
- Server: expose each table participant's public card fields (display name, avatar emoji + bg, equipped title + equipped badges, level) to other players in the room/seat snapshot. The equipped badges/titles cosmetics that now drive the owner's Player Card already land on `/v1/me`; plumb the same equipped-cosmetics shape onto the room/seat snapshot for opponents.
- Client: carry those fields onto `SeatView` (`TableUiState.fromSeat`) and render the shared `PlayerCard` in `PlayerProfileSheet` for opponents, not just the owner.
- Pairs with the existing `docs/todo.md` "Tap-an-opponent sheet — view full profile" item.

**Status:** Backlog (V1.x). Do this when human-vs-human is common enough to matter — V1 is mostly bots, which already render their own info.

---

## Player Card — Phase 3: "scouting" — opponent stats behind an equipped ability

**Idea:** Let a player see an opponent's *stats* (win rate / hands played, maybe richer reads later) on that opponent's Player Card — but only if the viewer has a "scouting" ability/cosmetic equipped. The original feature ask floated stats "if you have that ability equipped"; this is that gated perk. Deliberately out of the V1 card (see [decisions.md](./decisions.md) 2026-06-06) to keep V1 client-only.

**Sketch:**
- A new gating item (cosmetic/ability) the viewer equips; mint it as an earnable/buyable.
- Per-opponent stats on the wire (Phase 2 plumbing extended with a stats block), gated server-side so stats only return to viewers who own the ability — don't trust the client to hide them.
- `PlayerCard` grows an optional stats section, shown only when the viewer is entitled.

**Status:** Backlog. Depends on Phase 2 plumbing + a decision on whether scouting is earned or bought. Revisit once the card itself is live and human play is common.

---

## Level-up screen — aspirational content (names, percentile, unlocks)

**Idea:** The level-up mock (`docs/todo-assets/level-up-screen.png`) shows three things beyond the V1 celebration (see [decisions.md](./decisions.md) 2026-06-06), each needing data we don't have yet:

- **Per-level names** — a title per level ("Calculated" at L7). Needs a curated level→name table (content) and a slot on the screen. Cheapest of the three; pure client content.
- **"Better than N% of players" percentile** — needs a server-computed distribution of levels/XP across the player base + an endpoint to read the caller's percentile. Server work + a freshness story.
- **Level-gated "Unlocked" callout** — "Ranked tournaments / Compete in the Royal Flush" implies a level→feature-unlock map *and* the gated features actually existing (ranked/tournaments/leagues aren't built). The callout slot should stay hidden until there's a real unlock at that level.

**Status:** Backlog. Layer onto the V1 level-up screen as the data lands — names first (content-only), percentile + unlocks later (server + feature work). Keep the teal/progression identity.

## XP Boost active-state UI treatment

**Idea:** When a 2× XP boost is running, make the *whole app* feel boosted rather than just showing a small badge. A global treatment — e.g. a warm tint on the bottom bar + a slim countdown bar — signals the temporary special state. Drive it off a cross-cutting "boost active" signal (a `staticCompositionLocalOf`, the documented pattern for subtree-wide state) so any surface can opt in to reacting; the DS owns the treatment, not the bottom bar.

**On the poker table, stay subtle.** Mid-hand is the wrong place for app-chrome tinting — it competes with gameplay. The XP chip already sits up top; bolt the countdown onto it there and leave the felt/bottom-bar alone.

**Watch:** a perpetually-ticking countdown is a 1s recomposition driver — keep it cheap (isolate the ticking node) and self-hiding on expiry (the existing `XpBoostBadge` already self-hides).

**Status:** Backlog. Polish on top of the shipped boost mechanic; not a V1 gate.

---

## Level-up reveal — show the cosmetic's real name, not "New item"

**Idea:** The level-up celebration can now gift a cosmetic (felt / card back / title / emote pack), revealed as a generic 🎁 "New item" row. The row doesn't name the actual item because the level-up route has no product catalog injected — resolving `productId → display name` would mean threading a products repo through the route. The generic label is honest (the real cosmetic shows up in inventory/shop), but naming it on the reveal would make the moment land harder.

**Status:** Backlog. Enrichment on top of the shipped cosmetic-reward capability. Do it when threading a catalog read into the level-up entry point is worth the wiring (or once the celebration screen already reads the catalog for something else).

## Batch the profile-resolution endpoint's lookups

**Idea:** `GET /v1/profiles?ids=…` resolves each id with a separate `ProfileRepository.findById` call in a loop, so a batch of N ids fires N sequential DB round-trips (capped at 100). It's a thin shell over the existing single-id read, fine while the social lists are short, but a player with a large friends list pays one query per tile. Add a `findByIds(ids): List<Profile>` batch read (single `WHERE id IN (…)`) on `ProfileRepository` and have the route use it.

**Status:** Backlog. Correctness-neutral efficiency follow-up; do it when the social lists get long enough to feel the N+1, or when `ProfileRepository` grows a batch read for another caller.

## Shared `Syncable`/`SyncCoordinator` registry (offline-first Phase 7)

**Idea:** Extract `Syncable { suspend fun sync() }` + a `SyncCoordinator` (AutoInit) that owns the trigger wiring once (foreground/connectivity/UserChanged/AccountClaimed/session-rollover, with the `isColdBoot` skip and the Authenticated gate centralized), fanning out with per-syncable error isolation + structured logs. The 5 sync repos (Chips/Progression/Achievement/Equipment/Inventory), the messages/rooms syncs, and the profile outbox become plain `Syncable`s, deleting their duplicated `AppEventListener` boilerplate.

**Status:** Backlog. Pure sustainability refactor; do after Phase 6 so the profile outbox folds into it. Plan at `delegated-crunching-gem.md`.

## Surface a "couldn't add friend" message when a request is rejected

**Idea:** The recently-played-with shelf flips the "Add" tile to "Sent" optimistically and silently reverts it if the server rejects the request (not-played-with, rate-limited, or a network error). The revert is correct but invisible — the user just sees the tile flick back with no explanation. Once a global snackbar/toast surface exists, show a short reason ("You can only add people you've played with", "Too many requests, try later", "Couldn't reach the server") off the typed `SendFriendRequestResult` cases the repo already returns.

**Status:** Backlog. The repo already distinguishes the rejection cases; this is the user-facing message on top. Do it when the app grows a shared snackbar host.

---

## Stats lifetime-grid labels should be string resources

**Idea (raised 2026-06-22):** `StatsScreen.LifetimeStatsGrid` passes inline string literals for every `StatTile` label ("Hands played", "Hands won", "Win rate", "Fold rate", "Folds", "Showdown losses") — the win-rate / fold-rate tiles added this cycle matched the file's existing inline-label convention rather than introducing two lone resource entries. Per the coding guideline every user-facing string belongs in `:libraries:resources`. Convert the whole grid's labels in one pass so it's consistent rather than half-migrated.

**Status:** Backlog. Pre-existing convention drift across the whole grid; do it as one sweep when next touching `StatsScreen`.

---

## More stats-page metrics — "Best Hands" / "Biggest Pots"

**Idea (feedback CARDS-1W, 2026-06-23):** Owner floated adding richer stats-page entries like "Best Hands" and "Biggest Pots" ("or something idk"). Needs a product call on which metrics are worth tracking and a per-hand result record to back them (the per-hand decision-capture / per-game result work in `docs/todo.md` is the prerequisite data source).

**Status:** Backlog. Fuzzy feature idea; gate on a per-hand/per-game result store existing. Sentry [CARDS-1W](https://elijah-dangerfield.sentry.io/issues/CARDS-1W).

---

## Surface the legal-consent record + a "Terms changed, re-accept" gate

**Idea (raised 2026-06-23):** Onboarding now silently records which Terms/Privacy version the user accepted (by proceeding past the Welcome step) plus a timestamp, into `AppData.acceptedLegalVersion` / `legalConsentAcceptedAt`. Two follow-ons sit on top, both deferred until legal asks: (1) surface the acceptance record somewhere (e.g. a read-only line in Settings or an exportable audit field) so it's not write-only; (2) a re-consent gate that compares the persisted `acceptedLegalVersion` against the live `LegalUrls.LEGAL_VERSION` and re-prompts when the hosted docs materially change (bump `LEGAL_VERSION` to trigger).

**Status:** Backlog. The audit record is captured; these consume it. Gate on legal/owner deciding the audit surface + re-accept UX are wanted.

## Matchmaking chooser — richer table cards

**Idea (raised 2026-06-24):** The new pick-a-table chooser (`PublicSearchingScreen.ChoosingContent` / `CandidateCard`) lists each candidate with buy-in, seats taken/max, and a real-human count. Two deferred niceties: (1) show the humans-vs-bots split more richly than a single "N playing" line (e.g. seat dots, or "3 players, 1 bot"); (2) mark a table the caller is already seated in with a "you're here" badge — the server already includes such a table in the candidates list per its KDoc, the client just renders it the same as any other today.

**Status:** Backlog. Cosmetic polish on a shipped, functional chooser. Do when next iterating on matchmaking presentation.

---

## XP anti-cheat hardening — server-derive XP when stakes rise

**Idea:** The server stores client-computed XP deltas with a per-event clamp. That's fine for play-money — there's nothing to mint. When XP gates ranked status, leagues, or IAP-equivalent rewards, the trust model has to flip: the server should **derive** XP from synced hand facts (using the same curve it already serves to the client) and enforce caps / rate-limits / claw-back, instead of trusting whatever delta the client posts.

**Trigger to revisit:** the first feature that makes XP convert into real value (ranked tier, league placement, exclusive cosmetic gates, IAP discounts tied to level).

**Hints:** Same pattern as ENG-9 (server-authoritative rewarded chips) in [`docs/todo.md`](./todo.md) — port the level/XP curve interpreter from `:libraries:cards` into `:apps:server`. Anti-cheat principles are summarized in AGENTS.md → "Write-path / grants" (the fuller `state-authority-and-sync.md` wiki page was deleted 2026-06-24; git history has it).

**Status:** Backlog. Explicitly deferred — not worth the engineering until XP gates something a cheater would want.

---

## Responsible-play nudge after risky patterns

**Idea:** A gentle, dismissible nudge after risky patterns (repeated chip buys after going bust, rapid repeat purchases). The Settings row and the real-money purchase-sheet link both already ship; this is the proactive in-app nudge on top. Needs an economy-event hook that doesn't exist yet — `AppEventBus` is lifecycle-level only.

**Status:** Backlog. Owner unsure whether to ship this at all — revisit if user reports or analytics suggest the nudge would land. Retired from todo as AUTH-4.

---

## Multiplayer game summary + recent-games history

**Idea:** No post-game summary exists in MP — the end-of-hand XP/achievement dialog is transient and nothing is stored. When an MP game ends (or a player leaves), show a summary: chips won/lost, XP gained, achievements earned during that game. Persist per-game results so Home can show a "recent games" list, each tapping into its summary. MP only.

**Sketch:** Greenfield — needs a per-game result record (client Room table or a server endpoint; the server already logs `hands_finished`). The chips delta depends on the MP wallet settlement being real (it is). Distinct from the friend-graph `RecentlyPlayedWithStrip` (opponents-to-friend, not results).

**Status:** Backlog. Retired from todo as GAME-2. Pull when player-history surfaces become a V2 priority.

---

## Server-derive level-up grants (when stakes rise)

**Idea:** The server applies whatever `levelup_<level>` chip delta the client sends (only guard: no-below-zero) and gates the cosmetic grant by product allowlist, not by *level reached* — so a tampered client can mint level rewards it didn't earn. Harmless for play-money. When XP/chip totals feed leagues/leaderboards or anything else a cheater would want, switch to: on progression-sync, the server derives level from its reconciled `total_xp` against the curve it already serves, grants `levelup_<level>` itself (idempotent), and caps client-claimed amounts.

**Hints:** Port the `levelProgressFor` interpreter from [`Level.kt`](../libraries/cards/src/commonMain/kotlin/com/cards/libraries/cards/Level.kt) into `:apps:server` (don't extract a shared module — see `decisions.md` 2026-06-21). Grant precedents: `PostgresWalletRepository.apply` + `LevelGrantableProducts` / `GrantsRoutes`. Client path: `LevelUpRewardGranter`.

**Status:** Backlog. Same trigger as the XP anti-cheat hardening item above. Retired from todo as PROG-2.

---

## Pick-a-Card reward chest

**Idea:** A consumable chest: open → magician-style card-shuffle/reveal → a prize (chips / card back / felt / boost) from a weighted, server-owned loot table. Server rolls + grants on open (idempotent); the client only animates + reveals the server's result. Online to open; ownable offline ("opens when you reconnect"). Giftable on level-up.

**Phasing:**
- **Phase A — inventory quantity + consumable kind:** add `quantity` (stockpile) + a consume path to inventory (today it's one permanent row per product) and a `chest_` product kind.
- **Phase B — server chest-open:** `POST /v1/me/chest/{id}/open` rolls the weighted loot table, grants the prize (chips → wallet ledger, cosmetic → inventory grant), idempotent per open.
- **Phase C — the pick screen:** full-screen pick/shuffle + reveal showing the server-rolled prize; offline "connect to open" gating.

**Hints:** Grant precedent is `grantApi.grantAchievement` / `GrantsRoutes`; chips prize via `ChipsRepository.addChips(idempotencyKey=…)`. Touches wallet, inventory / my-items, shop, and level-up rewards.

**Status:** Backlog. Retired from todo as SHOP-1. Real V1.x/V2 monetization feature; pull when consumables are on the table.

---

## Friends + social — descoped to V2

**Idea:** Friends and social are explicitly out of V1. SOC-2 in todo.md gates all the existing fake-or-stubbed social surfaces (FriendsStrip, recently-played-with shelf, friend-requests inbox, at-table Add Friend) behind a config flag defaulted off. When V2 revives the feature, the V1 plumbing left behind the flag becomes the starting point.

**V1 work that ships behind the flag (server-side, currently fake or no-op):**
- Online-presence signal — server emits presence on WS connect/disconnect + stores last-seen / current-room; client subscribes once per session, filtered to friend ids.
- Real friend graph backing the strip + the "recently played with" shelf (today the strip uses `defaultOnlineFriends()`; the shelf is a stubbed list).
- Friend-request inbox + accept/reject flow (today the badge is real, the inbox isn't).

**Locked rule for the V2 revive:** the only way to friend someone is the "recently played with" shelf — no search-by-handle, no suggestions. Empty states must say so.

**Out of scope even for V2 revive:** friend suggestions, invite-via-share-link, push notifications for requests, group chat.

**Status:** Backlog (V2). Retired from todo as SOC-1 (presence signal). The flag-gating work that disables these surfaces in V1 lives in todo.md as SOC-2.

---

## MP lobby shows $0 buy-in + 409 on POST /bots after sole-human-left rebound

**Symptom (owner repro 2026-06-24, room NP2DDJ):** After Player A leaves a 2-player private room, Player B (now sole human) is routed back to the in-room lobby view. The lobby reads **buy-in $0** and "waiting for players" instead of the room's actual buy-in, and at some point fires a POST `/v1/rooms/{code}/bots` that the server rejects with 409 NotJoinable (room status still `Playing`). The two big-ticket bugs from this repro — A stranded on the play screen, B getting a stacked duplicate Lobby — are fixed in `feat(navigation): class-based popBackTo` and `fix(room): pop existing lobby on OpponentsLeft`. The $0 buy-in + 409 are residuals.

**What's not yet pinned down:**
- Whether `room.buyIn` is genuinely 0 (room creation default issue?) or the LobbyScreen is rendering a stale `Room` snapshot from the auto-rejoin path that the duplicate-lobby fix now eliminates.
- Whether the server's `RoomStatus` is failing to drop back to `Lobby` after a one-hand session ends, or whether the client is triggering an automatic bot-add at the wrong moment.

**Action:** repro fresh with the just-shipped inbound-WS-frame logs (the `recv game_state hand=… street=… …` lines now ride in feedback `session-log.txt`). Those will say exactly which `RoomStatus` and `buyIn` the client saw in the snapshot it rendered, which disambiguates the two hypotheses without another guess-and-check.

**Status:** Backlog. Pull when next a similar report comes in; the new client-state.json attachment + frame logs should make it a one-pass triage.

---

## Flaky integration tests: multiplayer socket-lifecycle suite

**Symptom:** Two `:apps:integration` tests time out (`TimeoutCancellationException`) intermittently on full-suite runs, while passing reliably in isolation — a real-time socket-timing race that surfaces under JVM load:
- `LobbyLifecycleTest.hostLeaves_promotedMemberCanStart` — fails roughly 1-in-3 full-suite runs on a clean tree (reproduced by a worker on `origin/develop` with all in-flight changes stashed and the new server-restart test removed — still failed ~1/4).
- `SetupJourneyTest.hostDropsAndReconnectsFast_bothClientsAgreeOnExactlyOneHost` — observed timing out alongside it on a CI run whose changes (offline lobby error copy, an AppData flag reset, verify-banner strings) don't touch the socket path.

**Action:** Stabilise the host-leaves → promoted-member-can-start and host-drop → single-host-agreement paths — likely needs longer / polling awaits on the promotion-and-reconnect steps rather than one-shot assertions, matching the suite's "real time + generous timeouts, never fixed sleeps" convention. Not introduced by any current PR; first flagged during MP-2 review.

**Status:** Backlog. Pull when stabilising the integration tier.

---

## Bound the room-socket handshake-retry path too

**Context:** MP-8 bounded the *connected-then-dropped* reconnect path — a socket that completes the WS handshake but never delivers a frame now gives up after 6 attempts (`ClosedReason.ReconnectFailed`). The separate *handshake-retry* path (5xx responses / transport-level handshake failures, tracked by `consecutiveFailures` in `ReconnectingRoomSocket`) still retries unboundedly by design — those are treated as the server's transient problem.

**Action:** Decide whether to unify the two ceilings so a persistently failing handshake (e.g. server hard-down) also lands on a terminal state instead of looping forever, or keep them deliberately separate. Low urgency — the reported storm was the connected-then-dropped path, which is now fixed.

**Status:** Backlog. Triage when next touching the reconnect loop.

---

## Stale / abandoned Compose UI-test harness in `:apps:compose` (MP-2)

**Symptom:** `:apps:compose:testDebugUnitTest` fails to compile on a dirty build dir with `Unresolved reference 'TestAppComponent' / 'TestProfileRepository' / 'testUserId' / 'OnlineConnectivityObserver'`. The offenders live only in **generated KSP output** (`apps/compose/build/generated/ksp/android/androidUnitTestDebug/.../KotlinInjectTestAppComponent.kt` + `InjectKotlinInjectTestAppComponent.kt`) under package `com.cards.uitest.harness` — there are **no committed source files** for that harness. A `rm -rf apps/compose/build/generated/ksp/android/androidUnitTestDebug` clears it and the module's android unit tests go green.

**Context:** Looks like a prior, incomplete Compose-UI-test scaffolding attempt (the kind MP-2's remaining sub-item calls for — `PlayPokerScreen` UI tests) that was started, partially generated, then deleted/uncommitted, leaving orphan KSP artifacts behind. The standing `apps/compose/androidUnitTest` source set is otherwise just `commonTest`.

**Action:** When picking up MP-2's Compose UI-test sub-item, start from a clean `:apps:compose` build dir, and decide whether that `uitest.harness` shape (a kotlin-inject `TestAppComponent` for UI tests) is the intended foundation to revive or to discard. Either way the orphan generated artifacts shouldn't be relied on.

**Status:** Backlog. Triage against MP-2's remaining Compose-UI-test work.

---

## Extend the PlayPokerScreen Compose UI-test harness

**Context:** The first host-side Compose UI-test harness now exists on `:features:room:impl` (Robolectric + `runComposeUiTest`), with a 12-test suite over `PlayPokerScreen`'s rendered states. By design it asserts only on screen-owned chrome (connection banner, dealing-in placeholder, back affordance), not the deep action-bar internals, to stay robust against DS churn.

**Action:** Two follow-ons now the harness exists: (1) add per-action-button coverage (Fold/Call/Raise visible and tappable on the human's turn), accepting the brittleness tradeoff; (2) extend the same harness shape to other feature screens that warrant UI tests. Neither is filed elsewhere.

**Status:** Backlog. Pull when extending UI-test coverage.

---

## Fold HandRankingsCheatSheet off the `BaseBottomSheet` escape hatch

**Idea (raised 2026-06-25):** `HandRankingsCheatSheet.kt:249` uses `BaseBottomSheet` (the `@LowLevelDSComponent` raw layer) because the opinionated `BottomSheet` wrapper doesn't yet expose the content shape it needs (custom drag handle / full-bleed scrollable body). AGENTS.md flags this as an escape that "should resolve by extending `BottomSheet`, not entrenching" — extend the opinionated layer with the missing slot, then route the cheat sheet back through it.

**Why it's backlog, not a worker one-liner:** the fix is a DS design call (what content/handle override to add to `BottomSheet`), not a mechanical swap. Do it when next touching the bottom-sheet primitive.

**Status:** Backlog. Design judgment on the DS surface; AGENTS.md references it.

## Hand-rankings cheat sheet v2 — "you have" banner + compact rows

**Idea (owner mock):** Upgrade `HandRankingsCheatSheet` toward the owner-provided target mock: a summary banner at the top showing the user's current hole+board cards with "YOU HAVE / Two Pair", then a tight numbered 1–10 list (Royal Flush → High Card) with compact card glyphs per row, the user's current hand row highlighted. Today's sheet has the ranked list and a "YOU" highlight but not the you-have banner or the compact-glyph treatment. Full transcription of the mock: [`todo-assets/README.md`](./todo-assets/README.md) → `hand-rankings.png`.

**Status:** Backlog. UX polish with layout design calls; pairs naturally with the BaseBottomSheet fold-back above.

## "Recent XP" rows on Stats

**Idea (owner mock):** A "Recent XP" section on the stats surface — a short list of recent XP-earning moments, each row = source emoji + what happened ("Won a hand vs Theo", "Achievement · Big bluff") + relative timestamp + a `+N` trailing amount colored by XP source. Gives XP a narrative ("where did my progress come from") instead of just a total. Needs a local recent-XP-events feed (the `xp_events` ledger already captures per-hand deltas client-side — surface the tail of it). Full transcription: [`todo-assets/README.md`](./todo-assets/README.md) → `recent-xp.png`.

**Status:** Backlog. Not tracked by any todo; pull when the stats screen gets its next pass.

## Burn down the VerifyStrings baseline (extract hardcoded copy to resources)

**Idea (2026-06-25, ENG-2 follow-up):** Standing up the detekt `VerifyStrings` rule baselined 42 existing inline user-facing string literals (`Text("Claim")`, `Text("BUSTED")`, `Text("Report to developers")`, …) in `config/detekt/baseline.xml`. New code is gated, but the baselined ones are real localization/i18n debt frozen in place. Sweep them into `:libraries:resources` (`stringResource(...)`) and shrink the baseline toward empty — each extraction removes its baseline entry. Mechanical but spans many files; do it as a focused sweep, not piecemeal.

**Status:** Backlog. Engineering hygiene, not V1-blocking (the app is English-only for V1); pull when localization or a UI-copy pass comes up.

## Audit follow-ups (lower priority, 2026-06-25)

**Idea (client/lifecycle audit):** Two lower-impact findings from the MP audit, parked rather than filed as todos:

- **Solo Showdown/Bust dialogs dismiss on scrim-tap → silently advance.** `ShowdownDialog` (`onDismissRequest = onNextHand`) and `BustDialog` (`onDealMeIn`) in `HandResultDialogs.kt` advance the hand on a stray outside-tap. Lower harm than the MP real-chips ejection (already fixed) — for solo it may even be intended "tap to continue." Decide whether to lock them with `ModalDialogProperties(dismissOnClickOutside = false)` or keep the convenience.
- **`AccessDeniedScreen` with no appeal URL is a dead end.** `AccessDeniedScreen.kt` blocks back (`BackHandler { }`) and only shows an "Appeal" button when `appealUrl != null`; a banned user with no appeal URL configured has text and no actionable control, and the route is `NavigableWhileBlocked` so it can't be popped until auth state changes. Not MP-specific; an auth/ban-path edge. Ensure an appeal URL is always configured, or give a fallback action.

**Status:** Backlog. Both are real but low-frequency; pull when touching the dialogs or the ban gate.

## Backend-driven achievement definitions (PROG-1 — future consideration)

**Idea (2026-06-25):** PROG-1 is done — progress, unlock, celebration, and earning are all server-authoritative and offline-correct (one effective-counter source = server snapshot + unsynced outbox, shared fold; the legacy device-only counter DB is retired). The **only** open consideration is whether achievement *definitions* (the catalog: id, criterion, threshold, reward, display) should eventually be **backend-driven** rather than hardcoded in the client.

Today they're client-side, which is the right default: the client needs a bundled catalog for offline anyway, and a genuinely new achievement usually needs new icon/copy → an app release regardless. Backend-driven definitions would only buy release-free *retunes* (a threshold/reward number) or *hot-adds that reuse existing visuals* (seasonal/event achievements) — and because facts are stored, a hot-added one would back-fill from history. If pursued, it slots into the existing app-config mechanism (a `progression.achievements` value with a bundled default, same shape as the level-rewards table), and the server would then also evaluate that catalog if/when reward-granting moves server-side.

Adjacent, also deferred (not blocking): **server-validated reward granting** — the server re-deriving each crossing and granting chips exactly-once (it already does this for *multiplayer* achievements). Skipped because chips are freemium (no cash-out), so a cheated/double-granted reward is in-game inflation, not lost money.

**Status:** Backlog. A future product/ops call, not a gap. See `docs/wiki/achievements.md` for the as-built design + rationale.

## Private-room host sets the table's cosmetics (card face, felt, …)

**Idea (raised 2026-06-26):** Let the host of a private room theme the table for everyone seated — card face / card back, felt, and other table cosmetics — drawn only from cosmetics the host personally owns. Today cosmetics are per-viewer (each player sees their own equipped felt/card back); this flips a private room to a host-chosen shared look.

**Sketch directions when revisiting:**
- Room snapshot carries a host-chosen `tableCosmetics` block (felt id, card-face id, …); clients render the room's cosmetics instead of their own equipped set while seated.
- Gate the picker to the host's owned inventory only — validate ownership server-side on set, don't trust the client's list.
- Decide the default (host's own equipped cosmetics) and whether guests can locally override back to their own look or are pinned to the host's choice.

**Tradeoff:** Reuses the existing cosmetic catalog + equip plumbing, but introduces room-scoped cosmetic state and a server-side ownership check on set — more than a one-line change. Pairs with the felt/card-back cosmetic work already in the catalog.

**Status:** Backlog. Owner flagged it as a backlog feature on capture.

## Pin a room to one machine (room→machine affinity) before scaling the server out

**Idea (raised 2026-06-26):** Live game state is held **in-memory per machine** in [`DefaultGameSessionRegistry`](../apps/server/src/main/kotlin/com/cards/server/game/GameSessionRegistry.kt) (`MutableStateFlow<Map<code, GameSession>>`), with a Postgres snapshot (`room_sessions` via `SessionSnapshotStore`) written through on every mutation for server-restart recovery. WS clients attach to the live session via `observeSession`. This is correct on **one** machine. `apps/server/fly.toml` keeps `min_machines_running = 1` with `auto_stop`/`auto_start`, so today there's effectively a single warm machine and the model holds.

**The hazard the owner flagged ("misses"):** the moment a second machine runs concurrently (Fly autoscaling under load), two players in the same room can land on different machines. Each machine hydrates its *own* `GameSession` from the snapshot; an intent applied on machine A never reaches machine B's subscribers in real time — the snapshot store is a restart-recovery store, not a cross-node bus. Result: dropped/stale frames and divergent table state.

**Two ways to fix (design call when scale-out is on the table — they're alternatives):**
- **(a) Room→machine affinity.** Route every socket *and* HTTP intent for a room code to the single machine that owns its session — Fly's `fly-replay` (reply with `fly-replay: instance=<id>`) or a consistent-hash on the room code. Keeps the in-memory + snapshot model intact; one machine owns a room for its lifetime, and the snapshot gives failover if that machine dies. Smaller change.
- **(b) Shared real-time bus.** Back the session fan-out with Redis pub/sub (or Postgres `LISTEN/NOTIFY`) so any machine can serve any room and broadcasts cross nodes. Removes the single-owner constraint; more infra.

**Recommendation:** No action at current scale — one warm machine is correct as-is, so this isn't a V1 gap. Treat affinity (option a) as the **hard gate before enabling horizontal scale-out** (>1 concurrent machine); it's the smaller change and fits the existing architecture.

**Status:** Backlog. Not needed today (single instance); revisit before raising `min_machines_running` / letting Fly run multiple machines concurrently.

## Surface MP winnings on leave (toast or result dialog), not just a silently-updated balance

**Idea (raised 2026-06-26):** A tester (QuickJack56, Sentry CARDS-4F) noted that after a heads-up game auto-ends, the only feedback is the chip balance changing — they suggested we "toast when they leave how much they won or even a dedicated dialog." The MP-21 fix (PR #74) already makes the balance reconcile correctly on the opponent-left / auto-end path, so the *number* is now right; this is the missing **acknowledgement** of the result. Today a player who wins (or loses) on an auto-end is routed Home with no "you won N" moment.

**Sketch directions when revisiting:**
- On the auto-terminal MP paths (opponentsLeft, match-over, settled hand), show a transient "You won N chips" / "You lost N chips" toast or a small result dialog before/while routing off the table.
- Reuse the existing chips-delta the reconcile already computes; don't recompute settlement client-side.
- Decide toast vs. dialog by weight: a pure win/loss number is a toast; the heads-up match-over already has `MatchOverResultDialog` (MP-14) — make sure this doesn't double up with it.

**Tradeoff:** Small, additive UX polish on top of an already-fixed data path. Pairs with MP-14's match-over dialog and the backlog "per-game result record" item.

**Status:** Backlog. Enhancement, not a defect — the underlying stale-balance bug (MP-21) is already fixed. Captured from feedback case `docs/agent/feedback-cases/8d2185b9834542e9abc2be52afdded2d.md`.

## Full session/game recap on leave (hands played, net won/lost, opponents) + cache to stats

**Idea (raised 2026-06-27):** A tester (SteadyEight23, Sentry CARDS-5M) asked for a recap "every game left with the result — amount won or lost, hands played, people played with, maybe even hands the player had," to remove ambiguity about where their money went. This is the broad version of two narrower items already in flight: ROOM-4 (show net win/loss + chips forfeited in the leave-confirm dialog) and "Surface MP winnings on leave (toast or result dialog)" above. Ship those first; this is the superset.

**Sketch directions when revisiting:**
- A leave-time recap surface: hands played this session, buy-in, net won/lost, opponents faced — sourced from the per-game data the table already tracks, not recomputed client-side.
- Cache a per-session/per-game record so the stats page can show recent-game history (pairs with the "more stats metrics — Best Hands / Biggest Pots" backlog item).
- Keep the leave-confirm dialog itself a short recap (ROOM-4 slice); push the richer breakdown to a dedicated recap/stats surface.

**Tradeoff:** Real product/feature work (new surface + a cached game-result record), not a bug. Sits on top of ROOM-4 and the MP-winnings-on-leave toast.

**Status:** Backlog. Feature/owner directive. Captured from feedback case `docs/agent/feedback-cases/bb4c51d9d27844b7a5cdce100dddf2d2.md`, Sentry [CARDS-5K](https://elijah-dangerfield.sentry.io/issues/CARDS-5K).

## Scale chip-stack and pot count-up animations with the Game speed setting

**Idea (raised 2026-06-26):** GAME-6 added a speed preference (Normal / Fast / Instant) that scales the play-screen card-deal and reveal animations via `LocalTableTempo` (`features/room/impl/.../ui/TableTempo.kt`). (Later unified into a single "Game speed" setting that also paces bot think time — the old separate "Bot speed" / "Table speed" pickers were merged, and `GameSpeed` now carries both `animationScale` and `botThinkScale`.) The tester's request named the deal, *chip*, and reveal animations; the deal + reveal are done, but the chip-stack odometer roll (`ChipCoinAmount(animated = true)` → `AnimatedNumberText`) and the pot pill still animate at their fixed pace regardless of the setting.

**Sketch:** The count-up animators live in `:libraries:ui` DS primitives (identity-bearing content the DS deliberately animates), so the scaling has to be threaded into those primitives rather than the play screen — either read an optional speed/tempo from a composition local with a Normal default, or pass a duration-scale param. "Instant" should snap the stack/pot to the final number. Keep the Normal feel byte-identical so non-instant players see no change.

**Tradeoff:** Small polish completing the GAME-6 request; touches a shared DS animator used beyond the table, so it needs a default that leaves every other surface untouched.

**Status:** Backlog. GAME-6's core ask (speed up / instant-skip the deal + reveal) shipped; this is the remaining chip-move sub-part.

---

## Cross-version backwards compatibility for game/state objects

**Idea (owner feedback 2026-06-26, Sentry [CARDS-4S](https://elijah-dangerfield.sentry.io/issues/CARDS-4S)):** We should decide how the app handles game/state objects across client versions before launch — e.g. can a user on app version 1 join a game created by someone on version 2? What happens if not, and how do we degrade gracefully? Owner asks for a recommendation + an implemented system while we're still pre-launch and free to set conventions.

**Threads to weigh:** a min-supported-version / schema-version field on rooms and on the wire frames; server-side gating that refuses (with a clear "update your app" message) rather than letting a stale client misread a newer object; forward-compatible (additive-only) serialization conventions so most version skew is tolerated without a hard gate; and where the version line is drawn (room create, room join, or per-frame). Pairs with the existing app-integrity / min-version thinking in `docs/decisions.md`.

**Status:** Decided 2026-06-27 — see [decisions.md](./decisions.md). V1 uses the simple two-tier rule (additive changes degrade; breaking game-object changes bump `upgrade.minSupportedVersionCode`), with a runtime deserialization-failure fallback (ENG-7) as defense-in-depth. The per-room **capability gate** sketched in "Threads to weigh" is the future-consideration path if we ever need to ship a breaking gameplay feature to some tables without forcing every client to update — not built.

---

## Evaluate / eliminate empty-body POST requests

**Idea (owner feedback 2026-06-26, Sentry [CARDS-4K](https://elijah-dangerfield.sentry.io/issues/CARDS-4K)):** A number of client→server POSTs send an empty body (the session logs are full of `POST … request_body_size=2` sync calls — equipment, play-style, progression, achievements, inventory, etc.). Owner suggests we evaluate those: some may be redundant round-trips that could be collapsed, batched, made GETs, or dropped entirely.

**Sketch direction when revisiting:** inventory the empty-body POST endpoints, classify each as (a) genuinely needed write/sync, (b) collapsible into a single batched sync call, or (c) removable; then trim. Pairs with the existing "Offline-aware retry / deferred queue" and the per-sync-coordinator design — a batched sync envelope would address several at once.

**Status:** Backlog. Fuzzy/evaluative ("maybe we should evaluate those") — scope it with an endpoint inventory first, not a blind change.

---

## Typed next-hand refusal code on the room socket ack

**Idea (deferred from MP-22, 2026-06-27):** When the server refuses a "next hand" request it sends back a free-text `error` string on `IntentAck`. The client now classifies that string to decide whether to show the terminal "waiting for your opponent to rebuy" notice (the genuine can't-deal case) or a quiet "catching up, try again" hint (every transient race). The only coupling point is one mirrored constant, `RemotePokerSession.CANNOT_DEAL_ERROR = "not enough players with chips for next hand"`, which must stay byte-identical to `GameSession.requestNextHand` server-side.

**Why it's safe today:** if the strings ever drift, the failure is graceful in both directions — a healthy table briefly shows the rebuy hint, or a busted table shows the resync hint — never a crash or a stuck state. So this is a robustness cleanup, not a bug.

**Sketch:** add a typed refusal reason (an enum on the wire `IntentAck`, e.g. `NextHandRefusalReason.CannotDeal` / `Transient`) so the client switches on a stable code instead of parsing copy. Removes the string mirror entirely.

**Status:** Backlog. Client-side classification ships in this PR; the server-side typed code is the follow-up.

---

## Mid-session push for the force-update / maintenance gate

**Idea (from ENG-6 verification, 2026-06-27):** The app-wide upgrade / maintenance overlay (`AppGuardGate` → `AppGuardState.from`) recomputes live on every streamed-config emission, so bumping `upgrade.minSupportedVersionCode` raises the blocking overlay over any screen — including an in-session play screen — on the **next foreground transition** (config is fetched on foreground, throttled, never polled mid-session by deliberate design in `OfflineFirstAppConfigRepository`). A client that stays continuously foregrounded mid-hand therefore won't see the gate until it backgrounds/foregrounds.

**Why it's acceptable today:** the cross-version rule (CARDS-4S) is additive-only for game objects; a breaking change that needs the hard gate ships with a coordinated min-version bump, and the room socket already closes a genuinely-unparseable frame as `IncompatibleVersion` (ENG-7) — so an in-game client that would actually choke on a new frame gets a graceful exit even without the overlay. The overlay is the broad "time to update" net, not the per-frame safety mechanism.

**Sketch if revisited:** push a lightweight "config changed" / "force upgrade" signal over the existing room WebSocket (or a dedicated app-wide channel) so a continuously-foregrounded client re-resolves config without waiting for a foreground transition. Avoid reintroducing fixed-interval polling — that was deliberately removed.

**Status:** Backlog. The reactive wiring + z-order are verified (`AppGuardStateTest`); this is the optional "cover the never-backgrounds-mid-hand client" hardening, gated on a product call about whether it's worth a new push channel.

---

## Drain paid-but-unredeemed receipts on app foreground (BILL)

**Idea (deferred from BILL-5, 2026-06-27):** On the server-authoritative purchase path (`billing.realPurchasesEnabled` on), if `/v1/billing/redeem` is unreachable *after* the user already paid at the store, `DefaultPurchaseChipPackUseCase` returns `Failed("redeem_unavailable")` and the purchase stands at the store but is never credited until the user manually re-taps Buy (which idempotently re-redeems). For real money, a transient network blip at the wrong moment means a paying user is left without their chips with no automatic recovery.

**Sketch if revisited:** on app foreground, enumerate any finished-but-unredeemed store purchases (Play Billing's owned-but-unacknowledged purchases / StoreKit's unfinished transactions) and re-POST each to `/v1/billing/redeem` (idempotent on the store transaction id, so a double-drain is safe), then acknowledge. This is the standard "restore / reconcile pending purchases" loop both stores expect anyway.

**Status:** Backlog. Real money-loss-for-user risk — should land before `realPurchasesEnabled` is ever flipped on in prod, alongside the real store listings (BILL-3/4).

---

## Leave a real-chip table before the next hand's blinds post (ROOM-4 secondary)

**Idea (deferred from ROOM-4, 2026-06-27):** ROOM-4 made the leave-confirm dialog show the net a leave settles plus any chips forfeited in the live hand. The secondary owner ask — letting a player leave *before* the next hand's blinds are posted (so they don't forfeit a blind they never wanted to post) — is a turn-flow change (when the leave actually fires relative to the deal), not a dialog-copy change, so it was left out of the dialog-only slice.

**Sketch if revisited:** let a queued leave fire at the hand boundary before the new blinds are posted for the leaving seat — i.e. honor an "I'm leaving" intent during the between-hands window so the player isn't auto-posted into a hand they're trying to exit. Pairs with the existing sit-out / auto-fold machinery.

**Status:** Backlog. Visibility (the net + forfeit callout) shipped in this PR; this is the turn-flow follow-up.

---

## Extract a shared `:libraries:gameplay:testing` deck-scripting helper (ENG)

**Idea (from MP-25, 2026-06-27):** The deck-scripting test DSL — `cards("As Ad")` parse + a `stackedDeck(holeBySeat, board)` builder that pads a deterministic 52-card `Deck.fromOrdered` so the engine deals exactly the spelled-out cards — is now copy-pasted across three test surfaces: `:apps:integration` (`helpers/DeckScripting.kt`), `:features:room:impl` (`harness/ScenarioDecks.kt`), and an inline copy in the server's `GameSessionShowdownTest`. Both existing copies already carry a "unify into a shared gameplay-testing module if a third consumer appears" note — the third consumer has now appeared.

**Sketch if revisited:** create a `:libraries:gameplay:testing` module (mirrors `:libraries:flowroutines:testing`) exporting `cards()` + `stackedDeck()`, depend on it from the three `commonTest`/`androidUnitTest`/server-test sources, and delete the three copies. Small, mechanical; the only friction is wiring a new Gradle module + its `jvm()`/android/ios targets to match each consumer.

**Status:** Backlog. Pure test-infra DRY; no product impact. Kept the inline copy in MP-25's fix to avoid a module refactor riding on a bug fix.

## Achievement award timing — the delayed-drip queue reads as "earned a while ago"

**Idea (owner feedback 2026-06-30, Sentry [CARDS-72](https://elijah-dangerfield.sentry.io/issues/CARDS-72)):** Achievements aren't surfaced when they're earned — they drip out roughly a hand late, as if there's a queue that only shows ~two at a time. Against bots, where unlocks come fast, that backs up into a queue the user never catches up on, so an achievement toast for something that happened several hands ago is confusing. Two entangled threads: (1) the **presentation queue/pacing** — show earned achievements promptly (or at least labelled to when they were earned), don't let a backlog form; and (2) the **root cause of the flood** — too many achievements are trivially earnable, several against bots, so consider gating some behind real (non-bot) play and, per the existing catalog item, growing the set (owner floats 75–100). Thread (2) overlaps the existing backlog item "More achievements + early-stage pacing rebalance" (CARDS-1A) — fold them together when this gets a pass; this item adds the award-*timing*/queue defect.

**Status:** Backlog. Product + UX call on the achievement drip/queue plus a content pass; pull with the achievement-system rework. Owner directive.

---

## Fold the settled balance into the terminal room frame for involuntary teardowns (MP-29 follow-up)

**Idea (from MP-29, 2026-06-30):** MP-29 made a *voluntary* leave a synchronous cash-out — `DELETE /v1/rooms/{code}/me` returns the settled balance in its body, so the leave call *is* the wallet reconcile. The **involuntary** teardown paths (heads-up match-over, last-opponent-left, host-closed-room, kick) have no REST leave to answer, so they still reconcile client-side via a `ChipsRepository.sync()` fallback in `PlayPokerViewModel.reconcileWalletAfterGame()`. That sync is no longer latched (it can retry, which fixes the "stuck stale until foreground" half), but it's still a *pull* that can momentarily read a pre-settlement balance before landing the right one.

**Sketch if revisited:** the server already cashes these players out over the per-room socket (`RoomSocketRoutes` — `settleLeaver` on `MemberLeft`, the `departedSettlements` settler, the match-over/teardown paths). Attach the resulting authoritative balance to the terminal frame the client reads on teardown (`RoomConnection.Closed` / the match-over / opponents-left signals) so the client applies it via `setBalance` exactly like the voluntary path, and drops the `sync()` fallback entirely. Needs a wire field on the terminal socket events + the client threading it into `reconcileWalletAfterGame(settledBalance = …)` (the param already exists).

**Status:** Backlog. The voluntary-leave half (the recurring CARDS-5R / 3E cluster) shipped in MP-29; this closes the residual pre-settlement-flash window on the auto-end paths. Money is safe either way — this is a UI-freshness hardening.

---

## Server-side ownership check for host-picked table cosmetics (SHOP-5 follow-up)

**Idea (from SHOP-5, 2026-06-30):** The create-room table-look picker offers only the host's owned felt / card back (built from `inventory ∩ catalog` in `LobbyFeatureEntryPoint`), so ownership is enforced by construction on the client. The server accepts the picked ids verbatim without re-checking ownership. A hand-crafted request could pin a felt / card back the caller does not own onto a room — cosmetic only, no economy impact (nothing is spent or granted), but it is a trust gap. If we ever attach paid stakes or unlock-gating to specific cosmetics, validate the picked ids against the caller's server-side inventory in the create-room handler.

**Sketch if revisited:** validate `feltProductId` / `cardBackProductId` against the caller's wallet/inventory in the room create path; on a miss, drop the override (fall back to default) rather than reject the create.

**Status:** Backlog. Freemium cosmetics, low stakes — the render path is additive-only, so an unknown / unowned id degrades to the default table look rather than crashing (covered by the cross-version rule above). Deferred as a hardening, not a bug.

---

## Restore a guest identity offline for account-gated surfaces (AUTH-11 follow-up)

**Idea (from AUTH-11, 2026-06-30):** AUTH-11 fixed the misleading *copy* an onboarded guest saw on a cold offline boot — an account-gated route now shows an honest "you're offline, progress is safe" sheet instead of "account needed." It does **not** restore a live guest identity offline: solo / practice play already runs offline (it is not auth-gated), but anything that genuinely needs a confirmed identity while offline still can't proceed until connectivity returns. Persisting and restoring the guest Supabase session locally would let those surfaces work fully offline.

**Sketch if revisited:** persist the guest session tokens locally and rehydrate the auth state on cold boot so an account-gated action has a usable identity without a network round-trip.

**Status:** Backlog. Only load-bearing if a future feature needs a confirmed identity offline; solo play already works offline today, so this is latent until then.

---

## Achievements for social/MP engagement + out-of-game actions

**Idea (owner feedback 2026-07-01, Sentry [CARDS-81](https://elijah-dangerfield.sentry.io/issues/CARDS-81), [CARDS-80](https://elijah-dangerfield.sentry.io/issues/CARDS-80)):** Add achievements that reward real-player multiplayer engagement — joining/creating MP rooms with other real people, winning against a full room, staying for a full cycle of players — to pull users toward the social side. Also add achievements for out-of-gameplay actions like sending feedback or reporting a bug, with their own Home-screen celebration since those happen outside a hand. Both are catalog/design asks that extend today's thin achievement set.

**Status:** Backlog. Folds into the "More achievements + early-stage pacing rebalance" pass ([CARDS-1A](https://elijah-dangerfield.sentry.io/issues/CARDS-1A)); pull when the achievement catalog gets a dedicated design pass.

---

## Achievement recap as a Home-screen notification

**Idea (owner feedback 2026-07-04, Sentry [CARDS-8K](https://elijah-dangerfield.sentry.io/issues/CARDS-8K)):** When a user returns to the Home screen after a session, show a recap of the achievements they earned as a Home-screen notification — explicitly contingent on first building out a sturdy Home-screen notification flow ("assuming we built out that sturdy Home Screen notification flow"). Pairs with the mid-game achievement pager (PROG-9) from the same report.

**Status:** Backlog. Depends on a robust Home-screen notification system that doesn't exist yet; pull alongside the achievement catalog/celebration pass (CARDS-1A cluster).

---

## Refuse client-asserted `iap.*` wallet-sync credits (ENG-9 follow-up)

**Idea (from ENG-9, 2026-07-04):** Wallet sync now refuses client-asserted `levelup.*` / `achievement.*` credits (`RefusedServerOwned`), but `iap.*` credits still ride wallet sync trusted verbatim. Real purchases already flow through the server redeem path (`/v1/me/billing/redeem`, receipt-validated, BILL-5) — the only remaining writer of client-asserted `iap.*` events is the debug fake-billing path in `DefaultPurchaseChipPackUseCase.creditChipsLocally` (used when `billing.realPurchasesEnabled` is off). Extending the refusal to `iap.*` closes an unbounded mint vector (a modified client can post any delta under an `iap.*` reason), but needs a decision about what the fake-billing dev path does instead — likely: point it at the redeem endpoint's fake-receipt branch or accept the local-only credit being clawed back at sync in debug.

**Sketch if revisited:** add `"iap."` to `RewardChips.SERVER_OWNED_CREDIT_REASON_PREFIXES` (or a sibling list keyed to the redeem path), retire `creditChipsLocally`, and route debug purchases through redeem with a fake receipt the validator accepts in dev. Note the broader hole stays until Phase 4.2 server-side hand resolution: wallet sync still trusts arbitrary positive deltas under *unreserved* reasons (solo-play results are legitimately client-asserted today), so reason-prefix refusal is per-category hardening, not full trust.

**Status:** Backlog. BILL-1/2 territory; pull when the billing go-live pass happens.

---

## Delete the dead AudioRecorder + microphone-permission surface (ENG-14 follow-up)

**Idea (from ENG-14 review, 2026-07-04):** The camera capture surface is gone, but its sibling recording stack is equally call-site-free: `AudioRecorder` (common interface) + `AndroidAudioRecorder` / `IosAudioRecorder` impls and `rememberMicrophonePermissionLauncher` in `:libraries:ui` have zero callers in features or apps — the impls only appear in the DI graph via their own `@ContributesBinding`s. The planned audio work in this file ("Audio infrastructure") is playback-only (`AudioPlayer`), not recording, so nothing on the roadmap claims this code.

**Sketch:** delete `AudioRecorder.kt` (common/android/ios), `PermissionLauncher.kt` expect/actuals, and any `RECORD_AUDIO`/mic usage-description leftovers; verify Android + iOS builds. If voice notes on feedback reports ever become a thing, rebuild against that design rather than resurrecting this.

**Status:** Backlog. Deferred from the 2026-07-04 nightly rather than deleted in-cycle because it touches the iOS DI graph and the cycle's build validation didn't cover a full Xcode pass.

---

## Platform game services — Game Center + Google Play Games achievement/leaderboard mirroring

**Idea (owner directive 2026-07-07):** Mirror in-app achievements and progression to the native platform services — Apple Game Center on iOS, Google Play Games Services v2 on Android — behind one common `GameServicesCoordinator` interface, cloning the billing coordinator pattern (Swift class conforms via SKIE; real Android impl via anvil). One-way, fire-and-forget, idempotent mirroring driven purely by observing `AchievementRepository.observeProgress()` and `ProgressionRepository.observeProgression()` — no ViewModel or game-logic changes. Two leaderboards (lifetime Total XP, lifetime Hands Won — deliberately not chips; freemium economy means chip boards would be pay-to-rank). All gated behind a `gameServices.enabled` flag defaulting off until the store-side config is live.

**Full plan:** [docs/plans/platform-game-services.md](./plans/platform-game-services.md) — includes module layout, interface signatures, 6 commit-sized phases, testing strategy, and step-by-step App Store Connect + Play Console runbooks (achievement points budget math, games-ids.xml handling, signing-key credentials, the separate PGS publish step).

**Status:** Backlog. Approved plan, not yet started. Note the store runbooks involve real admin work (≈53 achievements × 512px artwork in both consoles) that can't be automated; code phases all ship dark so they can land anytime.

---

## Real-multiplayer bust — show the losing showdown before the bust dialog (GAME-18 follow-up)

**Idea (deferred from GAME-18, 2026-07-08):** Solo play now sequences the showdown reveal ahead of the bust dialog so a busted player sees the hand that took their stack. The real-chip multiplayer path still pops `MultiplayerBustDialog` straight over the table on a showdown bust — same "never saw the hand I lost to" gap in principle. It is not a copy-paste of the solo fix: the MP bust dialog carries the heads-up rebuy-grace countdown (MP-14), which the server starts the moment the hand settles, so gating it behind a reveal + "Continue" tap would eat seconds of a live rebuy window. The reveal likely needs to render *alongside* the countdown (compact result strip inside the bust dialog, or a reveal with the countdown/Rebuy CTA visible throughout) rather than in front of it. Needs a UX call before engineering.

**Sketch:** see `PlayPokerScreen.kt` (`humanBust && state.isRealMultiplayer` branch) and `MultiplayerBustDialog` in `HandResultDialogs.kt`; the solo sequencing in the sibling branch is the reference for what the reveal must convey.

**Status:** Backlog. Pull with the next pass on the multiplayer bust/rebuy flow.

---

## Prefilled-code join failures (full / network / over-balance) strand the user on the lobby spinner

**Idea (raised 2026-07-08, janitor pass):** CARDS-28 fixed the NotFound case — a bad prefilled code bounces back to the code-entry screen. But every *other* join failure on the PrivateJoin → Lobby funnel (room full, over balance, network error, unknown) still lands as an inline error under the "Setting up your table…" spinner with no retry affordance — the room never arrives, so the user sits on a dead lobby with only the back button. The create side already has the full-screen error + Retry treatment (`LobbyState.createError`, CARDS-2E); join failures deserve an equivalent: either extend the full-screen treatment with a join-flavored retry, or route all join failures back to `PrivateJoinRoute` the way NotFound does (would need the join screen to render errors beyond "room not found"). Needs a small UX call on which shape wins.

**Sketch:** `LobbyViewModel.handleAction` SubmitJoin branch + `LobbyScreen`'s `createError` full-screen path; `LobbyScreenPreview_JoinFailed` pins the current stranded state.

**Status:** Backlog.

---

## First-to-all-achievements reward automation

**Idea (owner, 2026-07-10):** When the first player earns every achievement, send them a manual reward. Once ECON-2's admin grant endpoint exists this is a thin GH action: query achievements-per-user (server Postgres), detect completion, fire an `admin_adjustment` grant with a celebratory note. Blocked on ECON-2; also pairs with the achievements panel idea in ENG-19.

**Status:** Backlog.

---

## ENG-20 phase 3: retire the replayed-edge machinery entirely

**Idea (filed with ENG-20, 2026-07-10):** With sync triggers on levels (`runWhen` + `SyncTriggers`), the remaining edge plumbing can shrink: drop the app-event bus replay 1→0 once `OfflineFirstAppConfigRepository` (the last replay-dependent consumer) is checked/migrated, migrate identity's condition-shaped listeners (`GuestSessionHealer` etc.) onto `runWhen`, then delete `ConnectivityEdgeDispatcher` + `AppEvent.ConnectivityRegained` (SyncTriggers already derives `cameOnline` from the `isOffline` level directly).

**Status:** Backlog.

---

## Matchmaking funnel: attribute invite-link joins (`entry=deep_link`)

**Idea (filed with the ENG-18 taxonomy sweep, 2026-07-11):** `matchmaking.search_started` fires with `entry` public (Find-a-table) or private_code (join-by-code submit), but a join via a shared invite link doesn't route through either instrumented VM, so deep-link joins are invisible in the funnel. When the invite/deep-link funnel matters, add the emit at whatever entry the link-join routes through and register `entry=deep_link` in `docs/wiki/app-events.md`.

**Status:** Backlog. Small; funnel currently only distinguishes public vs private_code.

---

## Versioned wallet snapshot: `revision` column + monotonic `setBalance`

**Idea (filed with PROG-12, 2026-07-11):** The mint→re-pull contract fixes the reward-staleness race by ordering (a pull issued after the mint reads post-mint state), but the fully general fix for any two wallet writers racing is a server-side `revision` on the wallet row, returned by every read/mutation, with the client's `setBalance` refusing to apply a snapshot older than the one it holds. Design and rejected-for-now reasoning are in `docs/decisions.md` (2026-07-11, PROG-12 entry). Pull if another balance-staleness report arrives with a different shape than the mint edge.

**Status:** Backlog.

---

## Account-linking nudge for beta testers on anonymous accounts

**Idea (filed with AUTH-19, 2026-07-11):** The owner's stranded-account incident happened because a guest account with real progression has no credential to recover with; the session mirror now protects against storage loss, but a claimed account is the only durable recovery path. Once progression passes some threshold (level, chip balance, or days played), surface a gentle one-time "protect your progress" nudge toward claiming. Pairs with the existing claim-account flow; needs a trigger heuristic and copy.

**Status:** Backlog.

---

## In-game quick-buy sheet: purchase-failure dialog parity with the shop

**Idea (filed with BILL-7, 2026-07-11):** BILL-7 gave the shop a blocking "Finishing your purchase" overlay and a full failure dialog distinguishing paid-but-pending / refused / not-charged. The in-game quick-buy chip sheet still surfaces failures as toasts. Apply the same treatment there (the same `PurchaseError` classification already exists in `:features:shop:impl` — lift it somewhere shared) so a paid-but-uncredited purchase mid-game gets the same honest explanation.

**Status:** Backlog.

---

## Pre-action family: "Call [amount]" / "Call any" + standalone Fold

**Idea (filed with GAME-30, 2026-07-12):** GAME-30 shipped the two no-money-committed pre-action toggles ("Check/Fold", "Check"). The natural next slice is a call-committing family — "Call [amount]" and "Call any" — plus a standalone "Fold". Deferred because a call puts chips in without a fresh confirmation, so it needs its own instant-vs-confirm decision distinct from the check toggles. The resolve/arm/disarm machinery (`PreAction.resolve`, `evaluatePreAction`, the disarm-on-bet projection) is already in place to extend.

**Status:** Backlog. Needs a product call on confirm-vs-instant for chip-committing pre-actions.

---

## Responsible-play link on the in-game quick-buy confirm

**Idea (filed with BILL-10, 2026-07-12):** BILL-10 added a confirm step to the post-bust quick-buy so a real-money chip pack isn't one tap from a charge. The storefront's real-money confirm also carries a "Play responsibly" (NCPG) link; the quick-buy confirm does not. For compliance parity, thread an `onOpenResponsiblePlay` callback from the room VM/router into `QuickBuyChipsSheet` and add the same line. Left out of the confirm-gate itself as a separate compliance decision.

**Status:** Backlog. Compliance parity; needs a call on whether the in-game path requires the same line.

---

## Lift `platformStoreName()` to a shared home

**Idea (filed by reviewer with BILL-10, 2026-07-12):** The `@Composable platformStoreName()` helper (branches `App Store` / `Google Play` off `BuildInfo.platform`) is now duplicated verbatim in `:features:shop:impl` (`PurchaseConfirmSheet.kt`) and `:features:room:impl` (`QuickBuyChipsSheet.kt`), both reading the same `shop_purchase_store_*` strings. Lift it into a shared home both can reach (a small billing-UI helper) so the two confirms can't drift. Minor; two 5-line copies today.

**Status:** Backlog.

---

## Audit the linked iOS klib graph for a transitive ktor-client-cio

**Idea (filed with ENG-28, 2026-07-12):** ENG-28 fixed the iOS TLS crash by binding Darwin explicitly on every first-party HTTP/WS client, so engine-less auto-resolution can no longer pick a TLS-incapable native engine. A static source grep finds CIO only in `:apps:server` (JVM). The remaining gap is a transitive dependency dragging `ktor-client-cio` onto the iOS binary — invisible to a source grep because it needs the resolved klib graph of the linked iOS framework, not the Gradle files. If an iOS TLS/engine crash ever recurs, resolve the iOS binary's klib graph and confirm no CIO is linked.

**Status:** Backlog. Needs the linked iOS binary's resolved graph; not doable from source.

---

## Swipe-up-to-fold + minimal undoable fold indicator (drop the fold button)

**Idea (owner feedback 2026-07-13):** The dedicated "fold when it's my turn" button isn't needed. Fold should just be the **swipe-up gesture** on your cards. Pair it with a **very minimal fold UX/UI indicator** and an **undo** affordance right next to it: when you throw your cards away to fold, an undo button appears **in the spot where you folded**, available as long as the action **hasn't been submitted** to the server yet. Removes a button from the action bar and makes fold feel physical (toss) while staying reversible against misfires. Exact indicator/animation and the "not yet submitted" reversal window are a directional call for whoever picks it up.

**Status:** Backlog. Gameplay-UX polish, post-launch; entry point is the play-screen action controls + card gesture handling in `:features:room`.

---

## Multiple servers + zero-loss games across reboots/deploys

**Idea (owner feedback 2026-07-13):** Two linked pieces of server scaling/resilience work, both blocked today by the single-writer design (live room/hand state is in one process's RAM, gated by the `SingleWriterGuard` Postgres advisory lock in `Application.kt`; `fly.prod.toml` runs one instance, rolling stop-old-before-new). Consequences we accept for now: only one server can run, and a deploy/reboot tears down live tables — the boot-recovery sweep cashes escrowed chips back to wallets (money is safe) but the in-progress hand ends and players get a disconnect blip.

1. **Zero-loss deploys/reboots (do first, smaller).** Survive a restart without ending live games. Options: **graceful drain** (stop taking new hands, let in-flight hands finish or hand off, then swap) and/or fuller **persist + rehydrate** of live session state so the new instance resumes seats/stacks/betting and clients reconnect into the same hand. Partial machinery already exists — `SessionSnapshotStore` (durable snapshot, hydrate-on-lookup) + the WebSocket reconnect grace period — so the gap is finishing the handoff so restart resumes rather than cashes out.
2. **Multiple servers / horizontal scale (bigger).** Replace the one-instance advisory lock with **per-shard ownership** (shard-by-room-code), a matchmaking/routing layer that sends a player to the instance owning their room, and a Postgres matchmaking query — the deferred "shard-by-code + PG matchmaking" design. Removes the single-instance ceiling for concurrency.

Sequence: (1) makes deploys painless at current scale; (2) is the real scale-out and subsumes much of (1)'s handoff plumbing.

**Status:** Backlog. Infra investment; pull when either deploy disruption at peak or a single-instance concurrency ceiling becomes a real constraint. See the "Server sharding" and server-deploy notes.

---

## Player report: reason picker + confirmation step (MOD-1 follow-on)

**Idea (filed with MOD-1, 2026-07-13):** MOD-1 shipped a single-tap "Report" on the player card that files an append-only report with no reason (the `player_reports.reason` column + the client `reportPlayer(reason=...)` param are already wire-ready, sent null in V1). Two enhancements when moderation matures: (1) a lightweight **reason picker** (offensive name/emotes, cheating, harassment, other) so a human moderator gets signal beyond "user X reported in room Y"; (2) an optional **confirmation step** before filing, if the single-tap-plus-disabled affordance proves too easy to misfire in practice. Both are client-only — the server route already accepts an optional reason and is idempotent-friendly.

**Status:** Backlog. Pair with the deferred moderation-review UI + auto-ban rules (see `post-launch.md`); until a human reads reports, the reason adds little.

## HowToPlaySheet: adopt BottomSheet scrollableContent

**Idea (GAME-32 follow-on, 2026-07-13):** GAME-32 gave the opinionated `BottomSheet` an opt-in `scrollableContent` flag so sheets stop hand-rolling `Column(verticalScroll)`, and migrated `HandRankingsCheatSheet` onto it. The sibling `HowToPlaySheet` (`:features:room:impl`) still hand-rolls its own scroll because it uses the `title`-overload of `BottomSheet`, which doesn't thread `scrollableContent` down to the base. Threading the flag through the two title overloads finishes the story. Not mechanical: the title overload wraps `title + body` together, so the scroll must wrap only `body()` to keep the title pinned — decide the scroll scope deliberately rather than wrapping the whole slot.

**Status:** Backlog. DS plumbing polish; pull when the next sheet wants a scrollable body under a title.

## RoomSeat: lift the seat-tile labels to string resources

**Idea (ROOM-18 follow-on, 2026-07-15):** `RoomSeat.kt` in `:libraries:ui` renders several user-facing labels as inline literals ("Add a bot", "Adding…", "Open", "joining…", "up next") while only the "You" pill routes through `:libraries:resources`. ROOM-18 added the "Adding…" literal to match the file's existing convention rather than lift one label in isolation. Do the whole component in one deliberate pass: add `room_seat_*` entries and read them via `stringResource`, so the copy is translatable and word-checkable in one place. Watch the ellipsis characters (use a plain "..." per the strings.xml rules, no backslash escapes / em dashes).

**Status:** Backlog. Small DS hygiene pass; pull when touching RoomSeat again or when localization work starts.

## Guest email-link "account saved" dialog on cold launch (AUTH-24 follow-on)

**Idea (AUTH-24 follow-on, 2026-07-17):** AUTH-24 shows the "account saved" dialog when an anonymous guest confirms an email link, by threading a `guestLink` flag through `VerifyEmailRoute`. The cold-launch deep link `cards://auth/confirmed` can't carry that flag, so a guest who kills the app between requesting and tapping the link lands on Home with no dialog (the warm `AppResumed` path on the live screen is covered). Close the gap by persisting a "guest email link pending confirmation" marker in `AppData` when the claim flow kicks off `linkEmailIdentity`, then reading + clearing it in `VerifyEmailViewModel.routeAfterConfirmation` so the cold-launch confirm also shows the dialog.

**Status:** Backlog. The flag defaulting false only ever omits the dialog, never shows the wrong one, so this is a completeness polish, not a correctness bug. Pull when touching the verify-email flow or AppData onboarding flags.

## Cold-launch email confirmation: same-frame routing into onboarding (AUTH-26 residual)

**Idea (worker note 2026-07-18):** AUTH-26 makes a `cards://login-callback` confirmation link establish + persist a session even when the app was killed mid-signup (`SupabaseAuthRepositoryImpl.completeRedirectWithoutPendingLocked`). But on that killed-then-relaunch path the app boots session-less and `OnboardingViewModel.ResolveEntry` can run before the async import lands, so the user may sit on the Welcome/landing step (now silently authenticated) until the next auth resolve routes them into the identity step. Close the loop so the freshly-imported cold-launch account routes into onboarding on the same launch — e.g. an app-level reaction to the auth transition, or re-resolving onboarding entry when auth flips to authenticated-not-onboarded. Needs device verification (deep-link + process-kill timing).

**Status:** Backlog. Low-severity edge (the app-backgrounded-not-killed path already routes immediately); pull when the auth deep-link paths get device QA.

## Animated screen-entrance transitions (staggered content reveal)

**Idea (owner feedback 2026-07-18):** When a screen finishes loading, its content currently pops in all at once — e.g. the shop grid snaps onto the screen once the catalog resolves. Add a deliberate entrance animation so loaded content reveals with a short, subtle staggered fade/slide instead of jumping. Most visible on the list/grid screens (shop, My Items, achievements). Build it as a reusable enter-transition primitive in `:libraries:ui` (`AnimatedVisibility` + a staggered fade+slide, or lazy-list item enter / `animateItemPlacement`) rather than per-screen one-offs, so the motion reads as one system and stays DS-owned. Keep it fast and understated (the goal is polish, not a splashy reveal); honor reduced-motion if/when that setting is surfaced.

**Status:** Backlog. Pure UX polish; pull during a motion pass or when the shop/list screens get design attention.
