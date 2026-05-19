# Cards — V1 MVP Scope

**Last reviewed:** 2026-05-19 · **Status:** Active · **Companion to:** [product-spec.md](./product-spec.md)

V1 = the smallest *coherent* expression of [Card Hall](./product-spec.md). Shipping less = a generic solo bot poker app (Offsuit's space, already taken). Shipping more = scope creep and quality compromise (the Pokerrrr 2 trap). This document defines that cut.

**Working punch list:** [`docs/v1-remaining.md`](../v1-remaining.md) — what's actually left to ship. Updated continuously as items land. This doc is the scope frame; that doc is the working sheet.

---

## 1. Success criteria

Success is *the flywheel starting*, not just "the app ships."

| Metric | Target | Why |
| --- | --- | --- |
| App store rating | ≥ 4.3 | Polish floor. Below this, ASO grinds. Offsuit hit 4.6. |
| Day-2 retention | ≥ 40% | Tests the lightweight loop (Today's Quests + achievement progression). No streak mechanic by design (see [spec Appendix C.1](./product-spec.md#c1-daily-login-streak-rejected-2026-05-16)). |
| Day-7 retention | ≥ 25% | Tests the social hook — engaged users have invited a friend or joined a public room. |
| Day-30 retention | ≥ 10% | Tests whether the meta-game has legs *without* leagues (which ship V1.1). |
| First-session → first-game time | < 60 sec | The hard rule from [spec §2.1](./product-spec.md#21-first-session--the-60-second-rule). |
| Friend invite rate | ≥ 25% of installs | Virality measure. Friend network = compounding growth. |
| Anonymous → claimed conversion | ≥ 20% | Tests whether the claim flow is friction-free. |
| First chip-pack purchase | ≥ 3% of D30-retained | First revenue signal. |

Calibrate against real data after 4 weeks post-launch.

---

## 2. Must-have

V1 doesn't ship until all of these clear the quality bar.

### 2.1 Already shipped

Phases 1–2 (engine, bots, defensive infra), the full V1 progression UX (XP, ranks, ~37 achievements, chip wallet), Phase 3 (anonymous-by-default auth + claim flow + delete account), Phase 4.1 (multiplayer lobby foundation with rooms, codes, reconnect grace timer, ops endpoints), server-authoritative chip wallet, server-side product catalog, and the billing scaffold (NoOp default; FakeBillingClient for tests).

Day-to-day status lives in [project memory](~/.claude/projects/-Users-elijahdangerfield-Workspace-Cards/memory/project_cards_v1.md) and in [`docs/decisions.md`](../decisions.md). What's *not* yet shipped is in [`docs/v1-remaining.md`](../v1-remaining.md).

### 2.2 Phase 3 — Auth

- Anonymous-by-default identity with auto-generated name + avatar ([spec §2.1](./product-spec.md#21-first-session--the-60-second-rule))
- **Anonymous state persisted server-side** keyed to UUID + device fingerprint (not local-only) — see [spec §6.1](./product-spec.md#61-anonymous-by-default)
- **Best-effort account revival on reinstall** — fingerprint match + platform keychain (iCloud Keychain on iOS, Block Store on Android) to recover anonymous accounts when the user comes back. "Welcome back" flow if match found. See [spec §6.1](./product-spec.md#best-effort-account-revival-on-reinstall).
- Server-side XP / chip persistence via Supabase
- Account claim (Apple / Google) — anonymous is default, claim is optional but smooth
- **Smart claim prompts** at meaningful moments (first MP win, first Epic/Legendary achievement, chip balance 5K, first shop interaction, Level 10). Each fires once, dismissible. Copy in [voice-and-copy.md §5.11](./voice-and-copy.md#511-smart-claim-prompts-anonymous-users).
- **Starter grant deduplication** — 10K starter is one-per-device-fingerprint, closes the uninstall-reinstall exploit
- Account deletion

### 2.3 Phase 4 — MP foundation

- Server-authoritative dealer over Ktor WebSockets
- Friend Games: private rooms via 6-char codes + universal/app deep links
- Bot fill for empty seats (free, ≥2 humans for MP credit — [spec §5.4](./product-spec.md#54-bot-fill))
- Reconnect handling with bot-continuation passive seat
- Replace "Create Game" / "Join Game" coming-soon dialogs

### 2.4 Phase 5 — Public rooms + table-side social + moderation

- Public rooms with Quick Match (hero CTA — [spec §5.3](./product-spec.md#53-public-rooms))
- Browse public rooms (secondary surface)
- 5 stake tiers (Practice / Casual / Standard / High / Premium) with anti-smurf rule
- Anti-friend matchmaking in Quick Match
- Anonymous host restriction (claimed accounts only for hosting)
- **Emoji blasts** (~12 base emojis, 8s cooldown, "mute this player's emoji" available)
- **Reactive emoji** (auto-fired on big moments — 🤯 on >50BB pot, etc.)
- Tap-avatar profile preview at the table
- **Block** (per-user, instant, mutual)
- **Report** (server-side queue → manual human review; **no auto-bans in V1**, see [spec §7.2](./product-spec.md#72-v15-approach))
- Host-boot (public rooms only)
- Server-side logging for future integrity work + retroactive achievements ([spec §3.1 technical principles](./product-spec.md#31-gameplay))

**Cut from V1, defers to V1.x:**
- **In-game chat** (canned or free-text) — friends already have external chat channels; public rooms with stranger chat is the highest-moderation / lowest-value surface. Defer until moderation infra matures. See [spec §5.5](./product-spec.md#55-table-side-social).
- **Auto-ban triggers** — V1 is manual review only. Automated suspension at scale is V2+.

### 2.5 Phase 6 — Today's Quests + notifications

- Today's Quests: 3/day, three flavors (effort / variety / skill), refresh at local midnight. No streak mechanic; missing a day costs nothing.
- Event-driven push notifications only. Never time-of-day pings, never beg-copy. ([spec §8](./product-spec.md#8-notifications))

### 2.6 Phase 8 — Shop + IAP

- Cosmetic shop with categories: card backs, table felts, emote packs, avatar frames
- All shop items chip-priced (single-currency model — [spec §4.1](./product-spec.md#41-chips--the-only-currency))
- **Unlock-only "trophy case"** showing achievement-earned cosmetics — visible, not buyable ([spec §4.2](./product-spec.md#42-the-unlock-only-catalog))
- Native StoreKit + Google Play Billing
- Three chip packs: $1.99 / $9.99 / $49.99
- No P2W shop items — all cosmetic, never gameplay-advantage ([spec §4.5](./product-spec.md#45-no-pay-to-win--the-hard-rule))
- Solo bot games: free settings toggle for live equity display (practice aid, never in MP)

---

## 3. Should-have

Strongly recommended; first to descope if scope tightens.

- "Recently played with" surface on home / profile
- Explicit friend list (separate from recently-played-with)
- End-of-session recap ("8 hands won, 240 XP, today's quests complete, level up to 7")
- Subtle sound design (default off, opt-in, real-recorded, never casino-loud)
- Live activity ticker on home screen (§2.4 of spec)

---

## 4. Could-have

Defer unless free. Each documented in the spec.

- Play-style heat map ([spec §6.4](./product-spec.md#64-play-style-heat-map-phase-10)) — V1.2
- Hand history viewer — V1.2
- Profile bio + pre-filled tags — V1.x polish
- Audio voice reactions — V2 (moderation overhead)
- Bot-coach overlay ("what would the bot do?") — V1.x

---

## 5. Explicit V1 non-goals

The most important section.

| Cut feature | When | Why deferred |
| --- | --- | --- |
| Weekly leagues | **V1.1, 6–8 weeks post-launch** | The retention multiplier — adds 4–6 weeks (matchmaking telemetry, integrity sweep, reward calibration). V1 retention rests on quests + achievements + friend hooks; intentionally lighter than streak-heavy alternatives ([spec Appendix C.1](./product-spec.md#c1-daily-login-streak-rejected-2026-05-16)). Leagues without V1 calibration data would launch broken. |
| Royal Flush Tournament | V1.2 | Requires Royal Flush tier with stable population. |
| Seasonal battle pass | V1.3 | Needs a season's cosmetic content, vault mechanics. Limited-time cosmetic drops can ship before the full season framework. |
| In-game chat (canned or free) | V1.x | Friends have external chat; public-room stranger chat is highest-moderation / lowest-value. Emoji blasts cover the table-side social moment. |
| Auto-ban / shadow-ban automation | V2+ | V1 is manual human review only. Volume at V1 scale makes manual feasible; auto-bans at low volume risk false-positive disasters. |
| Bot name localization | V1.x / V2 | English-market launch first. Per-locale name table or localized titles when we expand. ([spec Appendix B.20](./product-spec.md#appendix-b--open-decisions)) |
| Tournaments (sit-and-go, MTT) | V2 | Separate game mode. V2 expansion. |
| Additional card games (Blackjack, Hearts) | V2+ | Earn the right by being great at poker first. |
| Clans / clubs | V2+ | Pokerrrr 2's 2,000-member club pattern is overkill pre-PMF. |
| Spectator mode | V2+ | Useful but not load-bearing. |
| Subscription (Cards Pro) | V3 candidate | Card Hall is the dominant motion; sub would muddy positioning. |
| Rewarded ads | V3 candidate | Default is no ads, ever. |
| External billing | At $100K/yr IAP | [spec §4.4](./product-spec.md#44-iap--payment-processors). RevenueCat-mediated on Android first. |
| ML-based anti-cheat | V2+ | Log + report + manual review is V1 floor. |
| Loot boxes / random packs | **Never** | Direct-sale only. Sidesteps app-store odds-disclosure. |
| NFTs / crypto | **Never** | Audience overlap is zero. |
| Two-currency model (Gems) | **Never** | Rejected for simplicity + cleaner anti-P2W. [spec Appendix C.2](./product-spec.md#c2-two-currency-economy-with-gems-rejected-2026-05-16). |
| Daily login streak | **Never** | Off-brand for episodic entertainment. [spec Appendix C.1](./product-spec.md#c1-daily-login-streak-rejected-2026-05-16). |
| Expiring chip bonuses / wheel-spin rewards | **Never** | FanDuel / Zynga patterns. [spec Appendix C.5](./product-spec.md#c5-expiring-chip-bonuses--wheel-spin-rewards-rejected-2026-05-16). Bust protection + welcome chips serve the same retention goal honestly. |
| Synthetic / fake users | **Never** | [spec Appendix C.6](./product-spec.md#c6-synthetic--fake-users-to-fill-the-ecosystem-rejected-2026-05-16). Bots are labeled bots; leagues scale cohort with population. |

---

## 6. Quality bar — non-negotiable

A great V1 that does fewer things beats a mediocre V1 that does more.

- **Zero pop-ups** for monetization, retention nudges, "rate us" prompts
- **Zero begging copy** in notifications or in-app
- **No fake casino aesthetics** — no neon, no fake felt, no slot-machine sounds
- **Real haptics** on real moments (bet/raise/all-in, card flip, level-up, achievement unlock)
- **Sound design from day one** — chip clatter and card flicks, opt-out
- **No crashes, no freezes** (Pokerrrr 2's #1 review-killer)
- **Reconnect works flawlessly** (Offsuit's #1 complaint)
- **Empty states are neutral and inviting** — never punitive
- **Onboarding < 60 seconds** to first hand
- **Identity is auto-assigned**, not demanded

Reinforced in [spec §10 brand checks](./product-spec.md#10-brand-checks). Run them before merging any user-facing feature.

---

## 7. Sequencing

Assumes one focused full-time engineer (more = faster). Phases can overlap on dependencies.

| Order | Phase | Estimate | Critical path? |
| --- | --- | --- | --- |
| 1 | Phase 3 — Auth + persistence | 3–4 wk | Yes — blocks all below |
| 2 | Phase 4 — MP foundation | 6–8 wk | Yes |
| 3 | Phase 5 — Public rooms + social + moderation | 4–6 wk | Yes for public rooms; some social can parallel Phase 4 |
| 4 | Phase 6 subset — Quests + push | 2 wk | Can parallel late Phase 5 |
| 5 | Phase 8 — Shop + chip IAP | 4–5 wk | Final; cosmetic asset design can parallel earlier phases |

**Estimated total: 5–6 months.**

---

## 8. V1.x roadmap

| Release | Window | Headline addition |
| --- | --- | --- |
| **V1.1** | 6–8 wk post-launch | Weekly leagues (Phase 7) — the retention multiplier, calibrated against V1 telemetry |
| **V1.2** | ~3 mo post-launch | Play-style heat map + hand history (Phase 10), reactive emoji — the polish layer |
| **V1.3** | ~4–5 mo post-launch | First season + battle pass (Phase 9) — depth / LTV layer |
| **V2** | ~9–12 mo post-launch | Tournaments, additional games (Blackjack first), clans, ML integrity, spectator mode |

---

## 9. What we're betting on

That **friend-first social poker with a cosmetic-only economy** is an open market.

- **Offsuit proved the demand for a modern, anti-scam poker app** (4.6 stars, 404K+ downloads from a solo experience). They left MP unshipped.
- **Pokerrrr 2 proved that friend-game features attract users** (millions of downloads on a clunky app). Their execution is the moat; we cross it with polish.
- **Marvel Snap, Clash Royale, Duolingo proved the gamified meta-loop works on mobile.** None are in poker. We bring the loop to poker.

If V1 retention proves the bet, V1.1 ships leagues into a hungry, primed audience.

---

## 10. What V1 does *not* prove

Be honest. V1 doesn't validate:

- Whether **leagues** are the right retention engine — V1.1, not V1
- Whether **the battle pass mechanic** lands — V1.3
- Whether **ML anti-cheat is worth investing in** — earn the data first
- Whether **the audience extends beyond poker** — V2 question

V1's question is the modest one: *will users come back tomorrow, and will they bring a friend?* If yes, we earn the right to ship the harder questions. If no, the harder questions wouldn't have saved us anyway.
