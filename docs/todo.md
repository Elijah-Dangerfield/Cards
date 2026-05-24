# TODO

**Last reviewed:** 2026-05-21 · **Companion to:** [product/v1-mvp.md](./product/v1-mvp.md), [backlog.md](./backlog.md)

The live punch list of actionable engineering work. Append, check off, and **delete** done items — they don't need to live here as history. Add a [decisions.md](./decisions.md) entry **only** when an item resolved a non-trivial architectural call worth not re-litigating (see decisions.md header for what qualifies). Most items just get crossed off and removed.

When an item points at a file path or system, the assumption is that path/system already exists — the work is the gap, not a greenfield build.

**Loose item template** (freeform — no enforcement, but the more of these you fill in, the safer it is for an automated worker to pick up):

> **Problem:** what's wrong / missing.
> **Acceptance:** how we know it's done.
> **Files / hints:** where to start looking.
> **Out of scope:** what NOT to drag in.

Anything in §A is **off-limits to automated workers** — those items need a human call first. Everything below §A is fair game.

---

## A. 🚫 Blocked — needs human decision

**Do not pick these up in any automated run.** Contradicts the locked spec or requires an executive call before engineering can start.

*(No open items at this time.)*

---

## B. UX gaps observed in the build

These are bugs / polish items found playing the app or scanning the code. Cheap individually; collectively the V1 quality bar.

### Design system — dialog & sheet primitives
- **Top-accessory generalization for dialogs + bottom sheets.** Today both `Dialog(emoji = DialogEmoji)` and `BottomSheet`'s emoji drag-handle bake in an emoji-shaped affordance: the only "what sits on the lip of the surface" affordance is `EmojiBubble`. That over-commits the DS — a future surface might want a `CircleIcon` (vector / chip-coin / avatar tile), a stacked-coin glyph, a logo mark, etc. Refactor toward a `topAccessory: TopAccessory?` parameter on both primitives, where `TopAccessory` is a sealed type that can be `Emoji(...)`, `Icon(...)`, `Image(...)`, or `Custom(@Composable () -> Unit)`. The shape carve-out (notch geometry) is the shared bit — anything that fits within a circle / squircle slot of the configured size should be a valid accessory. **Files / hints:** [EmojiBubble.kt](../libraries/ui/src/commonMain/kotlin/com/cards/libraries/ui/components/dialog/EmojiBubble.kt), [Dialog.kt](../libraries/ui/src/commonMain/kotlin/com/cards/libraries/ui/components/dialog/Dialog.kt) (the `DialogEmoji` data class + `dialogEmoji(...)` / `dialogChipBubble()` factories), [BottomSheet.kt](../libraries/ui/src/commonMain/kotlin/com/cards/libraries/ui/components/dialog/bottomsheet/BottomSheet.kt) (drag handle variants). Migrate the existing two factories (`dialogEmoji`, `dialogChipBubble`) to be `topAccessory` constructors over the new sealed type. **Out of scope:** redesigning the notch shape — the `NotchedSheetShape` half-circle / rounded-rect carve stays; only what fills it changes. When variable bubble sizes actually land, revisit whether `EmojiBubbleDefaults.BodyGap` should become a function of size — today's constant is correct for the single 100dp size we ship.
- **Beef up `BottomSheet` so the existing `BaseBottomSheet` callsites can migrate.** Today `HandRankingsCheatSheet` opts into `@LowLevelDSComponent` and uses `BaseBottomSheet` because it owns its own padding / scrolling — the opinionated `BottomSheet` doesn't expose enough hooks to host that content without re-doing the chrome. The DS goal is the opposite: every real sheet uses the opinionated wrapper and `BaseBottomSheet` is reserved for genuine one-offs. Audit current `@LowLevelDSComponent` usages (start with `HandRankingsCheatSheet`), figure out what they need that `BottomSheet` doesn't give them, and **extend `BottomSheet`** — extra content slots, an override for the gutter / top padding, maybe a `text:` overload vs a `content:` overload that defaults the typography + color via `LocalContentColor` / `LocalTextStyle` so sheets read consistently without each caller spelling it out. Then migrate the callsites and re-tighten the `@LowLevelDSComponent` blast radius. **Out of scope:** removing `BaseBottomSheet` — the escape hatch stays; this is about making it genuinely rare.

### Economy — chip flow promises not yet built
Surfaced 2026-05-20 by a spec-vs-build audit. All three are spec promises in [product-spec.md §4.1](./product/product-spec.md#41-chips--the-only-currency) with zero implementation. None are in catastrophic territory on their own, but together they're load-bearing for the "chips feel sacred / we're generous, not punitive" economy narrative.

- **Soft bust protection — client surface for the welcome dialog.** Server side landed 2026-05-21: both `GET /v1/me/wallet` and `POST /v1/me/wallet/sync` call `maybeApplyBustProtection`; the first time the user's balance hits zero they get `Wallet.BUST_PROTECTION_GRANT` (1,000) chips with a `bust_protection_v1` ledger event AND a Dialog `UserMessage` with copy "Welcome back to the table." Idempotency on the ledger means it's lifetime-once. Remaining work is purely client-side: the dialog gets picked up by the existing UserMessage polling, so it should "just work" — but verify on a real device that the dialog renders correctly with the chip-bubble emoji + body, and that the wallet observer in `ChipsRepository` sees the +1000 delta after the grant lands. If the auto-pop dialog placement is wrong (e.g. fires in the middle of a hand), gate it on session-start instead.
- **Tip the dealer** — 50–500 chip post-hand action as flavor sink. Lives on the showdown / hand-end dialog. **V1-polish.** Pure flavor; not load-bearing. Worth doing for the chip-sink narrative but skippable if scope tightens.

### Catalog gating — unlock-only vs purchasable
- **`unlock_only` flag — wire the earned-grant path + render Earned in My Items.** [product-spec.md §4.2](./product/product-spec.md#42-the-unlock-only-catalog) is structurally load-bearing: legendary achievement cosmetics, league-tier cosmetics, RFT cosmetics, achievement-chain cosmetics are **never in the shop, ever.** Shop catalog and unlock catalog must be disjoint. **Foundations are in place — there is no separate Trophy Case surface; earned items land in My Items.** Remaining work:
   - **Server:** the achievement-reward / league-finish hooks (already wired for chip rewards on achievements) need to also write the unlock-only product id into `inventory` for the user via `InventoryRepository.recordEarnedGrant(...)` (entry point exposed, unwired). Use `ProductCatalogSource.readById(id, context)` for validation — it bypasses the `unlock_only = false` filter so server can resolve any id regardless of flag.
   - **Client UI:** `MyItemsScreen` "Earned" badge landed 2026-05-24: equippable earned rows get an inline `EarnedTag` chip beside the subtitle; non-equippable earned rows (avatar / emote packs) flip the trailing `OwnershipBadge` from "Unlocked" → "Earned" in `accentSecondary`. Still open: the **earn-source attribution** ("from Comeback Kid achievement", "from Bronze league") — `InventoryItem` doesn't carry the source id today, would need a new wire field (`earnedFromKind`/`earnedFromId`) populated on the server's grant path and a client-side resolver from id → display string. Also still open: a **celebratory unlock dialog at the moment of earning** — prestige lives at earn-time, not on the shelf. Shop search rendering as "Owned" already works through the existing inventory → shop-render pipeline since earned items land in `inventory`.
   - **Design lever — "unlock and buy":** a family can have both an earnable variant (achieve X to unlock) and purchasable variants. Earning the first is a taste-test that pre-qualifies the user as a buyer for the rest.
   - **V1-blocker** for any prestige cosmetic. Acceptable to ship V1 with the unlock-only catalog *empty* and defer the My Items earned-rendering — but that's a content decision. The shop is unaffected either way.

   **Already in place (don't redo):** `unlock_only BOOLEAN` column on `products` (V10) with the `WHERE unlock_only = FALSE` filter in `PostgresProductCatalogSource.read`; `acquisition_source TEXT` on `inventory` (V13, `'purchased'|'earned'`); `OwnedItem.acquisitionSource: AcquisitionSource` on server; `OwnedItemDto.acquisitionSource: String` on the wire; `InventoryEntity.acquisitionSource` in Room (AppDatabase v15); `InventoryItem.acquisitionSource` in the client domain.

### Screen / chrome consistency
- **Previews on every user-facing composable.** Rough rule: every public/internal screen-level composable should have at least one `@Preview`. Private helpers don't need their own preview unless the parent doesn't already exercise the visual. First sweep landed previews on the obvious gaps — `OnboardingScreen`, `SignInScreen`, `SignUpScreen`, `VerifyEmailScreen`, `BotTableSetupDialog`, `WinOddsBadge`, `CountdownBadge`, `ProductIcon` / `BadgePill` / `OverhangBadge` (shop helpers). Future contributions should add a preview alongside any new screen-level composable; CI doesn't enforce yet (no static-analysis lint plugged in), so this is a convention.

### Privacy policy / terms of service
- **Write the actual content.** The profile screen already deep-links to a web page; the page itself is empty/placeholder. Probably one of the last items before TestFlight. Hosting can stay on the existing web link target.

### Typography & DS consistency
- **Audit text sizes across the app — RankDetailSheet aligned 2026-05-22; Shop + ProfileScreen ClaimAccountCard aligned 2026-05-22 (second pass); first room slice landed 2026-05-22; home + the rest of room still open.** RankDetailSheet's body copy (RankHero subtitle, HowRankWorks trailer, ClaimAccountCard paragraph, Bullet text) bumped from `Body.B400` → `Body.B500` to match StatsScreen's `Body.B500` body convention. Second pass aligned the four obvious body-shaped subtitles in Shop (`ShopHeader` "Spend chips. Stock up. Flex.", `SectionHeader` subtitle slot, `EmptyShop` body, `StatusPrompt` body on `PurchaseConfirmSheet`) and the profile `ClaimAccountCard` description paragraph — all bumped `Body.B400 → Body.B500`. Third pass (room explainers): `BlindRolesExplainer.RoleRow` per-role description, `HandRankingsCheatSheet.ActionRow` action description, and `HandRankingsCheatSheet.RankingCard` per-rank tagline all bumped `Body.B400 → Body.B500` — they're parallel title-and-paragraph rows where the paragraph was reading visibly smaller than the title. Per-product tile subtitles, "Charged via your <store>" fine-print, `BalanceRow` labels, `ErrorBanner` message, and shop-snackbar inline text remain `Body.B400` — those are genuine *captions* on a card or in a banner, not body. The room B400s I explicitly left alone after inspection: `StackExplainer`'s "practice chips" footer (fine print), `LastActionExplainer` + `HandLabelExplainer` "Tap the ? icon" footers (small CTAs), `HandResultDialogs` row sublines + "Board" / "earned" / "Achievement unlocked" labels (single-word inline captions), `PlayerArea` displayName + title (small label locked to the 142dp tile height — bumping would clip), `RaiseSheet` "max {N}" + bet-preset sublabel (small numeric captions), `HandRankingsCheatSheet`'s "Strongest on top." subtitle (single-line subtitle). StatsScreen continues to use `Body.B400` only for genuine *captions* (XP-to-next-level subline under the hero, StatTile labels, EventRow sublines) — that's the intended pattern: B500 is body, B400 is captions. Remaining sweep: home, plus any room callsite a future eye disagrees with. Pairs with the next bullet — if 90% of body callsites have to spell out `typography = Body.B500`, the default (`Body.B600`) is the lever, not per-callsite overrides.
- **DS-first text component default — repo-wide audit landed 2026-05-22; flipping deferred.** Counted across `features/` + `libraries/` (using `grep -rn "typography = AppTheme.typography.<X>"` then de-duped against `typography = AppTheme.typography.Body` totals): of 198 explicit `Body.*` overrides on `Text(...)`, 103 (52%) are `B500`, 23 are `B600` (today's default), 68 are `B400`, 4 are `B700`. Total explicit typography overrides (any family) across 1,458 Compose `Text(...)` callsites is 343 — meaning ~1,115 callsites accept the default (`Body.B600`) silently. The "90% of body callsites pass B500" assumption baked into the bullet doesn't hold up: explicit B500 *dominates* the explicit overrides (52%) but the *true majority* of all Text uses is "no override at all" — and those have lived happily on B600 for the whole of V1 development. Flipping `DefaultTypography.Default` from `Body.B600 → Body.B500` would silently shrink ~1,115 callsites and require explicit B600 overrides on 23. Net change is ~80 fewer explicit overrides at the cost of every unmarked Text getting smaller everywhere — not a clear win on the data we have. **Recommendation:** don't flip the default. Instead, treat the per-screen typography audit (other open bullet) as the way forward — fix outliers in place. Re-open this question only if a future audit shows a clear screen-level pattern (e.g. >80% of body callsites *within a single feature module* pass B500 explicitly).

### Strings — loose-leaf literals everywhere
- **Sweep inline string literals into a centralized strings layer.** Most UI copy is currently hardcoded at the callsite ("OWNED", "Claim your account", "Long-press to copy", "Stats", section titles, error snackbars, etc.). That's load-bearing-by-accident: it blocks localization, makes voice-and-copy edits a repo-wide find-and-replace, and lets two screens drift on the same idea. Pick the KMP-friendly approach (compose-multiplatform-resources `Res.string.*`, or a typed `Strings` object generated from a single source-of-truth file) and migrate. Start with the surfaces that share copy (shop snackbars, owned state, claim CTAs) so the consolidation surfaces drift immediately. **Out of scope:** translating anything. This is about giving strings *a home*, not a second language. **V1-polish** rather than blocker — but a much cheaper sweep now than after another quarter of features land.

### App guard chrome
- **Blocking states intentionally overlay (not in topBar).** `UpgradeRequired` / `MaintenanceBlocking` use a full-screen `Box` overlay inside `AppGuardLayer` so they cover the entire app surface — including any topBar / bottomBar. That's the right model for "stop everything" states; don't refactor to a Column.

### Animations / table polish
- **XP / coin earned distribution animation.** Today the showdown dialog overlays the XP/coin badges, so the user never sees the odometer count up. Idea: defer the XP/coin badge animation until *after* the showdown/bust dialog dismisses, then play it as a small "zip" — XP particle flying up to the XP badge, coin particle flying down to the chip badge, each landing into an odometer count-up. Open to pushback: the alternative is to render the earned values inside the dialog and skip the badge animation entirely.

### Table-side social
- **Emoji sending in games.** [product-spec.md §5.5](./product/product-spec.md#55-table-side-social) commits to emoji blasts (~12 base emojis, 8s cooldown, mute-this-player) as a V1 feature. Not built yet. Bottom-tray surface, full-screen 1.5s animation per emit.
- **Drag-to-fold — gesture redesigned 2026-05-23; device QA still pending.** `PlayerArea` listens for a vertical drag on the hole-cards subtree via `detectVerticalDragGestures`. Gate: only when `table.isHumanTurn && humanLegalActions != null && participation != Folded`. Two semantic shifts vs the earlier flat-threshold prototype: (1) cards physically follow the finger — an `Animatable<Float>` drives the row's `graphicsLayer.translationY`, with proportional tilt (`rotationZ = -6 * progress`) and fade (`alpha = 1 - 0.25 * progress`). Release below threshold springs the cards back. Past threshold continues the upward motion off-screen (~400dp) as the fold animation rather than cutting. (2) Commit is now release-only with two qualifying paths to make accidental folds less likely: drag past ~100dp (~70% of card height) *or* release with upward velocity >1200 dp/s after at least 30dp of travel (flick shortcut). `VelocityTracker` accumulates positions during the drag and `calculateVelocity()` resolves the release velocity in `onDragEnd`. First-trigger UX is the `SwipeFoldConfirmDialog` — teaches the gesture with a "Don't show this again" checkbox; confirm writes through `PlayPokerAction.AcknowledgeSwipeFoldGesture` → `AppData.swipeFoldGestureAck = true` and dispatches the Fold intent. From then on the gesture folds silently. Remaining: device QA — confirm the new thresholds feel right at thumb-friendly distance and no accidental folds slip through.
### App-store review prompts
- **App-store review prompts — V1 path wired end-to-end (verify on device before TestFlight).** All three triggers land as of 2026-05-21: `:libraries:review` exposes `ReviewPromptCoordinator` + `ReviewLauncher`; `RealReviewPromptCoordinator` runs the eligibility gate (install age ≥3d, prompt cooldown ≥30d) against `AppCache`. `AdaptedReviewLauncher` delegates `ReviewLauncher.requestReview()` to the long-standing `ReviewPrompter` binding (Android Play Core via `AndroidReviewPrompter`, iOS `SKStoreReviewController` via Swift). `PlayPokerViewModel` fires `AchievementUnlocked` on rare/epic/legendary unlocks, `LevelUp` on hand-driven level changes, and `SessionEnd` via `PlayPokerAction.LeaveTable` (dispatched by the screen's back-handler / top-bar back / confirmed-leave dialog) — gated on `sessionFactory.xpMode == XpMode.BOTS` so MP-disconnects don't masquerade as positive moments. Remaining work is verification only:
  - Smoke-test on Android release flavor: meet the install-age floor + prompt cooldown, then leave a bots table — Play Core decides whether to surface the dialog. Either outcome is correct per the spec.
  - Smoke-test on iOS: same eligibility floor; `SKStoreReviewController` is even stricter about throttling.
  - **Important:** never write a self-built rating dialog as a fallback. If the OS declines to show the prompt, that's the system working as designed — see [spec §2.6](./product/product-spec.md#26-app-store-review-prompts).

### Notifications — Phase 6, not started
- **§8 opt-in event-driven push notifications.** League placement, friend activity, battle-pass tier, Rare/Legendary achievement unlock. Never time-of-day modeled, never "your chips are lonely," never "come back" pings — see [product-spec.md §8](./product/product-spec.md#8-notifications) for the bright lines. Includes opt-in granularity (per-category toggle, not just global on/off). **Not a hidden gap** — explicitly Phase 6 on the roadmap — but worth listing so it doesn't slip after Phase 4.2.

### Email & deep linking
- **Friend-game link previews.** [product-spec.md §5.2](./product/product-spec.md#52-friend-games) promises iMessage/WhatsApp previews showing a Cards-branded card with stakes + seat count. Needs (a) iOS Universal Links + Android App Links configured for the friend-code URL, (b) a small web endpoint serving Open Graph meta (`og:title`, `og:image`, `og:description`) keyed by the code, (c) image rendering for the preview card (can be static-with-placeholders for V1 — full dynamic rendering is overkill). **V1-polish** — friend games work today via copy-code; the rich preview is a social-virality nicety, not a blocker.

- **Email confirmation link points to `localhost`.** Supabase email template is on the default. Set the project's site URL + redirect URLs in the Supabase dashboard (dev *and* prod). While there, swap the default Supabase template for a Cards-branded one (copy in [voice-and-copy.md §5.x](./product/voice-and-copy.md)).

### Claim Account screen
- **Email-claim semantics — `linkEmailIdentity` landed 2026-05-22; confirmation-style flow still open.** `IdentityRepository.linkEmailIdentity(email, password)` is now wired through `SupabaseIdentityRepository.updateUser { email = ...; password = ... }` — preserves chips/XP/history on the same userId, returns `LinkEmailIdentityOutcome.VerificationRequired(email)` on success so the existing verify-email screen picks it up unchanged. `SignUpViewModel.Submit` routes anonymous sessions through `linkEmailIdentity` and falls back to `signUpWithEmail` for non-anon callers (or if the link path returns `NotAnonymous` / `NotSignedIn` defensively). Repo-level guard returns `LinkEmailIdentityOutcome.NotAnonymous` on a non-anon call so we don't silently mutate the email of a real account. Coverage: three new VM tests pin the anon → link, non-anon → sign-up, and link-NotAnonymous → sign-up-fallback contracts (`submit_whenAnonymous_routesToLinkEmailIdentity_preservingGuestProgress` et al). Repo-level tests still don't exist for `SupabaseIdentityRepository` (separate gap in §D — no commonTest sources, would need fakes for `SupabaseClient`). **Still open from this bullet:** the "confirmation-style claim flow" half — a "this will turn your guest account into a real account, your chips/XP/avatars come with you" dialog before submit, mirroring `ConfirmSwitchToExisting`'s OAuth-conflict copy but framed positively. Lift when a designer is in the loop; the engineering safety net (preserves progress) is in place either way.

### Achievements
- **Bot-vs-human duplication for the rest of the registry.** `FIRST_BUST_DEALT` / `BUST_DEALT_5` are bot-only via `mode = BOTS` — same pattern needs to be applied to the rest of the registry when MP ships in Phase 4.2+. The "Beat Jane 10 times" entries are already bot-keyed by personality name; the volume / endurance / stack-swing / pot-size achievements default to `mode = EITHER`, which is fine until MP arrives. Decide at MP-launch time whether prestige-bearing ones (Comeback, Don't Call It a Comeback, Pot 5K) deserve human-only variants with separate ids.
- **Earned vs. unearned visual differentiation on the Achievements page — front-side "Earned" label + back-side relative date landed 2026-05-22, broader treatment still open.** Today's medallion (`AchievementMedallion.kt`) already does more than the bullet's original "dimmed text" framing — earned tiles render at full rarity-color gradient + shimmer, locked-non-mystery tiles get the same gradient at 0.45 alpha plus a "$progress / $target" chase chip, mystery-locked tiles get "?" + "Locked". The new bits: (a) the front-of-tile reward label flips to "Earned" once earned (previously "+X XP" same as locked), so the at-a-glance prestige signal isn't competing with the chase number locked tiles use; (b) the back-side header reads "Earned · 3d ago / 2w ago / 1mo ago / today / yesterday" via a small `formatEarnedAgo(earnedAtEpochMs, nowEpochMs)` helper in the same file (covered by `FormatEarnedAgoTest`). Still open from the spec: a fully separate "greyscale silhouette + lock glyph + '???'" locked treatment (the current alpha+gradient reads as "faded" but not "locked") and the My Items "Earned" filter pair. Designer call on whether to go further; the at-a-glance bar is mostly met by today's treatment.

### Rank screen
- **Rank/league surface isn't built out.** XP screen exists; the rank page is a stub. Either build the V1 form (current tier, what unlocks at each tier, no league mechanic yet) or be explicit it's gated until V1.1 leagues. Decide before V1 ship.

### Home screen redesign
- **Whole-screen redesign of Home.** The current Home doesn't match the brand — feels generic compared to the Card Hall positioning in [product-spec.md](./product/product-spec.md). Direction: "Duolingo big-surface energy, but more elegant, less kiddy" — large primary CTAs (Play with bots, future Play with friends / Find a room), prominent progression visibility (XP, Rank, daily/seasonal pull), but never feel like a casino skin or a kids' app. **Needs design pass first** — pull from product-spec.md §3 (the Card Hall positioning) and §7 (Home as the entry point), the existing voice-and-copy.md, and the brand notes in §3.1 ("dark mode, muted accents, type-driven moments, never saturated casino-green"). Out of scope until the design pass is done — engineering follows. **Future state to keep in mind during design:** Home eventually has two MP entry points — "Play with friends" (room code / direct invite) and "Find a room" (public matchmaking). Even if those aren't wired in V1, the design should accommodate them without restructuring.

### `Profile.Fallback` per-feature audit

Carried over from the deleted `docs/auth-rework.md` (the doc itself was removed when slice B landed; this is the bullet that didn't ship with it).

**Background.** The 2026-05-23 Auth/Profile split introduced a sealed `Profile` type:
- `Profile.Authenticated` — real Supabase user + `/v1/me` row.
- `Profile.Fallback(id = clientLocalUuid)` — fires when auth couldn't resolve AND there's no cached real profile. The client-generated UUID is persisted across launches so any local-only state has a stable key.

The Fallback case is rare in practice (it requires fresh-install + no network during anonymous sign-in + no cached profile from a prior session). But it IS a defined state the architecture now exposes, and each feature surface needs an explicit decision about its behavior in that state.

**The audit:** per surface, decide one of:
- **Cached browse works** — inventory list, equipment list, achievements page, XP screen. These can render off the local DB; no server identity required.
- **Hard-gate** — anything that mutates server state (Edit Profile, Shop purchase, Claim Account, Sign In, Multiplayer). Should refuse with a "you need to be online" / "we couldn't reach the server" message.
- **Soft-gate / read-only** — surfaces that *can* render but where mutations would silently fail. Better to disable the mutation affordances explicitly.

**Surfaces to walk through:**
- Home (chip badge, XP, active-rooms list — already gracefully handle null/empty)
- Shop (catalog browse vs purchase)
- Profile (display name + avatar — Fallback has none; show "Guest" or similar?)
- Edit Profile (already hard-gates on `Profile.Authenticated.filterIsInstance`)
- Claim Account
- Inventory / My Items
- Multiplayer (Lobby create/join, in-room)
- Settings / Feedback / Bug Report (currently seed email from profile — Fallback should render empty form)

The global offline banner from commit `775aa11` sets baseline expectations; this audit is per-surface polish. **Plays best as a designer-in-the-loop pass** — engineering picks up the screens after the per-surface behavior is decided.

### Device smoke test before merging `dev` → `main`

The dev branch is 17 commits ahead of `origin/dev` with the full Auth/Profile rework + chip-grant move + FK migration. None of it has been exercised on a real device against the live server yet.

**Minimum checklist before the dev → main merge:**
1. Fresh install on Android (or iOS) against the dev server.
2. Confirm onboarding "Get Started" lands on Home without hanging.
3. Confirm chip balance hydrates (no 0 → 10K flash; instead null → authoritative).
4. Sign up → verify email → claim account flow end-to-end on a real device.
5. Edit profile, save, observe optimistic update + server-confirmed value.
6. Shop purchase via the test billing path; chips deduct + restore correctly.

Not blocking the PR being open; blocking the merge.

---

## C. Multiplayer hardening

The lobby + reconnect-grace foundation landed (per project memory); these are the gaps before we trust strangers to share a room.

- **Implement buy-in / stack / re-buy mechanic.** Spec landed in [product-spec.md §4.1 → Wallet, stack & buy-ins](./product/product-spec.md#wallet-stack--buy-ins); engineering still needs to do it. Sketch of the work:
  - **Server:** new "table reservations" concept — buy-in moves wallet → table-held balance on sit; reverses on stand / sweep-evict. Hand resolution moves chips between table-held balances (no wallet touch mid-hand). Wallet sync is unchanged.
  - **Client:** play screen shows *stack* (not wallet); home / shop / profile keep showing wallet. Re-buy dialog on stack=0 (auto-prompt, free if wallet covers). Bust-protection path remains as-is. Sit-out toggle in seat menu.
  - **Bot tables:** stakes are derived from the bot-difficulty entry on the home screen (Casual → `StakeTier.Casual`, Standard → `StakeTier.Standard`, Challenging → `StakeTier.High`) — `SoloBotsPokerSessionFactory.toStakeTier()` does the mapping and threads it into `LocalBotsSession.settings`. The in-dialog tier picker was stripped 2026-05-23 (redundant with the difficulty entry, and the 5-pill row was clipping at the dialog width); `BotTableSetupDialog` is now seat-count only and `PlayBotsRoute` no longer carries a `stakeTier` parameter. `StakeTier` itself stays — five spec tiers (Practice 1/2/200, Casual 5/10/1k, Standard 25/50/5k, High 100/200/20k, Premium 500/1k/100k) with `toRoomSettings()` derivation — for the upcoming MP matchmaking work. Remaining: V1 mechanic itself (rebuy on bust=0, stack-vs-wallet display, sit-out toggle) and the MP buy-in flow — both still open as their own sub-bullets above/below.
  - **Toast on sweep-evict refund:** "your stack came home — N chips returned to your wallet." Currently a gap noted in product-spec.md §5.6.
  - **Anti-smurf gate** ([§5.3](./product/product-spec.md#53-public-rooms)): server rejects sit-down if buy-in > 25% of wallet. Client surfaces the "you need ≥ N chips for this tier" message before the network call.
  - **Out of scope for V1:** host-customizable blinds (V1.x), antes, rake, voluntary forfeit surface (V1.x).
- **MP credit by table composition** ([§5.4 table](./product/product-spec.md#mp-credit-by-table-composition)). The "≥2 humans AND humans ≥ bots" rule for granting MP XP / league credit / achievements is documented but not enforced anywhere. Without it, two friends + four bots is a chip-farm exploit. Enforcement lives wherever post-hand XP / progression hooks fire — likely in the server's hand-resolution path, gating which progression events get emitted for which seats. Client also needs the visible "Practice tier · bots present" label per [§5.3](./product/product-spec.md#53-public-rooms). **V1-blocker** for integrity once MP is end-to-end playable.
- **MP games don't actually work end-to-end.** Reported 2026-05-20 by the human: joining a multiplayer room doesn't produce a playable game. **Needs human reproduction first** before an automated worker should touch this — the failure mode isn't documented (does the room create, do players join, does the deal happen, do actions propagate?). Reproduction steps + a concrete failure signature need to live in this item before it's worker-pickable. Likely intersects with `RemotePokerSessionFactory` / `RemoteGameSession`, which were scaffolded for Phase 4.2 but may not be fully wired.
- **Orphaned room policy — robust, simple.**
  - Last human leaves → kill the room.
  - User taps back → leave the room (currently the WS may stay attached; verify the back path tears down).
  - App dies / disconnect → keep the seat warm via the existing `disconnectedAt` grace timer. The sweep cron (`POST /v1/admin/sweep-disconnected-room-members`, default 5 min) already evicts. After eviction the user's next launch should *(a)* tell them the seat was forfeited and *(b)* show what their stack returned. None of that surfaces yet.
  - On app launch, before allowing a Join → check `GET /v1/me/active-rooms` — server endpoint landed 2026-05-21 (filters `RoomService.snapshot()` by membership). Client repo surface landed 2026-05-21 (`RoomRepository.getActiveRooms()` + `GetActiveRoomsOutcome` covering Success / NotSignedIn / NetworkError / Unknown). Home-screen consumer landed 2026-05-21: `HomeViewModel` queries on init/refresh; `ActiveRoomBanner` per active room with Rejoin (→ `LobbyRoute(prefilledCode=code)`) and Forfeit (confirms then optimistic-`leaveRoom`, rehydrate on network failure). Remaining: verify on device once the MP "creating a room flips into Reconnecting" repro below is unstuck — until then we can't realistically exercise the rejoin path end-to-end.
  - **Treat >1 active rooms as recovery, not a normal state.** A user should only ever have one active room — anything else is the system failing to clean up after an app crash, force-quit, or network blip. **Client-side reconciliation landed 2026-05-21:** `HomeViewModel.loadActiveRooms()` sorts the server response by `createdAtEpochMs` desc; if there's more than one, it keeps the newest in state and fires `appScope.launch { roomRepository.leaveRoom(code) }` for each stale room — fire-and-forget under the app-lifetime scope so the cleanup outlives navigation away from Home. The Home banner is now single-room by construction. Logged via `KLog.withTag("HomeViewModel")` at WARN so the steady-state rate is visible if it ever becomes non-zero. **Still open — server side:** tighten the contract so the multi-room state can't happen in the first place — WS heartbeat (Ktor has ping-pong built in) plus a sweep that hard-evicts after N missed pings rather than just marking `disconnectedAt`. The client reconciliation is the belt-and-suspenders, not the fix. **Open product call, needs human:** after the sweep eviction, do we *sit out* the user (auto-fold, keep the seat for a longer grace window) or *fully remove* them from the room? "Sit out" is friendlier; "remove" is cleaner. Pick before wiring the sweep behavior.
  - The reconnecting-while-mid-hand path inside `ReconnectingRoomSocket` already exists; that's not the gap. The gap is the *user surface* for "you have an ongoing game."
- **Forfeit-then-spectator behavior after timeout.** Today the sweep evicts and the seat opens. Alternative: after timeout, auto-fold the user's hand for the rest of the session, leave them subscribed read-only, let them reconnect into spectate. That's a Phase 4.2 question — note it here so we don't re-derive it.
-    **Repro 2026-05-21:** creating a new MP game flips the room into "Reconnecting" immediately and surfaces socket errors. Smell: [RoomRepositoryImpl.kt:36](../libraries/rooms/impl/src/commonMain/kotlin/com/cards/libraries/rooms/impl/RoomRepositoryImpl.kt#L36) returns from `createRoom()` as soon as the POST succeeds, and the client then attaches to `ReconnectingRoomSocket.observe(code)` ([ReconnectingRoomSocket.kt:63](../libraries/rooms/impl/src/commonMain/kotlin/com/cards/libraries/rooms/impl/ReconnectingRoomSocket.kt#L63)), which emits `Connecting` and — if the handshake doesn't land — `Reconnecting` with backoff. Likely the WS attach is racing the room being fully provisioned server-side. **2026-05-21 update — diagnostic + classification slice landed:** the socket now logs the handshake status code on every failure (parsed from Ktor's `WebSocketException` message — the only handle we have) AND classifies 4xx as `Closed(Rejected)` terminal instead of looping forever as Reconnecting. The existing `ClosedReason.Rejected` enum value (previously unused) now actually fires. Next step is on-device repro: open the lobby, watch logcat for the new status-suffixed warning; the status code will say whether the handshake is being rejected (4xx — server-side membership/race issue) or the upgrade is just timing out (no status, network-layer issue). Then fix the underlying server-side race (likely a missing membership-row commit before the WS handler is reachable). Collector handling for `Closed(Rejected)` — "auto re-POST /join + re-subscribe" — is still TBD; for now Rejected surfaces as a Closed state and the lobby UI shows the same "room closed" treatment as `RoomDeleted`.
---

## D. Engineering / structural

Quality issues the user has flagged across the codebase. None are blockers, but they compound. Track them here; pull each in when the surrounding area is open.

### Config plumbing — `featureValue` vs DI-bound `ConfiguredValue`
**Question on the table:** `FeatureConfig` declares values with `by featureValue(...)` (the current pattern). The user previously preferred DI-bound `ConfiguredValue` objects, each able to advertise itself to the QA menu autonomously.

The current design: `FeatureConfig` subclasses are aware of the QA menu via convention; the QA menu enumerates declared features.

Decision options:
- **Keep `featureValue`.** Cheap. Works. The QA-menu autonomy concern is real but small; QA menu already auto-discovers.
- **Switch to DI-bound singletons per value.** Each value is its own `@Inject` singleton; QA menu takes a `Set<ConfiguredValue<*>>` multibinding. Adding a value is one class with one annotation; QA discovery is automatic and decentralized.

Lean: revisit when we add the next feature config. Not blocking V1. Capture the open question here so it doesn't get re-derived.

### Network retry / `authedCall` helper
**Landed 2026-05-24 (reference impl):** `NetworkClient.authedCall(description) { client -> … }` extension lives in `:libraries:networking`. Hands the authenticated `HttpClient` to the block, wraps in `Catching`, and emits a structured failure log with a classification tag (`timeout` / `http <status>` / exception-class) keyed by the `description` so cross-repo failures aggregate cleanly. Cooperative-cancellation semantics match `Catching { }`. Token refresh on 401 still rides Ktor's `Auth` plugin (unchanged). `InventoryRepositoryImpl.doSync()` is the migrated reference — the redundant per-repo failure warn was dropped since the helper covers it.

**Remaining (follow-up):**
- Migrate the rest of the repos: `RoomRepositoryImpl` (`/v1/rooms/create`, `/v1/rooms/join`, `/v1/me/active-rooms`, `leaveRoom`) and `IdentityRepositoryImpl`'s server hops. `RoomRepositoryImpl` is not a clean mechanical fit — each method maps specific HTTP status codes to specific sealed `*Outcome` entries (NotFound / Conflict / Unauthorized / NetworkError / Unknown), so a migration would need to either layer custom catches on top of `authedCall` or extend the helper. Worth a dedicated cycle when someone opens that area. **Landed:** `InventoryRepositoryImpl.doSync()` (2026-05-24, the reference impl); `ChipsRepositoryImpl.sync()` keyed `wallet.sync` (2026-05-24); `EquipmentRepositoryImpl.sync()` keyed `equipment.sync` (2026-05-24); `UserMessageRepositoryImpl.sync()` keyed `messages.sync` (2026-05-24).
- Retry composition: callsite-opt-in via `authedCall(description, retry = RetryPolicy.exponential().withJitter().maxRetries(N))`. The helper defaults to `RetryPolicy.None` — most calls shouldn't auto-retry (POSTs may not be idempotent). The retry system landed 2026-05-24 in `:libraries:networking/retry/`: `RetryPolicy` (fluent immutable chain), `Backoff` (None/Fixed/Linear/Exponential), `Jitter` (None/Equal/Full), `withRetry` (combinator). Replaced the old `withBackoffRetry`. Offline-aware retry deferred — see `docs/backlog.md`.

### Post-rework identity follow-ups
The 2026-05-23 Auth/Profile split landed `SupabaseAuthGateway` (interface) + `RealSupabaseAuthGateway` + `SupabaseAuthRepositoryImpl` with a 464-line `SupabaseAuthRepositoryImplTest`. `SupabaseProfileRepositoryImpl` coverage is also in place. Remaining structural concerns:
- `SupabaseProfileRepositoryImpl`'s `ProfileCache` overlaps supabase-kt's own session cache. The new `Catching { server }.fold(success → it.also(write), failure → cache.read())` pattern means we only *consult* the cache on failure, which is correct — but we still *write* on every success, so the storage cost remains. Worth measuring before optimizing.
- Profile-as-DI: rather than each consumer awaiting `ProfileRepository.observe().first()`, inject a `Lazy<Profile.Authenticated>` (the way `AppConfig` is treated) that should be initialized at boot, with `runBlocking` as worst-case fallback. Makes consumer code straight-line and removes a class of "what if the profile isn't ready" bugs.

### Identity cold-boot resilience
**Problem:** Anonymous sign-in roundtrips Supabase, and on a fresh install with poor / no network the `/auth/v1/token` call times out (`HttpRequestException`, 10s default) and the user is stranded in a `SessionState.Unknown` — they can't even play bots. Repro: fresh install on a heavily throttled connection.

This is a poker app. A user shouldn't need internet to play bots, even on first launch.

**Sketch — pick one or layer them:**
1. **Cached-identity fallback.** If we have a previously-cached identity from a prior session, fall back to it and treat sign-in as a background reconciliation. Doesn't help true first-launch.
2. **Local `ErrorIdentity` / `OfflineIdentity`.** Generate a local-only id so bot play is fully unlocked. On next successful auth, migrate any local progress (chips, XP, achievements) into the real account. Riskier — needs a real migration story.
3. **Defer anon sign-in until we actually need it.** Bots don't strictly need a server-side identity; only purchase / MP / leaderboard surfaces do. Treat anon-auth as a *prerequisite for those surfaces*, not for app launch. Boot offers bots immediately; sign-in happens lazily before the first networked feature.

**Lean:** option 3 is the most honest — it matches what the app actually needs from a server identity. Option 2 is technically possible but introduces a non-trivial reconciliation surface we'd otherwise avoid.

**Files / hints:** [SupabaseAuthRepositoryImpl.kt](../libraries/identity/impl/src/commonMain/kotlin/com/cards/libraries/identity/impl/auth/SupabaseAuthRepositoryImpl.kt) (`resolve()` / `retry()` loop), [AppViewModel.kt](../apps/compose/src/commonMain/kotlin/com/cards/AppViewModel.kt) (splash gate). **Discuss approach with the human before implementing** — this is an executive decision call, not a worker pickup.

### Module sprawl: `libraries/cards`, `gameplay`, `game`
**Problem:** `libraries/cards` was originally the "highly shared" dumping ground. It has grown to be too big. We now also have `libraries/gameplay` (engine types) and `libraries/game` (session abstraction). The three overlap in confusing ways for new readers.

Not a V1 blocker, but worth a deliberate pass:
- Audit what's in `libraries/cards` today. Which entries are *truly* cross-feature primitives, and which were dumped there because no better home existed.
- Likely splits: progression (XP/achievements/ranks) belongs in `libraries/progression` or stays — but if it stays, the cosmetics + chips + identity etc. need their own homes.
- The high-cohesion / low-coupling goal probably means tearing this down and putting up 3–5 narrower libraries.

Capture as a deliberate refactor pass. Do not entangle it with feature work.

---

## E. Already on the books elsewhere

For completeness; don't re-derive these here when the link below tracks them.

- **Known sharp edges** — [project memory](~/.claude/projects/-Users-elijahdangerfield-Workspace-Cards/memory/project_known_sharp_edges.md) (auto-loaded).
- **Phase 4.2 server-authoritative gameplay** — out of scope until we choose to start it. See [docs/decisions.md](./decisions.md) and the `:libraries:gameplay` JVM-target blocker noted in memory.
- **Real platform billing impls (Play Billing v6+ / StoreKit 2)** — billing scaffold + `FakeBillingClient` is in place; provisioning store listings is the gate, not engineering. `DevBillingClient` currently bridges the gap so debug builds render chip-pack tiles end-to-end; once the real platform bindings land, both `DevBillingClient` and `NoOpBillingClient` become candidates for removal.
- **OAuth UI gated by `IdentityFeatureConfig`** — Apple/Google buttons are wired but flagged off until dashboard credentials exist.
- **Username localization, bot name localization** — V1.x / V2 problems.
