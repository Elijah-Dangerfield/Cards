---
purpose: Hand-off brief for a designer (human or AI) producing UI proposals for Cards.
scope: V1 primary, with V1.x–V2 roadmap context at the end. No visual or UI direction — UX, structure, and content only.
how-to-read: This document describes what the app is, what each screen contains, and how users move through it. It deliberately omits any visual, typographic, sensory, or tonal direction so that proposals are not biased by the current implementation's aesthetic choices.
---

# Cards — Designer Brief

## 1. What the app is

Cards is a social poker app for the 18–34 player who plays poker for fun, not income. V1 ships as No-Limit Texas Hold'em. V2+ adds other card games.

The app supports three modes of play, all first-class:

1. **Solo vs bots** — practice, learning, and a complete progression loop for users who never touch multiplayer.
2. **Friend games** — private rooms joined via short alphanumeric codes or deep links.
3. **Public rooms** — matchmade games with strangers, organized into stake tiers.

Progression is carried by three meta-systems that operate over the gameplay layer: **lifetime XP / level**, **weekly leagues** (competitive ranking that resets every Monday), and **seasonal cosmetic tracks** (themed rotations).

## 2. Ethos

These are the principles every design decision is checked against.

- **Respect the player's time.** No daily-obligation mechanics. No login streaks. No "your usual play time" notifications. No manufactured urgency.
- **Anonymous-by-default.** A new user is at a table within ~60 seconds of opening the app. No email, no avatar picker, no name picker — identity is auto-assigned and editable later. Sign-in is offered but never required and never proactively pushed.
- **Solo earns, multiplayer ranks.** Bot play feeds progression (XP, achievements, most cosmetics) but not competitive standings (leagues, tournaments). The solo path is complete on its own; the multiplayer path adds competition.
- **No pay-to-win, structurally.** Real money and chips buy cosmetics and table buy-ins. They never buy gameplay advantage, and they never buy prestige items — the items that signal accomplishment are earned by doing the thing, never sold. The shop catalog and the earned-cosmetic catalog are disjoint.
- **No casino patterns.** No slot-style wheels. No expiring chip bonuses. No "almost out of chips!" prompts. No loot boxes. No insurance bets. Chips never disappear unless the user voluntarily spends or loses them.
- **Honesty.** Bots are visibly labeled as bots. We do not invent synthetic human players to make the ecosystem feel busier. We do not over-claim features (e.g., "AI anti-cheat") we do not have.
- **Friction is reserved for the right places.** Adding bot fill, joining a friend's table, and re-buying after a bust are zero-friction. Identity claim, hosting a public room, and adding a friend are gated where accountability matters.
- **Social is the spine, not a feature.** Room codes, deep-link invites, recently-played-with, and friend presence are everywhere there's a play surface.

## 3. Player journey

### 3.1 First session

1. User opens the app and lands on a launch surface offering two paths: continue as guest (default) or sign in.
2. Continuing as guest assigns the user a generated display name and an avatar from a small gallery. Both are editable later but not now.
3. One short explainer surface introduces the app in a single line.
4. User lands on the home surface. A "Practice" call-to-action is the recommended first action.
5. A bot game begins. The first hand ends in a result moment that may include XP awarded and possibly an achievement unlock.
6. After several hands, a soft prompt invites the user to invite a friend via deep link. It is dismissible and not repeated aggressively.

### 3.2 Returning session

A returning user opens the app and lands directly on the home surface. The home surface communicates that the app is a living ecosystem of people playing — friends online, league standing, what's happening this week — and offers one-tap entry into any play mode.

### 3.3 Cadence

- **Weekly** — leagues reset Monday. Promotion / demotion is framed as a celebration, not a punishment.
- **Every several weeks** — a new season rotates the available cosmetics. Past-season items are visible on profiles but no longer purchasable.
- **Event-driven notifications only.** Notifications fire on real events (a friend joined a table; league reset in 2 days; an achievement was unlocked). Never on modeled "you usually play around this time."

## 4. Feature inventory

### 4.1 In V1

- **Core gameplay** — No-Limit Texas Hold'em, 2–9 seats, configurable buy-in and turn timer, bots with three difficulty tiers and named personalities.
- **Anonymous-by-default identity** with optional claim (Apple / Google sign-in).
- **XP and levels** — every finished hand awards XP, weighted by investment and hand strength. Levels confer status and unlock titles at milestones; they never gate gameplay.
- **Achievements** — roughly 37 entries across rarity tiers (Common, Rare, Epic, Legendary), with chip and cosmetic rewards at higher tiers. Locked achievements are mystery silhouettes.
- **Wallet, stack, and buy-ins** — chips live in a wallet that persists across sessions. Sitting at a table moves a buy-in from wallet to stack; standing up returns the remaining stack to wallet. Re-buy on bust is one tap; if the wallet is short, a tier-downgrade or "second wind" (free 1,000 chips) keeps the user playing.
- **Friend games** — 6-character codes, deep-link invites, share-sheet flow.
- **Public rooms** — Quick Match (one tap into a stake-tier-matched room) and Browse (filterable list of open rooms). Five stake tiers from Practice to Premium.
- **Bot fill** — one-tap, free, unlimited. Bots are visibly labeled as bots. Tables with majority bots count as solo for league purposes.
- **Reconnect handling** — if a player disconnects mid-hand, a stand-in bot preserves their stack; reconnect within a grace window resumes the seat.
- **Table-side social** — emoji blasts (one-tap, full-screen brief animation, per-user cooldown, mutable per opponent). Themed emoji packs are unlockable via the shop.
- **Block and report** — block is instant and silent (per-user, mutual invisibility); report enters a human-reviewed queue. No auto-bans in V1.
- **Shop** — chip-priced cosmetics: card backs, table felts (visible only to the owner on their own play surface), emote packs, avatar frames, name color / glow, profile decoration. Categories Featured / Browse / Vault.
- **In-app purchases** — three native chip packs ($1.99 / $9.99 / $49.99). No subscriptions, no rewarded ads.
- **Notifications** — event-driven, opt-in by category (league, friend, battle pass, achievement). Hard cap at 2 per day.
- **Profile** — avatar, display name, level, member-since, hands played, top-3 pinned achievements, current league tier, active earned title, season-participation badges.
- **My Items** — the player's owned-cosmetics shelf, including both purchased and earned items. Earned items carry an "Earned" badge and source attribution.
- **App-store review prompt** — uses native OS APIs only, fired at calm positive moments (post-win exit, legendary unlock, level 10) after eligibility gates. Never after a bust or error.

### 4.2 Shipping shortly after V1

- **Weekly leagues (V1.1)** — 10 tiers (Bronze → Royal Flush). 30-player cohorts (smaller at low scale, honestly). Top 7 promote, bottom 5 demote, middle 18 hold. Reset Monday. Top tier feeds a Royal Flush Tournament with exclusive unlock-only cosmetics. Solo-only users show as "Unranked" with neutral copy.
- **Profile depth (V1.2)** — play-style heat map (a 2D position derived from a player's play stats, visible on opponents' profiles only after 50+ shared hands), hand history viewer (last 50 hands, replayable), "best hand ever" auto-pin, optional bio.
- **Reactive emoji (V1.2)** — game-fired emoji on big moments (big pot, bad-beat river, first hand-win of session, bust). Toggleable.
- **Seasonal battle pass (V1.3)** — 6-week seasons. Free track (with a headline cosmetic at the final tier for everyone) and premium track (unlocked with chips or by completing a legendary achievement during the season).
- **In-game chat (V1.x)** — room-scoped, ephemeral, pre-canned phrases first, free-text behind a per-user toggle with filtering and rate limiting.

### 4.3 V2+

Sit-and-go tournaments, heads-up duel, additional card games (Blackjack first, then Hearts/Spades), spectator mode, clans, ML-based integrity detection with appeals.

## 5. Screens

Each entry describes **what content and actions live on the screen**, not how it is laid out or styled. The number and grouping of screens is the current implementation's structure and is open to redesign — what matters is that all content listed is reachable.

### 5.1 Entry & onboarding

- **Launch / auth surface.** Two paths: continue as guest (primary), sign in with Apple or Google (secondary).
- **Onboarding explainer.** One tagline, one sentence, one button to proceed. New-user only.

### 5.2 Home

The hub the user lands on every session. Its job is to communicate that the app is a living ecosystem and to route the user into play within one tap.

Content:

- Player identity summary: avatar, display name, current level, current league tier (or "Unranked"). Tapping opens profile.
- A live activity ticker rotating real signals (e.g., "X promoted to Diamond," "182 players online," "Season ends in 5 days"). Honest data only.
- Four primary play routes:
  - **Practice** (vs bots) — pulse-emphasized for first-time users; tooltip on first launch only.
  - **Quick Match** — one tap into a stake-tier-matched public room.
  - **Friend Game** — create with code, or paste a code to join.
  - **Tournament** — disabled in V1 with a "Coming in V2" affordance.
- Friends-online strip with avatars; tapping a friend shows what table they're at and offers a join.
- One featured shop item (current limited-time drop). Tapping opens the shop.

Explicitly **not** on home: login bonuses, "spin to win," pop-up promotions, "almost out of chips" nudges.

### 5.3 Lobby / table picker

Before any play surface, the user picks the parameters of the game they're entering. Content varies by mode:

- **Practice (bots)** — choose stake tier (which sets blinds and buy-in), table size (2–9), bot difficulty.
- **Quick Match** — implicit: the server picks the room. The lobby may show what tier the user qualifies for and a brief "finding a table" state.
- **Friend Game (create)** — choose stake tier, table size, then receive a share-able code and link.
- **Friend Game (join)** — enter or paste a code.

### 5.4 Play (the poker table)

The core gameplay surface. The same surface backs solo bot games, friend games, and public rooms — content varies by context, not by structure.

Content:

- Each seat (2–9): occupant identity (avatar, display name, label "bot" where applicable), current stack, recent action, current bet, sit-out state.
- Community cards in their current revealed state.
- The current pot, including any side pots.
- The local player's hole cards.
- The current player's turn indicator and a turn timer.
- Action affordances for the local player when it is their turn: fold, check / call (with amount), bet / raise (with amount, plus quick-pick presets and a free-input).
- Re-buy prompt when the local player's stack hits zero (one-tap re-buy if wallet allows; alternatives if not — tier downgrade or "second wind").
- Sit-out toggle.
- Emoji blast tray (one tap fires a full-screen animation; cooldown applies).
- Tap-an-avatar surface to mute a specific player's emoji, block, or report.
- Leave-room affordance.
- Reconnect / disconnect state surfacing.
- End-of-hand result moment: who won, with what, pot delta, XP earned, any achievement progress, any unlock.

### 5.5 Profile

The player's own profile, and the same screen used to view other players' profiles (with privacy filtering).

Content (V1):

- Avatar, display name (with edit affordance on own profile).
- Level and current XP progress.
- Member-since date.
- Hours played, hands played.
- Up to three pinned achievements.
- Current league tier (or "Unranked").
- Active equipped title.
- Wall of season-participation badges.
- Static "Claim your account" card for anonymous users — never modal, always inline.
- Settings entry point.
- (Other-profile only) block, report, send friend request.

Content added in V1.2:

- Play-style heat map (own profile always; opponent's only after 50+ shared hands).
- Hand history viewer (last 50 hands, each replayable).
- Best hand ever (auto-pinned).
- Optional bio.

### 5.6 My Items

The player's owned-cosmetics shelf. Contains both purchased and earned items, with earned items carrying an "Earned" badge and source attribution (achievement / league / tournament / season). Each item can be equipped, unequipped, or inspected.

### 5.7 Shop

Content:

- **Featured** — currently rotating chip-priced items.
- **Browse** — full catalog, filterable by category (card backs, table felts, emote packs, avatar frames, name color, profile decoration). Owned and earned items are marked accordingly.
- **Vault** — past-season items, visible but not purchasable.
- **Chip packs** — three IAP tiers ($1.99 / $9.99 / $49.99).

Each item shows its full identity and price before purchase. No random packs.

### 5.8 Achievements

A browsable catalog of all achievements. Unlocked entries are visible; locked entries are mystery silhouettes (rarity tier visible, content hidden). For each unlocked entry: name, description, rarity, date unlocked, reward granted. Up to three can be pinned to the profile.

### 5.9 Stats

A progression-detail surface aggregating the player's XP history, level progress, recent XP events (per-hand breakdowns), and milestone unlocks. Educational and reflective, not actionable.

### 5.10 Settings & support

Reachable from the profile. Contains:

- Notification preferences (per category).
- Privacy preferences (profile visibility: public / friends-only / private).
- Display-name and avatar edit.
- Sound and haptics toggles.
- Account management: claim (anonymous → claimed), sign out, delete account.
- Feedback submission.
- Bug report submission.
- (Internal builds) QA menu.

### 5.11 Leagues (V1.1)

A league-standing surface showing the player's current tier, current cohort (~30 players) ranked by this week's MP XP, the player's position, the promotion line and demotion line, time to reset, and the rewards on offer at this tier. Solo-only players see a neutral "Unranked" state with one-line explanation.

### 5.12 Season / battle pass (V1.3)

A tracker surface showing season progress (current tier on the free track, current tier on the premium track if unlocked), the upcoming tier rewards, time remaining in the season, and the premium-track unlock affordance (chips or a legendary achievement).

### 5.13 Friends & invites

A friends surface listing:

- Friends online and what table (if any) they're at — one-tap join.
- All friends, with recent activity indicators.
- Incoming friend requests.
- "Recently played with" list (auto-generated, claimed accounts only) with one-tap "invite to game."
- Add-friend affordance (gated behind claim).

### 5.14 Notifications inbox

A surface showing recent system events: league transitions, friends online, achievements unlocked, season changes, account-recovery prompts. Tapping an item routes to its source surface.

### 5.15 Modal / sheet surfaces

These appear on top of other surfaces rather than being navigated to:

- **Achievement unlock moment** — flip + reveal of the earned achievement with its reward.
- **Level-up moment.**
- **End-of-hand result.**
- **Bust / re-buy prompt.**
- **Welcome / day-1 grant prompts.**
- **Claim-account inline prompts** — only fired at moments where the user is attempting an action that requires claim (host a public room, add a friend).
- **Share-sheet** (friend game code).
- **Block / report flows.**

## 6. Cross-cutting UX rules

These constrain the design across every screen.

- **60-second rule.** A new user is at a table within 60 seconds of first launch. Nothing is allowed to interrupt that path.
- **No proactive claim prompts.** The system never asks an anonymous user to claim their account except inline when the action they attempted requires it.
- **No popups for promotion.** No interstitials advertising the shop, the season, or "we miss you." The home surface communicates these passively.
- **Single source of identity per user.** Display name and avatar are consistent across all surfaces — home, table seat, profile, shop reviews, friends list, leaderboard.
- **Bots are always labeled.** At the table, in the lobby, in any history surface.
- **Earned and purchased cosmetics coexist in the same shelf.** They are distinguished by an "Earned" badge, not by separation.
- **Empty states are neutral, never punishing.** A solo-only player sees "Unranked" with a one-line invitation to multiplayer, not a locked-out message.
- **Reachable, not loud.** Settings, support, claim, account deletion, and privacy are always reachable but never solicit attention.
- **Disclosure where it matters.** When an anonymous user is about to do something with permanence implications (large shop purchase, accumulated progress), the consequence of "uninstall = account lost" is surfaced — once, in context, not as a recurring nag.

## 7. Things the app deliberately does not have

These should not appear in any proposal, even if they would be conventional.

- Daily login bonuses, login streaks, streak freezes.
- "Spin to win" or any wheel-of-fortune mechanic.
- Daily quests / daily challenges with reminder pings.
- Expiring chip bonuses or any timed-pressure currency mechanic.
- Random loot boxes or odds-disclosed packs of any kind.
- Wait timers or energy gates.
- "Your chips are running low" or any psychological-pressure copy.
- Persistent direct messages or friend feeds (chat is ephemeral and table-scoped).
- Pre-game lobby chat in public rooms.
- Synthetic / fake human players.
- Real-money cash-out, insurance bets, or any actual-gambling mechanic.
- Live equity / hand-strength assistance in multiplayer (allowed in solo bot play only, as a free settings toggle).

## 8. Open product decisions worth knowing

Things still under design discussion, in case a proposal nudges them:

- Season length (working assumption 6 weeks).
- Friends-list capacity.
- Quiet-mode toggle that hides leagues and seasons for power users.
- Whether limited-time cosmetic drops in early V1 graduate to a full season framework in V1.3.

---

For any question not answered above, default to the principles in §2.
