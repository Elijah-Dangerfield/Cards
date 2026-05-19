# Cards — Product Spec

**Last reviewed:** 2026-05-16 · **Status:** Active · **Owner:** Elijah Dangerfield

Cards is a gamified social poker app for the 18–34 player who plays poker for fun, not income. V1 ships as No-Limit Texas Hold'em; V2+ adds other card games. Brand center is competitive multiplayer with weekly leagues; solo / bot play is a first-class supported mode.

**Companion docs:**
- [v1-mvp.md](./v1-mvp.md) — V1 launch scope
- [decisions.md](../decisions.md) — engineering decision log
- [Appendix C](#appendix-c--removed-mechanics) — design decisions we considered and rejected

---

## 1. Positioning

### 1.1 Brand promise

> *"Cards is where your group plays poker. Climb the leagues, collect the felt, ping your friends. Chips fund the table and the shop. The trophies on your profile? Those you earn — they're not for sale."*

### 1.2 What we are

| | |
| --- | --- |
| **Gamified** | Three meta-systems carry progression: **Today's Quests** (3 small daily challenges, no obligation), **Weekly Leagues** (30-player groups ranked by this week's MP XP, top promote / bottom demote, resets Monday), **Seasonal Battle Pass** (6-week themed cosmetic tracks — V1.3). Each is detailed in §3. |
| **Social-by-default** | Room codes, deep links, friends, "recently played with" — the spine, not a feature. |
| **Cosmetically expressive** | Card backs, table felts, frames, emote packs. Marvel Snap's "slow collection" applied to poker visuals. |
| **Aesthetically grown-up** | Dark, considered, type-driven. Closer to Linear than to a Vegas casino. |
| **Ethically monetized** | Chips fund gameplay and common cosmetics. Prestige items are earned, never for sale. No P2W. No loot boxes. No wait timers. |

### 1.3 What we are not

- **Not a casino.** No real money. No cash-out. No insurance bets. No bonus wheels.
- **Not a training tool.** Learning-friendly bots, but we are not GTO Wizard.
- **Not a generic card-game platform.** V1 is poker. We earn the right to add Blackjack/Hearts/etc. by being great at poker first.
- **Not Discord.** Chat is ephemeral and table-scoped. No persistent DMs, no friend feeds.
- **Not Pokerrrr 2.** No espionage theme. No 4-hour table expirations. We're for *modern* phone users.
- **Not MP-exclusionary.** Solo / bot play is supported and rewarded. See §3.7.

### 1.4 Voice & visual language

- **Visual:** dark mode primary; muted accent tones drawn from the deck (deep plum, midnight blue, slate green used sparingly — never the saturated casino-green felt); bold type-driven moments; particle/celebration polish only on big moments. Linear chrome, Marvel Snap celebrations, Apple Fitness medallions.
- **Voice:** confident, dry, occasionally funny. Never begs. "Welcome back" not "Hey high roller!"
- **Sound:** default **off**. Opt-in for users who want it. When on: restrained, real-recorded (one quiet card flick, one clean chip click on bet) — never casino-loud rattles, no slot-machine cues, no background music. Haptics carry most of the tactile load.
- **Haptics:** big on bet/raise/all-in. Subtle on card flip. Celebration on level-up + achievement unlock. Haptics, not audio, are the primary feedback channel.

---

## 2. Player journey

### 2.1 First session — the 60-second rule

The user is at a poker table within 60 seconds of tapping the app icon. No exceptions.

**Launch screen** offers two paths:
- **Continue as guest** (default, primary CTA) — anonymous-by-default, auto-identity
- **Sign in with Apple / Google** — existing user returning; skips onboarding entirely once authenticated

**New-user path:**
1. Tap "Continue as guest." No email, no avatar picker, no name picker.
2. **Auto-assigned identity:**
   - Display name: adjective + card-term + 2-digit number (`QuietAce72`, `LuckyJack04`). Editable later from profile.
   - Avatar: assigned from a base gallery (~24 options) with color-tint variation. Editable later.
3. **One explainer screen.** Tagline + one line + one button — "Tap to start."
4. Land on home screen (§2.4). **"Play Bots" CTA is pulse-animated** with one-time tooltip: *"Start here — meet the bots."* Disappears after first tap.
5. First bot game starts within ~10 seconds.
6. First hand ends → showdown dialog with XP. Possible achievement unlock animation.
7. Today's first quest appears. League assignment deferred until first MP hand.
8. After ~10 hands: soft "Invite a friend" prompt with deep link.

**Smart claim prompts** fire at meaningful moments throughout the journey (not at first launch). Triggers: first MP hand won, first Epic/Legendary achievement, chip balance crosses 5K, first shop interaction, reaching Level 10. Each fires once, ever, dismissible. Full table in §6.1; copy in [voice-and-copy.md §5.11](./voice-and-copy.md#511-smart-claim-prompts-anonymous-users).

**Returning-user path** (re-install or new device):
- Tap "Sign in." Auth completes. Skip explainer, skip onboarding. Land on home screen with their existing identity, level, achievements, chips, friends. Familiar territory.

**Why default identity, not chosen:** Reddit's data on default usernames/avatars showed conversion-to-first-action jumped meaningfully when they removed the "create your identity" gate. Identity is something the user *grows into* after the first action — not a barrier to it.

### 2.2 Day-7 cadence

1. Event-driven push (e.g., "*Your league resets in 2 days — you're 4 spots from promotion.*"). Notifications fire on real events; never on modeled "you usually play around now."
2. Open app. Home shows: league position, today's quest progress, friends online.
3. One-tap join a friend's table.
4. 15–30 hand session, ~10 minutes.
5. Session recap: hands won, XP earned, quests complete, league position change, any unlocks.

### 2.3 Weekly & seasonal rhythm

- **Monday local** — leagues reset. Promotion/demotion as a *celebration*, not a slap.
- **Mid-week** — peak engagement. The 6–11 PM window.
- **Sunday** — last-chance push for league position. Real urgency tied to a real cadence.
- **Every 6 weeks** — new season ships. Themed cosmetic rotation. Past-season items go to vault.
- **Every 18 weeks** — Royal Flush Tournament champion crowned. Hall of Fame on home screen.

### 2.4 Home screen — the hub

The home screen is brand-load-bearing. Its job is to communicate *"this is a living ecosystem of people playing right now"* — not just a launcher for solo bot games.

**Layout (top to bottom):**

1. **Identity strip** — avatar, display name, level badge, current league tier (or "Unranked"). Tap to open profile.
2. **Live activity ticker** — rotating real signals: *"RoyalQueen86 just promoted to Diamond"* · *"182 players online right now"* · *"Steve won 4 hands in a row at the table"* · *"Season 2 ends in 5 days."* Honest data only; never fake.
3. **Today's Quests tray** — 3 quests with progress bars. Tap to expand.
4. **Play routes** — the meat of the screen, four primary CTAs:
   - **Practice** (vs bots) — pulse-animated for first-time users
   - **Quick Match** (find a public room with strangers, one tap)
   - **Friend Game** (create with code, or paste a code to join)
   - **Tournament** — grayed in V1, "Coming in V2" tooltip
5. **Friends strip** — "3 friends online" with avatars; tap to see what tables they're at and join
6. **Featured cosmetic** — current limited-time item or featured shop drop. Tap to open shop.

**What the home does *not* show:**
- Login bonuses (we don't have them)
- "Spin to win" wheels (never)
- Pop-up promotions
- "Almost out of chips!" nudges
- Anything pop-up-y, urgent, or beggy

The home is *busy* with information the user actually wants (friends, leagues, quests, what's happening) and *quiet* on promotion. The ecosystem sells itself by being visible, not by interrupting.

### 2.5 The solo player's parallel journey

A pure-solo player (never invites a friend, never joins a public room) has a fully supported parallel cadence:

- **Day 1** — identical to §2.1. Bot table within 60 seconds.
- **Day 7** — notifications are quest- and achievement-based. No "your quest expires" pings.
- **Weekly** — no league reset (Unranked). Today's quests refresh each midnight without urgency.
- **Seasonal** — free-track battle pass progresses on solo play at 0.5×. They earn the season's headline cosmetic.

They get: full progression loop, achievement unlocks, common cosmetics, seasonal headline rewards. They don't get: league standing, the Royal Flush Tournament. **The app feels complete to them, not crippled.**

**Bridge to MP, when they want it:** Quick Match (§5.3) places them in a public room within seconds. The door is always one tap away.

---

## 3. Core systems

### 3.1 Gameplay

**V1:** No-Limit Texas Hold'em. 2–9 seats. Configurable turn timer (5–120s) and buy-in. Heuristic bots, five named personalities (Steve, Jane, David, Gina, Mike), three difficulty tiers. Server-authoritative dealer for MP (Phase 4). Reconnect on disconnect.

**Not in V1:** PLO. Tournaments. Heads-up duel mode. Run-it-twice.

**V2+ priority order:** Sit-and-go tournament → Heads-up duel → Pot-Limit Omaha → MTT → Blackjack vs dealer → Hearts/Spades.

**Technical principles** (engineering constraints with product implications):

1. **Bots and humans share one Action interface.** A bot is a Strategy that produces Actions like a human would. The dealer doesn't know the difference. Anything we build for MP (turn timer, reconnect, action validation) applies to bot turns too — bots get an artificial 0.5–3s "think time" within the same timer system. This unifies the codebase and prevents a "two systems to maintain" trap.
2. **Bots and humans speak the same protocol.** Server-side bot fill in MP is a bot process connecting as a WebSocket client, never a special-case path in the server. One bot decision codebase (`libraries/bots/`) runs in both contexts — locally for solo play, server-side as a WebSocket client for MP bot fill. **Bots cannot cheat by design** — they submit intents through the same channel as humans, so there's no privileged "bot peeks at hole cards" API available. Reconnect-as-bot (a human disconnects mid-hand) is just a server-side bot driver with a passive personality taking over the seat — unified mechanism. Concrete interface lives in `:libraries:game` (`GameSession`, `GameSessionFactory`, `SeatOccupant`, `PlayMode`).
3. **The play screen is source-agnostic.** `PlayPokerScreen` and `PlayPokerViewModel` take a `GameSession` interface and don't know whether it's backed by a local game engine or a remote WebSocket. Same widgets, same state machine, same achievement firing — one play surface, two backing implementations.
4. **Turn timer is server-driven in MP.** Currently absent in solo impl; needs to be retrofitted in Phase 4 such that solo bots and MP players use the same timer mechanism.
5. **Per-hand event log is the source of truth.** Server records every action with timestamps, cards, pot state, opponent set. This is what enables:
   - Retroactive achievement progress (when we add achievements in V2, we backfill from history — users don't start from zero)
   - Hand history viewer (Phase 10)
   - Integrity detection (§7)
   - Play-style heat map (§6.4)
   Schema must be flexible enough to support new aggregations without migration.

### 3.2 XP & levels

**XP measures engagement, not outcome.** Every finished hand awards XP regardless of win/loss. This is the load-bearing decision from [decisions.md 2026-05-14](../decisions.md).

**Per-hand formula** ([XpCalculator.kt](../../libraries/cards/impl/src/commonMain/kotlin/com/cards/libraries/cards/impl/XpCalculator.kt)):
- BASE: 10 XP per finished hand
- INVESTMENT: 1 XP per big blind committed (capped at 20 BB)
- SHOWDOWN: 10 XP at showdown
- HAND_STRENGTH: super-linear bonus (2 high card → ~70 royal flush)
- Bot mode: 0.5× multiplier (MP is canonical rate)

**Levels:** Level N requires N²×100 XP. **Status, not power.** Unlocks profile flair and titles at milestone levels ("Felt Veteran" at L25, etc.). Levels themselves stay numbered (1, 2, 3...); only the *titles* you unlock at certain levels are named. Levels are **never** a soft pay-gate.

#### Level vs League — they are different things

This is a frequent point of confusion. Quick reference:

| | **Level** | **League** |
| --- | --- | --- |
| Tracks | Lifetime XP | This week's MP XP only |
| Resets | Never | Every Monday |
| Range | 1 → infinite | 10 named tiers (Bronze → Royal Flush) |
| What it means | "How much have you played, ever" | "How well are you competing, this week" |
| Solo play counts? | Yes (0.5×) | No, MP only |
| Analog | Reddit karma, Steam level | Chess ELO bracket, Duolingo league |

Two players at Level 30 may sit in completely different leagues depending on weekly activity. Two players in the Diamond league may be Level 8 and Level 50. The systems serve different purposes — Level is your *résumé*, League is your *current standing*.

### 3.3 Achievements

Shipped via [`AchievementRegistry`](../../libraries/cards/src/commonMain/kotlin/com/cards/libraries/cards/AchievementRegistry.kt) — ~37 entries. Apple-Fitness-style flip + shimmer animation. Locked-as-mystery silhouettes.

**Rarity tiers and rewards:**

| Tier | XP | Chip reward | Cosmetic unlock |
| --- | --- | --- | --- |
| Common | 50 | — | — |
| Rare | 200 | Small | — |
| Epic | 500 | Medium | Sometimes (a frame, an emote) |
| Legendary | 2,000 | Large | Yes (a unique card back, a title) |

**Categories:** Volume, Endurance (no-bust streaks), Hand strength, Pot size, Tactical wins, Bot mastery, Difficulty progression, Stack swings, Level milestones.

**V2 additions** (when MP exists): MP mastery, tournament achievements, social achievements (play with N different friends), seasonal achievements.

### 3.4 Today's Quests

Three quests per day. Refresh at local midnight.

**Framing matters.** These are "Today's Quests," not "Daily Quests." Today's signals *here's something fun if you play today.* Daily implies obligation. Missing a day costs nothing; fresh quests appear next time you play.

#### Quests vs achievements — both, on purpose

| | **Achievement** | **Today's Quest** |
| --- | --- | --- |
| Scope | Lifetime | Today only |
| Repeat? | One-time, permanent | New ones tomorrow |
| Arc | Months/years | One session |
| Role | Pull you across weeks ("only 50 hands until Felt Veteran") | Give you a reason to play *right now* ("5 hands and I get the bonus") |
| Cost of missing | Nothing — you're never behind | Nothing — tomorrow's quests appear next time you play |

The two reinforce each other. Achievements without quests = no daily hook. Quests without achievements = no long-term arc. Marvel Snap, Duolingo, and Clash Royale all run both. We do too.

**Three slots, one of each flavor:**
1. **Effort:** "Play X hands" / "Play for X minutes"
2. **Variety:** "See 10 flops" / "Reach showdown 3 times"
3. **Skill:** "Win a hand with two pair or better" / "Win an all-in"

**Rewards:** chips (small) + XP (small) per quest. Completing all three = bonus chips + possible cosmetic shop credit.

**No streak / consecutive-day bonus.** Quests are episodic, not chained. See Appendix C for why login streaks were rejected.

Reference: Marvel Snap daily missions, *not* Duolingo daily-XP-goal. The former is opt-in via play; the latter creates daily-obligation anxiety we don't want.

### 3.5 Weekly leagues (V1.1)

**The retention engine.** Modeled directly on Duolingo's leagues.

| Property | Value |
| --- | --- |
| **Tiers** | 10 (Bronze → Royal Flush). Names finalize during build. |
| **Cohort size** | 30 players, matched by tier + play frequency + time zone |
| **Ranking metric** | **MP XP earned that week.** Not chips. Not Elo. |
| **Promotion** | Top 7 |
| **Demotion** | Bottom 5 ("you'll be back" copy, never punitive) |
| **Hold** | Middle 18 |
| **Reset** | Monday 4 AM GMT |

**Rewards per tier finish:**
- Top 3: chips (scales with tier) + tier-specific cosmetic unlock
- Top 7: chips (smaller) + promotion notification
- Middle 18: small chips
- Bottom 5: small consolation chips + "you'll be back"

**Royal Flush Tournament (apex):** Top 10 of Royal Flush tier play a 3-week bracket each week. Winners get a unique seasonal title, profile flair, and exclusive cosmetic — **unlock-only, not for sale ever**. Cinematic moment on home screen.

**Integrity provisions:**
- **Bot games don't feed league XP** (§3.7). League XP is MP-only, tracked separately via the `XpEvent` ledger.
- **Soft floor:** once you've reached Hearts tier, you can't fall below Diamonds even on consecutive bottom-5 weeks.
- **Time-zone matchmaking** prevents off-hours uncontested exploits.
- **Multi-accounting detection** via device + auth signals.

**Solo-only users:** show "Unranked" with one-time copy: *"Leagues rank multiplayer XP only. Play a hand with friends to enter the ladder."* The door is always open; never punitive.

**Confidence basis:** Duolingo's launch data showed +25% lesson completion after leagues. The mechanic pairs attainable competition (30 people) with consistent risk (demotion threat). Single most-studied gamification mechanic in 2020s consumer apps.

### 3.6 Seasons & battle pass (V1.3)

**Note: V1 ships occasional limited-time cosmetic drops without the full season framework.** Halloween card back available 2 weeks each October, Valentine's emote pack, etc. The structured **Battle Pass** ships in V1.3 only if early limited-time drops demonstrate they drive engagement. If they don't, we stay with episodic limited-time items and skip the season framework entirely. This preserves flexibility — we're not committed to LiveOps content treadmill from day one.

The V1.3 design below describes the structured season *if* we ship it.

**Season = 6 weeks.** Themed cosmetic rotation. Past-season items go to vault (visible on profile, never re-sold).

Why 6 weeks: Marvel Snap's 4 weeks generates review-bombs from casual players who can't keep up. 6 is sustainable for a small team and respectful of the casual majority.

**Two tracks:**
- **Free track** (~30 tiers) — chips, XP boosts, and **one headline cosmetic at the final tier**, accessible to everyone. Non-negotiable. The Marvel Snap rule: "the season card is for everyone."
- **Premium track** — same 30 tiers, richer rewards (full thematic cosmetic suite, season title, profile flair). **Unlock paths:**
  - Pay with chips (substantial amount, equivalent to ~$5 worth at IAP rate), OR
  - Complete one legendary achievement during the season

**Progress driven by:** XP earned during the season. Same metric as leagues — single XP source feeds both systems.

**Season-end:** all premium items remain in inventory. Catalog removed from shop. "Season X" badge on profile signals "I was there."

### 3.7 Solo / MP participation matrix

**Principle: solo earns, multiplayer ranks.** Bots feed progression systems but not competitive ones.

| System | Bot play counts? | Notes |
| --- | --- | --- |
| Level / total XP | ✅ Yes | 0.5× of MP rate (shipped) |
| Achievements | ✅ Yes | Most V1 entries bot-compatible; V2 MP-mastery entries are clearly labeled |
| Today's Quests | ✅ Yes | Engagement-flavored |
| Chip balance | ✅ Yes (via achievements + gameplay) | Common cosmetics fully accessible |
| Battle pass — free track | ✅ Yes | 0.5× tier progress. Solo players get the headline cosmetic. |
| Battle pass — premium track | ✅ Yes once unlocked | Unlock via chips or a bot-achievable legendary during the season |
| Weekly leagues | ❌ No | MP-only. Solo shows "Unranked." |
| Royal Flush Tournament | ❌ No | Apex MP competition. |
| Exclusive-tier cosmetic unlocks | Mixed | Achievement-anchored unlocks: yes if achievement is bot-achievable. League/RFT/MP-anchored unlocks: no. |

**Mental model:** Bots are the practice room. MP is the arena. The practice room is real and respected. The arena is where status is earned.

---

## 4. Economy

### 4.1 Chips — the only currency

Cards uses a **single-currency** model. There is one wallet, one set of earn paths, one set of spend paths. Chips. (Two-currency designs were considered and rejected — see Appendix C.)

**Starting grant:** 10,000 chips. **One per device fingerprint**, not one per account — closes the "uninstall → reinstall → fresh chips" exploit. See §6.1.

**Earn paths:**
- Game wins (gameplay)
- Achievement rewards (Rare / Epic / Legendary tiers)
- Today's Quest rewards (200–500/quest + ~1,000 all-three-complete bonus)
- League placement rewards (top 3 large, top 7 medium, middle/bottom small)
- Battle pass tier rewards (chips appear at most free-track tiers)
- **First-week welcome chips:** 500/day for the first 7 days, no streak required, no expiry. A friendly hand to new users — generous, on-brand, no obligation.
- **Soft bust protection:** when a user hits 0 chips, they receive a "second wind" of 1,000 chips with no timer and no urgency. Copy: *"Welcome back to the table."* Cards is play-money — chip economics aren't real, and the new-user-bouncing problem is solved by being generous, not by manufacturing scarcity. (Anti-pattern explicitly rejected: FanDuel-style timed chip bonuses. See [Appendix C.5](#c5-expiring-chip-bonuses--wheel-spin-rewards-rejected-2026-05-16).)

**Spend paths:**
- Table buy-ins (gameplay)
- Tournament entries (V2+)
- Shop cosmetics — common tier (see §4.3)
- Battle pass premium unlock (see §3.6)
- "Tip the dealer" (50–500 — pure flavor chip sink at hand end)

**Critical rule:** chips never disappear unless the user voluntarily spends or loses them. No expiration, no "inactive — here's a small bonus to come back" hook. The Offsuit principle: chips feel sacred.

### 4.2 The unlock-only catalog

Some cosmetics are **never for sale.** Not for chips. Not for real money. They're earned by doing the thing they represent. This is how prestige works in Cards.

**Unlock-only items:**

| Item type | Earned by |
| --- | --- |
| Legendary achievement cosmetics | Completing the achievement |
| League tier cosmetics | Finishing top 3 in that tier |
| Royal Flush Tournament cosmetics | Winning RFT (titles, profile flair) |
| Season premium cosmetics | Battle pass premium track during that season |
| Achievement-chain cosmetics | Completing a category of achievements |

These items are explicitly **never** in the shop. Whales can buy chips, and chips buy plenty in the shop — but they cannot buy the items that signal accomplishment. **The line is bright and uncrossable.**

This is the structural anti-P2W mechanism: the items players see on rival profiles and recognize as prestige cannot be bought with money. The shop catalog and the unlock catalog are disjoint.

### 4.3 Shop

**Categories** (all chip-priced):

1. **Card backs** — workhorse. Common variants (500–2,000), seasonal variants (chip-priced but time-limited)
2. **Table felts** — visible to whole table, high social signal
3. **Emote packs** — bundled sets of ~6 themed reactions
4. **Avatar frames** — static frames; animated frames are unlock-only (§4.2)
5. **Name color / glow** — display name flair (a few simple options chip-priced; exotic variants unlock-only)
6. **Profile decoration** — limited chip options; rare ones unlock-only

**Structure:**
- **Featured** — current season chip-priced items, rotates weekly
- **Browse** — full catalog, filter by category, owned indicators
- **Vault** — past-season items shown on profiles but never re-listed
- **Trophy case** — unlock-only inventory display (not buyable; visible only)

**No random loot boxes.** Every item shows price + identity before purchase. Sidesteps app-store odds-disclosure entirely.

### 4.4 IAP & payment processors

**V1.5: native StoreKit + Google Play Billing only.** Three chip packs:
- $1.99 = 5,000 chips ("Starter")
- $9.99 = 30,000 chips ("Player")
- $49.99 = 200,000 chips ("Whale")

No subscriptions. No rewarded ads. No NFTs. No crypto.

**Why native first (not third-party billing):**

| Path | Effective fee (2026) | Cost to implement |
| --- | --- | --- |
| Apple IAP (US) | 30% (15% via Small Business Program ≤$1M/yr) | 1–2 weeks |
| Apple external link (US) | ~0% pending district court ruling on "reasonable" rate | 2–3 months + ongoing compliance |
| Google Play Billing (US, post-June 2026) | 25% (20% service + 5% billing) | 1–2 weeks |
| Google external billing (US, post-June 2026) | 20% service only | 2–3 months + ongoing compliance |

External billing also incurs a 15–30% conversion drop (RevenueCat data) — users distrust web checkouts, lose Face ID / autofill. And the legal landscape is still moving; the iOS 0% window could close when the district court sets a "reasonable" rate.

**Real-world adoption signal:** Marvel Snap, Duolingo, Clash Royale all eat the platform fee. Spotify/Kindle/Patreon are the named exceptions. The math doesn't favor external billing pre-scale.

**Revisit trigger:** annualized IAP revenue clears **$100K/year**. At that point, RevenueCat-mediated external billing on Android first (saves 5% per transaction without re-platforming). iOS waits for the district court's "reasonable commission" ruling before committing.

### 4.5 No pay-to-win — the hard rule

**Chips never buy gameplay advantage. Period.** This is a brand pillar, not a guideline.

| Feature | Allowed? | Where? |
| --- | --- | --- |
| Live equity display ("you have 64% to win") in **MP** (Friend Games, Public Rooms, Leagues, Tournaments) | ❌ Never | Removes skill in competitive contexts |
| Live equity display in **solo bot games** | ✅ Yes | Free settings toggle. Practice context. Offsuit ships this. |
| Post-hand equity replay (hand history viewer, Phase 10) | ✅ Yes | Educational, not real-time |
| Opponent style display (heat map) | ✅ Yes, but earned | Visible only after 50+ shared hands with that opponent |
| Hand strength analyzer in MP | ❌ Never | Same reason as live equity |
| "See one opponent's card" power-up | ❌ Never | P2W by design |
| "Re-deal this hand" | ❌ Never | Breaks the game |
| "Extra time on this decision" | ❌ Never | Turn timer must be neutral |
| Cosmetic items (card backs, frames, emotes) | ✅ Yes | Chips, freely. Never affects gameplay. |

**The principle:** any feature that gives information or capability that affects in-the-moment decision-making in MP is gameplay advantage. Anything purely aesthetic, anything in solo-only context, and anything strictly post-hand is fair game.

---

## 5. Multiplayer

### 5.1 Three states of an empty seat

Every empty seat in any room is in one of three states. This is the unifying model for multiplayer.

| State | Who takes it | Default for |
| --- | --- | --- |
| **Bot** | System-filled with a chosen personality | Backfill in any game |
| **Friend (private)** | Anyone with the 6-char code or deep link | "Friend Game" |
| **Public** | Any stranger via Quick Match or Browse | "Open Game" |

Game creation surfaces one binary up front: **Friend Game** or **Open Game**. Empty seats can be flipped between states mid-game (host control).

### 5.2 Friend Games

- 6-character alphanumeric codes (avoid lookalikes 0/O/I/1)
- Deep links via universal links (iOS) / app links (Android)
- Code expires after the game ends; not reused for 24h
- Create-a-game flow: 3 taps max (stakes, table size, "go")
- Share sheet: copy code, system share sheet (no app-specific share targets)
- Link previews on iMessage/WhatsApp show Cards-branded card with stakes and current seat count

### 5.3 Public rooms

**Quick Match (primary):** one tap. Server places user in an open seat at a stake tier matching chip balance + skill. Marvel Snap–style choice minimization. Quick Match prefers all-human tables. When the matchmaking pool can't fill one, it falls back to a bot-filled table labeled visibly **"Practice tier · bots present"** — never silently. We do not invent synthetic human personas to fill rooms.

**Browse (secondary):** filterable list of currently-open public rooms. Stake tier, seats taken, ante level, average wait. For power users.

**No pre-game lobby chat.** Strangers don't chat before sitting. Chat starts when seated.

**Stake tiers (matchmaking buckets):**

| Tier | Buy-in | Audience |
| --- | --- | --- |
| Practice | 100 chips | First MP sessions |
| Casual | 500 chips | Default tier |
| Standard | 2,500 chips | Bulk of engaged players |
| High | 10,000 chips | Higher-skill willing-to-risk |
| Premium | 50,000+ chips | Top end, gated by min balance |

**Anti-smurf:** can't enter a tier with buy-in > 25% of chip balance. Prevents whales sandbagging in Practice.

**Anonymous users in public rooms:**
- Can **join** public rooms — play, earn league credit, all of it
- Cannot **host** public rooms — hosting requires claimed account (Apple/Google) for moderation accountability
- Subject to device-level moderation: repeated reports on a device fingerprint trigger shadow-ban that survives re-installs

**Anti-friend matchmaking:** Quick Match avoids placing known friends in the same public room. Forces friends to use Friend Games (which is what friends want anyway). Friends ending up in the same public room via Browse flags the game for integrity attention (§7).

### 5.4 Bot fill

**Rule:** Bot fill is free, unlimited, one-tap — but the room must have **at least as many humans as bots, AND at least 2 humans** to count as multiplayer for XP / league / achievement purposes.

- No chip cost, no level gate. Bot fill is friction-reduction, not monetization.
- Bot personality matches difficulty selected (Casual / Standard / Challenging — same as solo)
- No quality penalty for short-handed tables — short-handed poker is a legitimate format
- Bots are always visibly labeled as bots at the table. We never disguise bots as humans.

#### MP credit by table composition

| Composition | MP credit? | Notes |
| --- | --- | --- |
| 4H + 0B | ✅ Full | Full 6-handed human game (or 4-handed) |
| 3H + 3B | ✅ Full | 50/50 boundary — counts |
| 2H + 2B | ✅ Full | Short-handed with parity |
| 2H + 4B | ❌ Solo-only credit | Bots outnumber humans — exploit vector ("2 friends + 4 bots = chip farm") |
| 1H + Xb | ❌ Routed to solo flow | One human is solo mode regardless of bots |

**Why majority human:** without this rule, 2 friends could create a 6-seat game with 4 bots and farm chip wins + league XP against weaker bot opposition. The rule preserves the "leagues reflect competition against humans" principle (§3.5) without breaking short-handed friend games (2H+2B = fine).

### 5.5 Table-side social

**Emoji blasts (V1):**
- Pool of ~12 base (🔥 🎉 😱 🤡 💀 👀 🥶 🤯 💸 🙏 😎 🥲)
- One-tap from bottom tray
- Full-screen ~1.5s animation
- 8-second cooldown per user
- "Mute this player's emoji" available from tap-avatar surface
- Additional themed packs unlockable via shop

**Reactive emoji (auto-fired by the game, V1):**
- 🤯 on a >50BB pot
- 🥶 on a 2-outer river beat
- 🎉 on first hand-win of session
- 💀 on bust
- Toggleable in settings

**Ephemeral in-game chat (V1.x, *not* V1):**

Cut from V1 scope. Reasoning: friend games already have external chat channels (iMessage, WhatsApp, Discord) — in-app chat is redundant for friend cases. Public rooms with stranger chat are the most moderation-heavy surface for the least social value. Tables in V1 are silent except for emoji blasts and gameplay sounds. Chat ships in V1.x once moderation infrastructure matures.

When chat ships in V1.x:
- Room-scoped. Dies when game ends. Never persisted.
- Pre-canned phrases first (~12: "nh", "gg", "ouch", "I had it", "good fold", etc.)
- Free text behind a per-user toggle with profanity filter + rate limit (3 msgs/min)
- Mute-table-chat always available

**Audio voice reactions:** V2+. Skip until we know we want the moderation overhead.

### 5.6 Reconnect handling

- Human disconnects mid-hand → seat plays out the current hand with a stand-in bot using their current stack and a passive personality. Mandate: *preserve the stack*, not *play to win*.
- Reconnects within 5 min → resume with whatever stack the bot left them
- Beyond timeout → host-setting determines whether the seat formally converts to a bot continuation or vacates (room's "drop policy" toggle, defaulted to "continue as bot" for friend games)

This is not a "play for me on purpose" feature. It's "life happens, the game continues."

---

## 6. Identity & social

### 6.1 Anonymous-by-default

Per [decisions.md 2026-05-14](../decisions.md). Users can play **everything** — bots, friend games, public rooms, leagues, achievements, shop — without an account.

**Claim account** (Apple / Google) unlocks: cross-device sync, public leaderboard visibility, friend list, anti-cheat eligibility, ability to host public rooms.

**Auto-assigned identity at first launch** (per §2.1): generated display name + base avatar. Both editable any time. Never gate gameplay behind identity setup.

#### Anonymous state persistence

Anonymous identity is **server-side**, keyed to a UUID generated at first launch. On uninstall, the local UUID is wiped (iOS wipes app data on uninstall; Android usually does). So "stored UUID" alone doesn't survive a reinstall — we need additional signals to recover the account.

#### Best-effort account revival on reinstall

We make every reasonable attempt to recover an anonymous user's state when they come back. Signal hierarchy, in order of preference:

1. **App-stored UUID** — survives app updates and OS upgrades, *not* uninstall. Primary mechanism for continuity, not recovery.
2. **Server-side device fingerprint** — the primary mechanism for *recovery* on reinstall. On every launch, the app reports a fingerprint (device class, OS version, locale, network indicators); the server checks for a matching anonymous account from the last 6 months. Match = offer to restore. Same mechanism powers anti-farming on the starter grant (§4.1) — one unified system.
3. **Platform-level backup** — iCloud Keychain (iOS), Block Store (Android). We write the anonymous account credential to platform backup on first launch and on every claim-prompt dismissal, so even users who refuse to sign in get cross-device protection *if they have platform backup enabled*. Restores automatically across device migration.
4. **Sign in with Apple / Google** — the only mechanism that works in 100% of cases. The case we make to anonymous users at claim-prompt moments (§5.11 voice-and-copy): *"this is the durable way; the others are best-effort."*

**Recovery flow on reinstall:**

1. App launches with no local UUID
2. Reports fingerprint to server, checks platform keychain in parallel
3. If either matches an account in the last 6 months → *"Welcome back. We found your account: `QuietAce72` · Level 12 · 8,200 chips."* `[Continue] [Start fresh]`
4. If neither matches → treat as new user, auto-identity + starter chips (subject to fingerprint anti-farming check)

**What this gets us:** the realistic outcome for the common case (anonymous user uninstalls, reinstalls weeks later on the same device) is **near-certain recovery** via fingerprint. Device-switch is recovered if platform backup is enabled. Only true wipe-and-no-backup loses state — and that's the case where we'd been prompting them to claim all along.

**Loss vectors that remain:**
- True device wipe with no platform backup enabled
- Device switch with no platform backup enabled
- User explicitly chooses "Start fresh" on the welcome-back prompt
- More than 6 months between sessions (recovery window expires)

The risk we accept: a small percentage of anonymous users will lose progress in edge cases. The mitigation is smart prompting toward claim (§5.11) at moments where their stake is meaningful, not forcing claim up front.

#### Privacy & transparency

Device fingerprinting is privacy-sensitive. We use only the minimum signals necessary for account recovery and anti-farming (device class, OS version, language, region, network indicators) — **not** user identifiers, advertising IDs, or persistent hardware IDs that would survive a factory reset. Data is used solely for account-matching, never sold, never shared with third parties. Surfaced in the privacy section of settings (§5.12 voice-and-copy).

#### Smart claim prompts (not gating)

We never block gameplay behind sign-in. Instead, we surface the claim option at moments when the user has something worth saving. Each prompt fires **once, ever, dismissible**. No begging, no "ARE YOU SURE" friction.

| Trigger moment | Why it matters |
| --- | --- |
| First MP hand won | First time chips have real provenance (earned from a human, not bots) |
| First Epic or Legendary achievement | First "this took effort to get" moment |
| Chip balance crosses 5,000 | Threshold where loss would feel meaningful |
| First shop interaction | About to spend chips on something durable (a cosmetic survives if claimed) |
| Reaching Level 10 | Milestone moment — user has invested time |

Specific copy in [voice-and-copy.md §5.11](./voice-and-copy.md#511-smart-claim-prompts-anonymous-users).

#### Anti-farming on the starter grant

The 10,000-chip starter (§4.1) is **one-per-device-fingerprint**, not one-per-anonymous-account. Phase 4 logging captures device fingerprint (§7.2); the starter-grant table checks fingerprint before granting. Reinstall on the same device = no fresh starter. Closes the obvious "uninstall to reset chips" exploit without affecting honest users.

Edge case: a user legitimately on a wiped/new device gets a fresh starter (their fingerprint changed). This is fine — true device-switch is rare and indistinguishable from "new user" anyway.

### 6.2 Profile fields

**V1.5:**
- Avatar (gallery ~24, auto-assigned)
- Display name (auto-generated, editable)
- Level + level badge
- Member-since date
- Hours played, hands played
- Top-3 pinned achievements
- Current league tier (or "Unranked")
- Active title (earned, equipped)
- Season-badge wall (seasons participated in)

**Phase 10 additions:**
- Play-style heat map (§6.4)
- Hand history viewer (last 50 hands, replayable)
- "Best hand ever" (auto-pinned royal flush / quads / etc.)
- Bio (optional, pre-filled tags or free text)

**Privacy:** Public / Friends-only / Private. Defaults: Friends-only for claimed accounts; Private for anonymous.

### 6.3 Friends

**Two layers of social graph:**

1. **Recently-played-with** — auto-generated. Last 20 people you've shared a table with (claimed accounts only). One-tap "invite to game." The lower-friction funnel.
2. **Friends** — explicit, two-sided. Friend request, accept/decline. Friends visible at top of home ("3 friends online"). Friends see your full profile; non-friends see only public fields. Block/mute/unfriend lives here.

**No clans/clubs in V1.5.** V2+ feature. Pokerrrr 2's 2,000-member club pattern is overkill and creates moderation hell.

### 6.4 Play-style heat map (Phase 10)

The killer differentiator. Reduces VPIP/PFR to a 2D coordinate.

- **X:** tightness (VPIP — how often voluntarily put money in pot)
- **Y:** aggression (PFR/VPIP ratio)
- **Quadrant labels:** Tight passive (rocks), Tight aggressive (TAGs), Loose passive (calling stations), Loose aggressive (LAGs/maniacs)
- **Visibility:** your own profile, and opponents' profiles **only after 50+ shared hands** (the "you've earned the read" rule)

Why this works: it's the only poker-app feature that gives genuine self-knowledge without being a coaching tool. Reducing two numbers to a single 2D coordinate is vastly more legible than VPIP + PFR as numbers. No phone competitor has this.

Phase 10 (post-V1) because it requires accumulated hand data to pay off.

---

## 7. Integrity & moderation

Once strangers and prestige unlocks exist, integrity becomes a real concern. We document the threat model deliberately — not because we have a finished plan, but because the model must be visible before leagues ship.

### 7.1 Threat model

| Threat | Description | Impact | V1.5 detection difficulty |
| --- | --- | --- | --- |
| **Collusion** | Multiple humans soft-playing each other | High | Medium (pattern detection) |
| **Multi-accounting** | One person, multiple accounts at the same table | High | Medium (device + IP + behavioral) |
| **Chip dumping** | Intentional losses to transfer chips | Medium | Medium (anomaly detection) |
| **Bot-as-human** | Bot running in a human account | Medium | Hard (timing + decision distribution) |
| **RTA (real-time assistants)** | Solver in a second window | Low (play money) | Effectively impossible server-side |

### 7.2 V1.5 approach

Sized for an indie team. Honest about the floor.

1. **Phase 4 — log everything.** Server records every action, every timestamp, every device + IP + auth signal, network signals, all emoji emissions. This is the data layer.
2. **Phase 5 — community reporting + per-user blocking.** Report-player button → server-side queue. Block-user (per-user, instant, mutual). **No automatic bans in V1.** Reports go to manual human review.
3. **Phase 5 — passive risk signals.** IP/device clustering (3+ accounts on a fingerprint = flagged). Co-occurrence (A and B at the same table >X% sessions, not friends = flagged). Anti-friend matchmaking already gates Quick Match co-tabling.
4. **Phase 7 — pre-league integrity sweep.** Manual sample-review of top 100 finishers across all tiers before first league rewards land.

#### Why no auto-bans in V1

A "3 reports = auto-ban" trigger is exploitable: a coordinated raid can permanently ban an honest user, generating 1-star reviews and Twitter threads we can't easily roll back. For a play-money app, false-positive cost is much higher than slow-positive cost.

**V1 policy:** 3+ reports on an account flags it for **priority human review** (the reviewer sees the reports plus recent activity logs from the Phase 4 event store). The reviewer decides: warning, temporary suspension, permanent ban, or no action. Automated bans wait for V2+ when volume forces them and ML detection (§7.3) provides better signal.

#### Moderation API contract (engineering reference)

For implementer reference, the response patterns when a banned / restricted user makes requests:

| State | HTTP | Body |
| --- | --- | --- |
| Active, no issues | 200 | normal response |
| Permanently banned | **403 Forbidden** | `{"reason": "banned", "until": null, "appeal_url": "..."}` |
| Temporarily suspended | **403 Forbidden** | `{"reason": "suspended", "until": "ISO-8601", "appeal_url": "..."}` |
| Shadow-banned | **200 OK** | normal response; server silently drops outgoing emoji / future-chat from broadcasting to other clients |
| Rate-limited | **429 Too Many Requests** | `{"retry_after_seconds": 60}` |

`423 Locked` is for WebDAV resource locking, not auth state — use `403` with a typed reason field. Frontend dispatches the right UI off the `reason` value.

#### Block vs Report — what each does

| Action | Effect on reporter | Effect on target |
| --- | --- | --- |
| **Block** | Target no longer appears in reporter's tables (public or invited). Target's emoji is hidden. | Target sees nothing — silent on their end. No notification. |
| **Report** | Submits to review queue. Reporter sees one-time "Thanks, we'll review" toast. | No immediate effect. Target is *not* notified. Account is flagged in the data layer with the report reason + timestamp. |

Block is for personal comfort (one-tap, immediate, reversible from settings). Report is for community policing (slower, human-reviewed, persistent record).

### 7.3 V2+ deferred

- ML-based collusion detection trained on accumulated data
- Automated bans with appeals flow
- Behavioral biometrics for bot-as-human detection
- Dedicated trust & safety surface

### 7.4 What we can't prevent (be honest)

- **RTA** — short of hostile screen-recording detection (privacy-invasive, easy to circumvent), there's no real defense. Play-money status helps.
- **Sophisticated multi-person collusion via VOIP** — undetectable without behavioral red flags. Mitigation: random matchmaking, anti-friend logic, victim reporting.

**Honesty principle:** we don't claim "AI-powered anti-cheat" in marketing until we actually have it. Overclaiming is a worse failure than under-claiming. "We log everything and humans review reports" is defensible and credible.

---

## 8. Notifications

**Event-driven, never behavior-modeled.** Notifications fire on real events (league boundary, friend joined a table, achievement unlocked) — never on a model of "when this user usually plays."

**Default categories (opt-in granularity):**
- League: promotion threshold reached, demotion risk, weekly reset coming
- Friend: friend joined a table, friend earned rare/legendary achievement
- Battle pass: tier unlocked, season ending
- Achievement: rare or legendary unlock

**Explicitly never:**
- Streak notifications (no streak mechanic — Appendix C)
- "Your quests expire in 1 hour" reminders (quests are episodic, not chained)
- Time-of-day pings based on usual play time (stalker-coded)
- "Come back, we miss you" / "Your chips are getting lonely" / any psychological-pressure copy
- More than 2 notifications per day, ever

---

## 9. Roadmap

### 9.1 Phases

Phases 1 (game engine) and 2 (defensive infra) are done. V1 progression UX is shipped.

| Phase | Title | Ships | V1? |
| --- | --- | --- | --- |
| **3** | Auth & server persistence | Anonymous-by-default Supabase sign-in, Apple/Google claim, account deletion, server-side XP/chip persistence | ✅ |
| **4** | Multiplayer foundation | Server-authoritative dealer, Friend Games (room codes + deep links + bot fill + reconnect) | ✅ |
| **5** | Public rooms + table-side social + moderation | **Public rooms + Quick Match + Browse + stake tiers.** Emoji blasts + reactive emoji, tap-avatar preview. Block / report + manual human review (no auto-bans). Chat deferred to V1.x. | ✅ |
| **6** | Quests + notifications | Today's Quests (3/day), event-driven push notifications | ✅ |
| **7** | Weekly leagues | 10 tiers, 30-player cohorts, top-7/mid-18/bottom-5, Monday reset, Royal Flush Tournament | V1.1 |
| **8** | Shop + chip IAP | Catalog by category, three chip packs, common cosmetics + unlock-only trophy case | ✅ |
| **9** | Seasonal battle pass | 6-week seasons, free + premium tracks, themed rotation, vault mechanic | V1.3 |
| **10** | Profile depth | Play-style heat map, hand history viewer, session recap, bio + pinned achievements | V1.2 |
| **11+** | V2 expansion | Tournaments (sit-and-go first), additional games (Blackjack first), clans, spectator mode, ML integrity, ban appeals | V2 |

**Dependency map:**

```
Phase 3 (auth)
   ↓
Phase 4 (MP) ─┬─► Phase 5 (table-social + public rooms)
              │
              └─► Phase 6 (quests + notifications) ─► Phase 7 (leagues) ─┬─► Phase 8 (shop + IAP)
                                                                          │
                                                                          └─► Phase 9 (seasons)

Phase 10 (profile depth) — orthogonal, lands after Phase 4.
Phase 11+ — gated on retention data from 7/8/9.
```

**Big-commit phases requiring scope confirmation before kickoff:** 4, 7, 9.

### 9.2 Module map

**Existing modules (extended):**
- [`libraries/cards/`](../../libraries/cards) — domain. Add `DailyQuestRepository`, `LeagueRepository`, `SeasonRepository`.
- [`libraries/cards/impl/`](../../libraries/cards/impl) — corresponding impls
- [`libraries/cards/storage/`](../../libraries/cards/storage) — new Room entities per phase, following `XpEventEntity` append-only-ledger pattern
- [`features/progression/`](../../features/progression) — XP / Rank / Achievements pages. Expand for league surfaces.
- [`features/profile/impl/`](../../features/profile/impl) — Phase 10 expansion for heat map + history
- [`features/home/impl/HomeScreen.kt`](../../features/home/impl/src/commonMain/kotlin/com/cards/features/home/impl/HomeScreen.kt) — league standing card, Today's Quests tray
- [`features/shop/impl/`](../../features/shop/impl) — currently placeholder; built out in Phase 8
- [`features/room/impl/`](../../features/room/impl) — Phase 4 MP work + Phase 5 table-social extension
- `:server` — Phase 4 work begins; subsequent phases add league snapshot job, season config

**New modules (in order):**
- `features/dailies/impl` — Phase 6
- `features/leagues/impl` — Phase 7
- `features/season/impl` — Phase 9

---

## 10. Brand checks

Run any user-facing feature past these before merging.

- [ ] Does this feature respect the user's time, or manufacture urgency?
- [ ] Is the copy confident and dry, or is it begging?
- [ ] Does this feature create a moment friends can share, or is it solo?
- [ ] Is there a popup we could avoid?
- [ ] Could a Zynga Poker designer have shipped this? If yes, redesign.
- [ ] Could an Offsuit designer have shipped this? If yes, does this game-y app need more celebration here?
- [ ] Could a Duolingo designer have shipped this without thinking? If yes, does daily-obligation make sense for episodic entertainment? (Some Duolingo patterns transfer — XP, leagues. Some don't — login streaks, "usual study time" pings.)
- [ ] If monetization — would the user feel respected or hustled?
- [ ] Does this feature get more interesting at level 50 than at level 5? (If not, it's a tutorial feature, not a system feature.)
- [ ] Does this work for anonymous users? Should it?
- [ ] Does this work for a pure-solo (bot-only) player? If not by design, is the empty state neutral?
- [ ] Does this survive a hostile actor — griefer, collusion, throwaway account?
- [ ] Does this feature affect gameplay decision-making in MP? If yes, it cannot be in the shop. (§4.5)

---

## Appendix A — Lineage

Catalog of what we take from each comparable app, and what we explicitly reject. Used as a benchmark for future product decisions: *"Is this a Card Hall thing or a Zynga thing?"*

| App | What we take | What we reject |
| --- | --- | --- |
| **Offsuit** | Anonymous-by-default. Aesthetic restraint. "Chips feel sacred." Bot quality bar. Solo equity display in practice mode. | Solo-first design. Async-aggregate tournaments. |
| **Pokerrrr 2** | Room codes + friend invites. Custom-buy-in private games. | Espionage theme. 4-hour table expirations. Gesture-based card peeking. Single currency / cosmetic confusion. 2,000-member clubs. |
| **Marvel Snap / Ben Brode** | "Slow collection" pacing. Free-track headline cosmetic for everyone. Daily missions (opt-in via play, not daily obligation). "Rough edges" philosophy. Cosmetic-driven progression. | Loot-box-style packs (Caches generate FTC scrutiny). 4-week season cadence. Card power-level upgrades. Two-currency design. |
| **Duolingo** | Weekly leagues, 30-player cohorts, 10 tiers, top-7/mid-18/bottom-5. Diamond Tournament (→ Royal Flush Tournament). XP as common currency across systems. "You'll be back" demotion copy. Test-everything culture. | Login streaks with daily-obligation framing. "Your usual study time" notifications. Hearts/lives energy gates. |
| **Clash Royale** | (Nothing structural. Lineage included for honest "considered and rejected" record.) | Two-currency framework. Wait-timer monetization (chests). Gem-buys-Gold conversion. Gold-scarcity-as-monetization. Random chest rewards. |
| **Zynga Poker / WSOP** | Tournament-as-event framing (V2 only). | Everything else: visual language, popups, IAP pressure, daily wheel, daily login bonus, leaderboard taunting, "your chips are running low" copy, login streaks. |

---

## Appendix B — Open decisions

Deferred questions to be answered when their phase comes up:

1. **Season length** — working assumption 6 weeks. Validate during Phase 9.
2. **League XP soft floor for high-level players** — likely "can't drop more than 2 tiers regardless of inactivity." Finalize Phase 7.
3. **Friends list capacity** — probably 200 hard cap, no warning until 150. Validate in Phase 5.
4. **Profanity filter for V1.x free-text chat** — allow-list first, then filter (Discord pattern). Phase 5+.
5. **App store compliance** — 17+ rating, geo-restriction in regulated markets (Korea, China). Confirm with Apple's reviewer guidance before Phase 8 submission.
6. **Quiet Felt Mode toggle** — option to hide leagues/quests/seasons for power users. Settings toggle in Phase 10.
7. **External billing migration trigger** — native StoreKit / Play Billing in V1.5. Revisit at $100K/yr annualized IAP. RevenueCat-mediated on Android first.
8. **Display-name uniqueness** — enforce on user-edited names; allow collisions on auto-generated with suffix counter for table display. Phase 3.
9. **Display-name word list curation** — poker-flavored, brand-safe. Block-list for offensive combinations. Phase 3.
10. **Bot-fill minimum-humans edge cases** — what happens when a 3-human game collapses to 1 via disconnects? Default: pause 2 min, then auto-end + final stacks paid out. Finalize Phase 4.
11. **Bot personality in mixed tables** — fixed slots or rotation? Default: difficulty-tier roster (same as solo). Phase 4.
12. **Public room stake tier calibration** — current numbers (Practice 100, Casual 500, Standard 2.5K, High 10K, Premium 50K+) are starters. Calibrate against chip economy modeling in Phase 8.
13. **Anti-smurf percentage** — 25% threshold is starter. Validate against Phase 5 telemetry.
14. **Integrity ML investment trigger** — define quantitative + qualitative thresholds before Phase 9 ships.
15. **Anonymous-host policy validation** — revisit if "create a public game" is top friction point in Phase 5 telemetry. Fallback: chip-balance floor for anonymous hosts.
16. **Co-occurrence threshold for integrity flags** — pick X% based on Phase 5 data.
17. **Weekly play streak as V1.x flex stat** — consecutive weeks with at least one MP hand, profile-only display, no notifications/freezes. Reconsider once leagues mature.
18. **League cohort sizing at low scale.** 30-player cohorts assume volume we won't have at V1.1 launch. Default to `cohort_size = min(30, active_in_tier / 2)` — grows as the user base grows. No synthetic users in leagues; small honest cohorts are preferable to fake ones. Validate in Phase 7.
19. **Live activity ticker content sourcing.** §2.4 home screen calls for a rotating ticker ("X just promoted to Diamond" etc.). Need to define the event types, throttling rules (no spamming one user's feed with one other user's wins), and freshness (events from the last 24h only). Detail in Phase 5.
20. **Bot name localization for V1.x / V2.** Current bot names (Steve, Jane, David, Gina, Mike) are Western — fine for V1 English-market launch, but jarring for JP / KR / Spanish users when we localize. Decision when V1.x ships: either (a) per-locale name table (`bots/names/{locale}.json`) maintaining culturally appropriate equivalents, or (b) switch to localized titles ("The Rock," "The Maniac," "The Calling Station") that translate naturally. Personalities stay constant either way; only the surface label changes.
21. **Auto-ban threshold for V1.x / V2.** V1 ships with manual review only (§7.2). When report volume outgrows the manual queue, we'll need an automated suspension threshold. Open: report count (3? 5? weighted by reporter reputation?), suspension duration (24h escalating?), appeal SLA. Detail when V2 trust & safety surface ships.
22. **First-week welcome chips delivery mechanism.** §4.1 commits to 500 chips/day for first 7 days. Open: does the user need to open the app to claim it (push-notification triggered "Day 3 bonus available") or does it just appear in their balance silently when they next play? Recommendation: silent. No notification, no claim-screen — chips simply present in the wallet. Avoids the Zynga "daily reward!" pattern.

*Voice & copy guide artifact* — formerly open decision #19, now resolved: see [voice-and-copy.md](./voice-and-copy.md).

---

## Appendix C — Removed mechanics

Decisions we considered and rejected. Brief rationale here; full context in [decisions.md](../decisions.md).

### C.1 Daily login streak (rejected 2026-05-16)

Originally planned (Phase 6) as a Duolingo-pattern daily-XP streak with freeze and XP multiplier.

**Why rejected:**
- **Use-case mismatch.** Poker is episodic / weekly-social entertainment, not daily-practice content. Forcing daily-play obligation creates anxiety on a "play when you want" activity.
- **Brand contradictions.** Streaks fail three brand checks (manufacture urgency, "about to break" copy is begging, streak-freeze-via-chips feels hustled).
- **Comparable entertainment apps don't ship login streaks.** Marvel Snap, Clash Royale, Hearthstone don't. Login streaks are a daily-conceptual-product pattern (Duolingo, Snapchat, BeReal).
- **Leagues already provide weekly urgency.** Real cadence, real urgency.

**What replaces it:** nothing in V1. V1 retention: Today's Quests + achievement progression + cosmetic anticipation + friend pings. V1.1 adds weekly leagues, which carry the heavy retention work.

**Open option:** a low-pressure **"weekly play streak"** (consecutive weeks with at least one MP hand, profile flex stat only, no notifications, no freezes) is on the table for V1.x. Listed in Appendix B item 17.

### C.2 Two-currency economy with Gems (rejected 2026-05-16)

Originally specced as Chips + Gems, with Chips IAP-able and Gems earned-only. Battle pass premium unlocked via 1,500 Gems OR a legendary achievement.

**Why rejected:**
- **Complexity without proportional benefit.** Two wallets, two earn rates, two spend menus — for a UX gain that doesn't materialize. Marvel Snap users complain about Gold/Credits confusion; we'd inherit that.
- **The prestige function works better without a second currency.** Real prestige is the *item*, not the currency that bought it. Marvel Snap's Variants are prestigious because they're rare, not because they cost Gold.
- **The anti-P2W argument was weaker than it looked.** Whales bought chips, chips bought most cosmetics — the "earned-only" tier was a thin distinction. The cleaner anti-P2W story is the **unlock-only catalog** (§4.2), where prestige cosmetics are never in the shop, regardless of currency.
- **One-currency matches user mental models.** Everyone understands poker chips. A second currency requires tutorialization.
- **Genre precedent.** Pokerrrr 2 (one currency), Offsuit (one currency), Zynga (one currency). Two-currency is CCG-pattern, not poker-pattern.

**What replaces it:** §4.1 single-chip economy + §4.2 unlock-only prestige tier. Prestige cosmetics are unlocked by doing the thing (winning RFT, finishing top 3 in a league tier, completing a legendary achievement, completing a season's premium battle pass). They are **never** in the shop.

### C.3 Bot-as-your-proxy with fractional wins (rejected 2026-05-15)

Considered allowing a bot to play as an "extension of yourself," with its wins partially crediting you.

**Why rejected:** institutionalized ghosting / multi-accounting (banned on every real poker platform). Adversarial gameplay has no clean answer for "your bot beat you in a hand." Hard to explain to new users. The kernel that survives is **reconnect-as-bot** (§5.6) — a different, narrower mechanism for "life happens mid-game."

### C.4 Cost-gated or level-gated bot fill (rejected 2026-05-15)

Considered charging chips/gems or requiring a level threshold to add bot fill in MP rooms.

**Why rejected:** punishes new users (most likely to need bot fill, least likely to have chips/level). Nickel-and-dimes the peak social moment ("we want to play *right now*"). Voice violation per §1.4. Bot fill is friction-reduction, not monetization.

### C.5 Expiring chip bonuses & wheel-spin rewards (rejected 2026-05-16)

Considered FanDuel-style timed chip bonuses ("use these 500 chips in 15 minutes!") and Zynga-style wheel-spin random rewards.

**Why rejected:**

- **Wheel spin** is the most casino-coded UX pattern in mobile gaming — a slot machine wrapped in a "free reward" frame. The dopamine loop is identical to pulling a lever in a casino. The reason gambling apps ship it isn't because it's good UX; it's because they optimize for slot-machine engagement. It's exactly the trap our brand promise says we don't fall into.
- **Expiring chip bonuses** are manufactured urgency on a *play-money* currency. They pressure users into playing when they don't want to. They fail three brand checks: manufacture urgency, copy is begging, the user feels hustled.
- Both are FanDuel / Zynga patterns optimized for a behavior (impulsive gambling) we don't want to teach our users.

**What we ship instead** (addresses the same underlying retention goal):

- **Soft bust protection** — when a user hits 0 chips, "second wind" of 1,000 chips with no timer. (§4.1)
- **First-week welcome chips** — 500/day for 7 days, no streak required, no expiry. (§4.1)
- **Achievement-tied chip rewards** — already in the achievement registry.

The underlying problem (new user about to bust out and bounce) is solved by being *generous* — chips are play money; they don't need to be scarce. Manufactured scarcity is a gambling-industry pattern, not an entertainment-industry one.

### C.6 Synthetic / fake users to fill the ecosystem (rejected 2026-05-16)

Considered populating public rooms or leagues with synthetic human-presenting accounts to make the app feel busier than it is at low scale.

**Why rejected:** deception is brand-toxic. When a user thinks they "beat RoyalQueen86" and RoyalQueen86 doesn't exist, prestige is hollow. Discovery (and someone always discovers) destroys trust permanently. The honesty principle from §7.4 applies: we don't claim things we don't have.

**What we ship instead:**

- **Bot fill in tables** is *labeled* — bots are visibly bots, never disguised as humans (§5.4)
- **Quick Match** prefers all-human tables; falls back to bot-fill tables with explicit "Practice tier · bots present" label (§5.3)
- **Leagues** scale cohort size with population (§Appendix B item 18) — small honest cohorts beat fake-populated large ones
