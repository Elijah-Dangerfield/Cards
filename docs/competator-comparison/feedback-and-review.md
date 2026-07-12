# Competitor feedback & review database

A running database of the most valuable user feedback pulled from a direct competitor's
app-store reviews. We mine it for both praise (things worth copying) and complaints (gaps
worth beating), then map each theme to what Downcard already does so we can extract concrete
opportunities.

**Anonymization:** the competitor is referred to only as **"the Competitor."** Their name,
developer name, and support domain are deliberately kept out of this file. Reviewer handles
are public and kept as source IDs for traceability. Do not add the competitor's name here.

**How to read a theme:**
- **Sentiment** — Praise (do this), Complaint (beat this), or Mixed.
- **Signal** — rough strength across batches, from repetition + "found helpful" votes. High / Med / Low.
- **Downcard status** — Have it / Partial / Missing / Deliberately different.
- **Opportunity** — Copy / Improve / Avoid / Already ahead.

Batches logged:
- **Batch 1** — first review pull, ~65 reviews, 2023-07 to 2026-07.
- **Batch 2** — second pull, ~50 reviews, 2023-06 to 2026-07. Reinforced themes 1–3 heavily and
  added deal-distribution suspicion, dark-pattern spend buttons, collusion/account-farming, and
  cosmetics demand.
- **Batch 3** — forum threads (a poker community + the competitor's own player forum), including
  public replies from the competitor's founder. Different source type: long-form, argued, and
  stats-literate. Escalated the fairness debate to the center of gravity and surfaced the single
  biggest strategic opening — **provable fairness** (see the Strategic spotlight below).

---

## Competitor snapshot (anonymized)

- Store rating: **4.6★**, ~2.38K reviews, 100K+ downloads. Rated Teen, Simulated Gambling.
- Monetization: free, contains ads, in-app chip purchases, a premium subscription, "spins" reward loop.
- Positioning line they use on screenshots: **"Simple. Modern. Poker."** (near-identical lane to our
  "Simple, fun poker." Our separation must come from honesty and bot quality, not aesthetics.)
- Modes: single-player vs AI (works offline) plus online multiplayer including play-with-friends.
- Their own dev replies repeatedly claim the AI uses "GTO strategies" and that hands are dealt
  randomly. The volume and consistency of "it cheats" reviews suggests that message is not landing,
  whether or not it's true. That credibility gap is the opening.
- Consistent strengths: minimal UI, opt-in ads, in-game stats, responsive devs.
- Consistent weaknesses: bots that feel like they cheat, deal distribution that feels off, thin
  multiplayer, and monetization pressure that reads as pay-to-win.
- The founder personally engages on forums, is praised for taking criticism well, and repeats the
  "standard shuffled deck, GTO AI, it's just variance" line. When pushed to *prove* fairness with a
  Monte Carlo sim, he declined on the grounds that no external party can verify a closed-source
  App Store build without signed reproducible builds. That admission is the opening (spotlight below).
- Counter-narrative worth knowing: some skeptics argue a *play-money* app has no incentive to rig
  ("why would RNG favor anyone?"). Others answer: to juice engagement, ad-watching, and chip sales.
  Our honest-by-design, provable answer wins with both camps at once.

---

## Strategic spotlight: the fairness-proof gap

This is the highest-leverage finding in the whole exercise, so it gets its own section.

Across store reviews and especially the forums, the Competitor's biggest problem isn't a missing
feature — it's a **trust deficit about fairness** that they have publicly admitted they cannot close.
In a forum thread, players demanded the founder run a Monte Carlo simulation and market the app as
provably fair. His own words (paraphrased, anonymized): running a sim, posting numbers, or even
open-sourcing the code *wouldn't prove anything*, because no one can verify the App Store binary
matches the public code "without signed reproducible builds, which is a whole rabbit hole." His
conclusion: *"at the end of the day it comes down to trust."*

A stats-literate commenter raised the stakes: because **real money can be spent to buy chips**, a
genuinely rigged (even unintentionally "fat-tailed") deal isn't just annoying, it's potential legal
exposure. Another deleted the app over it despite loving the UI.

**Why this is our opening:**
- The demand is explicit, loud, and unmet. Users are *asking* for provable fairness by name.
- The incumbent has publicly framed it as too hard and fallen back on "just trust us."
- We share the exact same constraint (closed-source mobile build), so we can't hand-wave it either —
  but we can actually *do the work they said they wouldn't*: publish an internal MC/distribution
  report, commit to a verifiable/provably-fair shuffle scheme (e.g. commit-reveal seeds, per-hand
  seed disclosure after the hand), and make "here's exactly what the bot knew, and it was only what
  you knew" a visible, in-product artifact.
- Even a *partial* credible answer (transparent hand distributions + post-hand seed reveal) beats
  "it comes down to trust," and it directly converts the category's loudest complaint into our
  headline proof point.

**Caution — don't overclaim.** Provable fairness is a real engineering commitment, not a tagline. If
we say "provably fair," we must ship something a skeptic can actually check, or we inherit the same
credibility problem. Scope this deliberately; see opportunity #2.

---

## Theme index (ranked by signal)

| # | Theme | Sentiment | Signal | Downcard status | Opportunity |
|---|-------|-----------|--------|-----------------|-------------|
| 1 | Bots feel like they cheat / see future cards | Complaint | **Dominant** | Deliberately different | **Already ahead — lead the brand with it** |
| 2 | Bots predictable / samey / unbluffable / every hand collapses to 1v1 | Complaint | **Very High** | Partial | Improve |
| 3 | Pay-to-win / rigged-to-sell-chips (incl. rubber-band by bankroll) | Complaint | High | Deliberately different | Already ahead |
| 4 | Deal/RNG feels statistically off (monsters + trash streaks) | Complaint | Med-High | Deliberately different | Already ahead |
| 5 | Clean, minimal, modern UI | Praise | **Very High** | Have it | Match / hold the bar |
| 6 | Opt-in, non-intrusive ads (their ads also stall / one pushed malware) | Praise | High | Deliberately different | Already ahead (we have no ads) |
| 7 | In-game stats & analytics | Praise | High | Have it (gap: session P/L) | Already ahead / close small gap |
| 8 | Offline single-player vs AI | Praise | High | Partial | Improve / verify |
| 9 | Betting UX (type-in bet, sliders, quick actions, auto-fold/check) | Complaint | High | Partial | Improve |
| 10 | Pace & turn control (too slow / too fast / turn cue / pause / timer-fold bug) | Complaint | High | Partial | Improve |
| 11 | Empty / thin multiplayer tables | Complaint | High | Deliberately different | Already ahead |
| 12 | Hand history / net +/- / free showdown transparency | Complaint | Med-High | Partial | Improve (add session P/L) |
| 13 | Difficulty doesn't scale by stake | Complaint | Med | Partial | Improve |
| 14 | Cosmetics & expression (avatars, emotes, card backs) | Mixed | Med | Partial | Copy (non-P2W monetization) |
| 15 | Rewards / spins clutter = money grab | Complaint | Med | Deliberately different | Avoid (don't add) |
| 16 | Predatory UI / dark patterns (misplaced spend buttons) | Complaint | Med | Deliberately different | Avoid + confirm-on-spend |
| 17 | MP integrity & account/chip farming (collusion, reinstall exploits, lost accounts) | Complaint | Med | Partial | Improve |
| 18 | Play-with-friends cross-device | Praise | Med | Have it | Match |
| 19 | Responsive, caring devs / community | Praise | Med | Missing (process) | Copy (cheap trust) |
| 20 | Clarity cues (whose turn, who won, win screen) | Complaint | Med | Partial | Improve |
| 21 | Satisfying sound / haptics / feel | Praise | Med | Partial | Improve |
| 22 | Not enough free chips / steep bankroll jumps | Complaint | Med | Partial | Watch (economy) |
| 23 | Hidden house rake, no disclosure | Complaint | Low | Deliberately different | Avoid / disclose if added |
| 24 | Tutorial / explain terms | Complaint | Low | Missing | Copy |
| 25 | App name / logo hard to find (ASO) | Complaint | Low | Same risk | Note |
| 26 | Off-brand asks (Blackjack, roulette, Steam/PC) | Request | Low | Out of scope | Watch (don't dilute) |

---

## Detailed themes

### 1. Bots feel like they cheat / can see the board — *the dominant complaint*

By a wide margin the loudest, most-repeated, highest-voted theme across both batches. Players are
convinced the AI knows the turn and river, calls all-in with trash and hits its out, folds exactly
when they finally hold a premium, and can't be bluffed. Many tie it directly to a motive: make you
lose so you buy chips or watch ads. The developer's public "we use GTO, it's fair" replies have not
changed the perception.

| Date | Handle | Votes | Quote (trimmed) |
|------|--------|-------|-----------------|
| 2025-09 | Jac N | 54 | "Bluffing never works... raise with a bad hand, the AI always matches and goes all-in... great interface, gameplay is horrible." |
| 2025-07 | Ethyn Hills | 18 | "how blatantly the AI opponents cheat... all-in on the river with nothing and wins. Unplayable." |
| 2025-04 | b b | 18 | "the cheating AI... My best hands get destroyed by miracle hands." |
| 2025-04 | (AI overpowered) | 14 | "go all-in and they miraculously have a better hand 90% of the time... programmed so you're forced to buy chips." |
| 2026-07 | Shawn McCullough | 11 | "huge suspicion they know the table... bet huge with nothing, catch something absurd on the river. Every hand a bot raises preflop." |
| 2023-06 | Bryan Le | 10 | "they always catch the river magically... put them all-in with nothing and they win. It's rigged." |
| 2026-03 | Harman singh | 16 | "tweak the AI to not know the cards and river beforehand... 100s of reviews saying the same thing." |
| 2025-08 | Lee Last | 8 | "ONE player wins ~90% of hands regardless of cards... I'm ahead on all my other poker apps, not this one." |
| forum | Technical-Pear-7467 | 8 | "they'll check till the end, wait for you to bet large, and have four of a kind. The AI does seem a lil sus." |
| forum | The-SweatyTickler | 2 | "I stand corrected. Low-stakes AI is easy, anything above that is a joke. I'd put my money on it being BS." |
| forum | Fantastic-Worker-743 | 1 | "the entry table is all AI... I can predict the turn and river too often. Seems like they juice them to keep bad players engaged." |

New sub-angle in Batch 2 — **rubber-banding by bankroll:** several claim the game changes once you're
winning or after you spend. Zachary: *"seems to change algorithm depending on how much money you have...
skews games so others with less money win"* (confirmed in his own edit). John Pippett (Batch 1): *"spent
a dollar, suddenly dealt significantly worse odds."* Batch 3 forum adds an ad-watcher-profiling variant —
kelvintiger: *"the more I buy in using ads, the more I lose on the river... once they profile you as a
frequent player who watches ads, they stack the odds against you."* Notably, even players who defend the
app as a "skill issue" concede the higher-stakes AI feels rigged.

See the **Strategic spotlight** above — this theme is also the fairness-proof opportunity.

**Underlying need:** a demonstrably fair deck and opponents that win by odds, not by peeking. Trust.

**Downcard status — Deliberately different / Already ahead.** Server-authoritative dealer, one real
shuffled deck; bots decide from Chen-formula + Monte-Carlo equity + opponent modeling with **no access
to unseen cards.** This is precisely the failure the market keeps naming.

**Opportunity — lead the entire brand with it, and make it provable.** "A real deck, dealt fair."
"Bots that play the odds, not the deck." "Our bots can't see your cards, and neither can we." Consider
proving it in-product (deterministic seed reveal / provably-fair shuffle, or a post-hand "the bot only
knew what you knew" note). Perception is the battle here, not just the code.

---

### 2. Bots predictable, samey, unbluffable — and every hand collapses to heads-up

The second-biggest theme, and it's really two linked complaints. First: the bots feel like one model
copy-pasted, never bluff, and fold the instant you bet into a good hand. Second, very specifically and
repeatedly: **one bot jams preflop, everyone else folds, so nearly every hand becomes a 1v1** and the
table never feels alive.

| Date | Handle | Votes | Quote (trimmed) |
|------|--------|-------|-----------------|
| 2024-11 | David Cardoso | 6 | "the AI is completely predictable... once you're ahead they always fold to an all-in. It should detect that repeated all-ins are bluffs." |
| 2025-05 | Borat | 3 | "the AI has no personality, same model for each opponent. Almost never bluffs. Someone raises, everyone folds 95%. Not believable." |
| 2024-07 | Charles D | 3 | "all bots post blinds, one bets crazy high pre-flop, the rest fold. Every. Single. Time." |
| 2024-11 | Cassius S | 8 | "you're playing against 4 of the exact same opponent." |
| 2024-02 | Winson Ho | — | "you can easily guess what card they have." |

**Underlying need:** distinct, human-feeling opponents you can read, bluff, and out-play, in multiway pots.

**Downcard status — Partial.** We ship five *named personalities* (right instinct), but we must confirm
they (a) feel distinct in play, (b) can bluff and be bluffed, (c) adapt to a player who keeps jamming,
and (d) don't all fold to the first raise so hands stay multiway.

**Opportunity — Improve.** Make personality differences legible (playstyle tags/tells), guarantee some
bots defend and bluff, and specifically prevent the "one raise and everyone folds" collapse. This is a
named, high-frequency gap we can beat.

---

### 3. Suspected pay-to-win / rigged to sell chips

Many reviewers connect the cheating feeling straight to the business model: the game feels engineered
to drain chips so you buy more or watch ads, and to punish spenders/winners.

| Date | Handle | Votes | Quote (trimmed) |
|------|--------|-------|-----------------|
| 2025-04 | (AI overpowered) | 14 | "programmed this way so you are forced to buy the chips." |
| 2026-04 | CJC | 5 | "designed for the AI to win to keep you losing so you watch more ads." |
| 2026-02 | PROKINGREX | 1 | "shows me 80%+ odds and I lose every single one." |
| 2025-09 | R. Kanji | 2 | "you don't get many chips to mess with... one buy-in and I was toast." |

**Underlying need:** confidence the business isn't rigging the game against them.

**Downcard status — Deliberately different / Already ahead.** No cash-out, no chip-timer pressure, no
rake today. Real money can only *buy* chips; it can never change outcomes. Rewarded chips are
server-owned (client can't assert level/achievement credits).

**Opportunity — Already ahead.** Say it plainly: "we make money when you buy chips, never by making you
lose them." Protect it: don't add mechanics that reintroduce the suspicion (see themes 15, 16, 22).

---

### 4. Deal / RNG feels statistically off — too many monsters, and trash streaks

Distinct from "bots cheat": even watching the board, players feel the *dealing* is unnatural. Two
directions, both cited: far too many big made hands (quads, straights, flushes, back-to-back royals),
and long droughts of unplayable cards. This erodes trust in the shuffle itself.

| Date | Handle | Votes | Quote (trimmed) |
|------|--------|-------|-----------------|
| 2025-07 | Robert Swiontek | 10 | "Way too many flushes, straights, 4 of a kind... basically every other hand is 3 of a kind or better." |
| 2024-12 | Evan Marsh | 2 | "20-40 hands where the best is a single pair of 7s... feels incorrect, something with the dealing logic." |
| 2025-09 | Anthony Shi | — | "two royal flushes for the bots within a single tournament?" |
| 2025-02 | Rizeeen | 2 | "card reveal speed too fast... the offline AI bots feel unnatural." |
| forum | Technical-Pear-7467 | 8 | "insane amounts of pocket pairs — at least one or two every two rounds. I rarely get pocket pairs IRL, seems strange." |
| forum | borqz | 1 | "your AI model is clearly fat-tailed. Get MC stats and compare to real deck odds and win rates." |

**Underlying need:** believe the deck is a real, fair 52-card shuffle.

**Downcard status — Deliberately different / Already ahead.** Real single-deck deal from a
server-authoritative dealer. Note the connection to a Batch-1 complaint about an "infinite shoe" you
can't card-count — a real deck answers that too.

**Opportunity — Already ahead.** Part of the "dealt fair" story. If we ever want to prove it, hand
distributions and a provably-fair shuffle are the receipts.

---

### 5. Clean, minimal, modern UI — *the biggest single point of praise*

Nearly universal, and conceded even by furious 1-star reviewers. Vertical / one-hand layout gets
specific, repeated praise. This is the price of entry in the category, not a differentiator.

| Date | Handle | Votes | Quote (trimmed) |
|------|--------|-------|-----------------|
| 2025-07 | Bryan Wicker | 2 | "best UI of any poker app I've used (vertical layout is a large part why)." |
| 2024-09 | Lisandro N | 9 | "clean, minimal, and vertical design... easy on the eyes compared to neon apps. Playing one-handed is nice." |
| 2024-11 | Cassius S | 8 | "the UI is extremely well designed... smooth and very polished." |
| 2023-06 | Bryan Le | 10 | "As an adult, I like not having my screen look like a casino game." |

**Underlying need:** an uncluttered, fast, good-looking, one-hand table.

**Downcard status — Have it.** Dark, minimal, warm-felt design system.

**Opportunity — Match / hold the bar.** Confirm we nail vertical one-hand play. UI polish alone won't
win here; it's necessary, not sufficient.

---

### 6. Opt-in, non-intrusive ads (and their ad quality is a liability)

Recurring delight: ads are optional (watch to refill chips, skip hands, reveal AI cards), and "no ads
if you win." But Batch 2 also shows their ad *network* is a liability — stalling ads that don't pay out,
and one review reporting an ad that tried to install malware.

| Date | Handle | Votes | Quote (trimmed) |
|------|--------|-------|-----------------|
| 2024-07 | nascent | 7 | "genius monetisation: no ads if you win; run out, watch an ad to replenish." |
| 2025-09 | Buck Photography | 1 | "one ad attempted to install malware, several ads stall and don't trigger rewards." |
| 2025-05 | Abe | 13 | "No mandatory ads... this is not one of the scammy poker apps." |

**Underlying need:** monetization that never interrupts or endangers.

**Downcard status — Deliberately different / Already ahead.** No ads at all, so no ad-network risk.

**Opportunity — Already ahead.** "No ads. Ever." is the stronger version of what they're praised for,
and it sidesteps the malware/stall liability entirely.

---

### 7. In-game stats & analytics — *most-wanted "nice" feature*

Players love the built-in stats and say it helps them learn poker; several stay *because* of it, and
ask for more and clearer stats.

| Date | Handle | Votes | Quote (trimmed) |
|------|--------|-------|-----------------|
| 2025-02 | Overflow72 Px | 15 | "the statistics are very helpful. Go more into detail on tight and loose." |
| 2026-05 | Omer Ofir | — | "detailed stats on you and opponents... would love how much I made/lost per day/week/month." |
| 2025-10 | Alice Wright | 4 | "very useful app for learning poker." |

**Underlying need:** watch your own game improve over time.

**Downcard status — Have it, with one gap** *(verified in code 2026-07-12)*. Beyond XP / ranks /
achievements we already ship a real poker-analytics layer: lifetime hands played/won, win rate, fold
rate, showdown losses, and a VPIP / PFR / aggression-derived **Play Style radar** (gated at ~20 hands),
plus a paid **Opponent Style Reader**. The doc's earlier "Missing" was wrong. The one genuine gap is
**session / running net P/L** (win-loss for the current session).

**Opportunity — Already ahead; close the small gap.** Our stats already answer the "help me learn"
praise. Add session/running net P/L (the one analytics thing reviewers ask for that we lack), and
inline explainers for style terms (tight/loose) — the stats screen explains XP mechanics but not poker
style terms yet.

---

### 8. Offline single-player vs AI

Cited repeatedly as a top reason to choose the app: real poker anywhere, no connection needed.

| Date | Handle | Votes | Quote (trimmed) |
|------|--------|-------|-----------------|
| 2023-09 | Daniel Moore | 3 | "super fun offline poker... hands feel truly random." |
| 2024-08 | Alex Hay | — | "best offline poker app... this isn't a virtual casino." |
| 2025-09 | Buck Photography | 1 | "offline works unless you need more chips." |

**Underlying need:** play anytime, zero friction, no waiting for a table.

**Downcard status — Partial / verify.** Our dealer is server-authoritative, so confirm whether solo vs
bots works truly offline. If it doesn't, that's a real competitive gap for this audience.

**Opportunity — Improve / verify.** If solo isn't offline-capable, scope it. Offline vs bots is a
headline feature here, not a nice-to-have.

---

### 9. Betting UX — type-in amounts, better sliders, quick actions

The most common concrete UX complaint. No exact type-in bet, sliders too small, no all-in / call-fold
buttons, a half-pot button that miscalculates, no auto-fold / auto-check-any toggles, and a nasty bug
where the turn auto-folds mid-slider.

| Date | Handle | Votes | Quote (trimmed) |
|------|--------|-------|-----------------|
| 2025-04 | Alex Lim | 5 | "no option to type in bet amount... slider too small... 1/2 pot button can't calculate half pot." |
| 2025-01 | Jack Leah | 2 | "anything over 5 seconds it auto-folds, even when you're in the betting menu working the slider." |
| 2025-06 | trip | 1 | "would be nice to auto-fold or auto check/check-any." |
| 2026-04 | Simeon Koh | 1 | "options to bet need work — call/fold, slider, all-in button." |

**Underlying need:** fast, precise, forgiving, one-hand betting.

**Downcard status — Partial.** We have a raise field (typing supported). Confirm accurate pot-fraction
buttons (½ / pot / all-in), a usable slider, auto-fold/check toggles, and that the action timer never
fires while a player is actively setting a bet.

**Opportunity — Improve.** Cheap, visible wins named directly by their users. The "timer folds you
mid-bet" bug is exactly the kind of thing we can simply not have.

---

### 10. Pace & turn control — too slow, too fast, and no clear turn cue

Two-sided and frequent. Some want it faster (long AI timers, waiting on the whole table, inactive
players); others want to *slow down* to read the outcome (card reveals too fast, no pause, round ends
before they understand it). And a shared, specific one: no clear cue for **whose turn it is** in online
play, so people miss their turn.

| Date | Handle | Votes | Quote (trimmed) |
|------|--------|-------|-----------------|
| 2026-07 | NUGGET SALTSHAKER | 1 | "needs a better visual cue for when it's your turn. So much waiting online I miss my turn." |
| 2025-12 | Harry M | — | "let the user pause. Sometimes it's too fast to end the round; I need to review why I lost." |
| 2025-09 | Jacob | 3 | "let me increase speed vs AI, and fold/skip immediately." |
| 2026-06 | Geordi Holliday | 2 | "kick inactive players after no response twice in a row." |
| forum | paypaypayme (95 hrs) | 10 | "a +time option like live poker — once or twice a game to buy 5–10 more seconds when you're running out." |

**Underlying need:** control the pace — move fast when you want, and get a beat to understand each hand.

**Downcard status — Partial / partly ahead.** We have a Game-speed setting pacing bot think-time
(Normal/Fast) — answers "let me speed up vs AI." We have MP disconnect grace + reaping. Confirm we have
instant-fold, pre-actions, a clear turn indicator, and a post-hand review beat.

**Opportunity — Improve.** Surface speed control, add pre-actions and a prominent turn cue, and give a
short post-hand summary/pause. Directly maps to named complaints on both sides of the pace debate.

---

### 11. Empty / thin multiplayer tables

Repeated: online tables are dead (especially higher buy-ins) or the player never matches at all.

| Date | Handle | Votes | Quote (trimmed) |
|------|--------|-------|-----------------|
| 2024-01 | HYDR0GEN | 6 | "multiplayer games are mostly empty." |
| 2024-02 | Winson Ho | — | "too few real players, couldn't even find a cash game." |
| 2024-07 | Za | — | "online games mostly empty." |

**Underlying need:** a game *right now*, not an empty lobby.

**Downcard status — Deliberately different / Already ahead.** Public matchmaking fills seats with
**disclosed** bots, so a table is never empty and we tell you which seats are bots. Solves the empty-
lobby problem *and* the trust problem together.

**Opportunity — Already ahead.** Verify public matchmaking is in the launch build (it was an original
V1 non-goal). If it ships, it's a strong, honest answer to a top competitor pain.

---

### 12. Hand history / net +/- / free showdown transparency

Players want to review what happened: see whether the AI was bluffing, replay previous hands, track a
running net win/loss, and get a beat after the river. Notably, the Competitor gates "reveal folded AI
cards" behind a paid/ad power-up — a transparency people feel should be free.

| Date | Handle | Votes | Quote (trimmed) |
|------|--------|-------|-----------------|
| 2023-10 | Ryan Hayes | 3 | "I wish cards always flipped at the end so I could see if the AI was bluffing." |
| 2025-10 | J | 3 | "if there were a way to view your net +/- I'd be okay with it." |
| 2025-05 | Jose Mendez | 1 | "a feature to view previous hands, or longer to see the board after the river." |
| 2025-12 | Harry M | — | "even better if the app displayed a brief summary of why I lost." |

**Underlying need:** learn from the last hand; know if you're actually up.

**Downcard status — Partial** *(verified in code 2026-07-12)*. At showdown we show a hand-result recap
(winner, your hand, the board, XP earned), so the "review the last hand" beat exists. Folded hands stay
mucked by real-poker rules — we don't reveal them, and, importantly, we don't paywall a reveal the way
the Competitor does. Missing: a running **session net P/L**, and a **replayable hand-history archive**
(the per-hand XP ledger powers a "recent" feed but doesn't store cards/board/actions to replay).

**Opportunity — Improve.** Add session net P/L (pairs with feature 7). A fuller replayable hand history
is a larger, deferrable build. Note we already sidestep their misstep — mucked cards being hidden is
intentional real-poker behavior on our side, not a gated feature — so "transparency we don't charge
for" is still a fair contrast.

---

### 13. Difficulty doesn't scale by stake

Bots play the same at every buy-in, so low stakes aren't the gentle on-ramp beginners need.

| Date | Handle | Votes | Quote (trimmed) |
|------|--------|-------|-----------------|
| 2026-01 | Kralicek | 12 | "make low-risk games more casual... difficulty is the same on all buy-in levels." |
| 2026-03 | Harris Chan | 2 | "the AI arena and tournament mode are pretty bad." |

**Underlying need:** a real curve — easy tables to learn, hard tables to test yourself.

**Downcard status — Partial.** Five personalities exist; difficulty-by-stake unconfirmed.

**Opportunity — Improve.** Tie bot difficulty to stake tier (soft at the low end) and label table
difficulty before you sit.

---

### 14. Cosmetics & expression (avatars, emotes, card backs)

A double-sided theme. People *like* the avatars/emotes/card backs and want more, but complain that
cosmetics are locked behind expensive gems or grindy ad-chests, and that default avatars are generic.
This is the healthy, non-pay-to-win monetization lane.

| Date | Handle | Votes | Quote (trimmed) |
|------|--------|-------|-----------------|
| 2025-03 | alkynne | — | "not enough customization. Gems are expensive, avatars generic. I can't watch 50 ads to open a wooden chest." |
| 2026-03 | Spicychilly | — | "love the avatars, emotes and card backs." |
| 2025-06 | trip | 1 | "custom profile photos would be nice." |
| 2025-02 | Overflow72 Px | 15 | "wish there were more customization options for avatars." |

**Underlying need:** express yourself at the table; earn/buy cosmetics that stay reasonable.

**Downcard status — Partial.** We have avatars. Emotes and card backs unconfirmed.

**Opportunity — Copy (the good version).** Cosmetics are the *right* thing to sell in a no-cash-out game.
Ship expressive, fairly-priced (or earnable) avatars, emotes, and card backs. This monetizes without
touching fairness.

---

### 15. Rewards / spins clutter feels like a money grab

A cautionary tale. A "rewards/spins/fortune-wheel" update was widely disliked for cluttering a
previously clean app and muddying whether you're actually winning.

| Date | Handle | Votes | Quote (trimmed) |
|------|--------|-------|-----------------|
| 2025-05 | Zach Cardoza | 9 | "the 'rewards' update... money-grab features that don't fit the aesthetic, noise on an excellent UX." |
| 2025-10 | J | 3 | "not a fan of the 'spins'... hard to tell if you're doing well or just won free chips." |
| 2025-05 | NBP CENTER 001 | — | "if you want poker without bonus games, ads, and nonsense reward fireworks, this is the game." |
| forum | paypaypayme (95 hrs) | 10 | "wheel/reel rewards are mostly for AI games I don't use — I only play humans. Let me convert them, 10 reveals = 1 chip." |
| forum | Sk3pticat | — | "I have thousands of reveals/skips I could never use. Always disappointing to get those as a reward." |

A related Batch-3 signal: the reward *currency* is mismatched to how people play. Human-only players
pile up AI-game rewards (reveals/skips) they can't spend, and want to convert them. The founder said
unifying and exchanging these is "in development" — i.e. even the incumbent concedes the reward system
is confusing.

**Underlying need:** know your real performance; don't bury poker under slot-machine noise, and don't
hand out currency that doesn't fit how someone plays.

**Downcard status — Deliberately different / Already ahead.** We deliberately have no daily-spin /
streak / loss-aversion faucet. Validated here.

**Opportunity — Avoid.** Do not add spin/wheel mechanics. If we ever add daily rewards, keep them quiet
and never let them obscure real win/loss. A direct warning against our own future temptation.

---

### 16. Predatory UI / dark patterns (misplaced spend buttons)

A sharp, specific complaint: a "rebuy for gems" button sits exactly where you tap to bet/fold, so
players spend real currency by accident, with no confirmation. Also flagged: watching an ad to re-buy
into a tournament with half your chips feels too easy/exploitable.

| Date | Handle | Votes | Quote (trimmed) |
|------|--------|-------|-----------------|
| 2025-12 | Cain | 1 | "the rebuy-for-10-gems button is in the most predatory spot, right where you bet or fold. Scammed out of ~50 gems in a couple hours. A simple 'are you sure' would fix it." |
| 2025-07 | Steven Smyroglou | 1 | "make it so you can't watch an ad and be back in the tournament with half the chips so easily." |

**Underlying need:** never lose real currency by accident; spend should be deliberate.

**Downcard status — Deliberately different.** No ads and no gem-rebuy pressure. Our out-of-chips flow is
a low-priority, once-per-episode notification, not an in-table trap.

**Opportunity — Avoid + confirm-on-spend.** Never place a spend action in a hot zone; always confirm
any real-currency spend. Cheap trust, and a direct contrast we can point to.

---

### 17. MP integrity & account/chip farming

Batch 2 surfaced integrity holes: private games can be rigged by inviting your own alt accounts;
starter chips can be farmed by reinstalling; and at least one player lost their account entirely on
reinstall (no cloud save).

| Date | Handle | Votes | Quote (trimmed) |
|------|--------|-------|-----------------|
| 2025-12 | Kristián Baláž | — | "really easy to rig a game by setting up multiple accounts and inviting yourself. There should be a mode without invite." |
| 2025-07 | Steven Smyroglou | 1 | "make it so you can't just uninstall/reinstall to get 1K chips — makes it feel pointless." |
| 2026-04 | Jacob Pappas | 1 | "reinstalled and lost my account, won't load." |
| forum | paypaypayme (95 hrs) | 10 | "saw 2 people colluding at higher stakes — one raises till everyone folds except his buddy, then folds to hand him the chips." |

The forum sighting *confirms* the collusion exploit isn't hypothetical: at higher stakes, two humans
soft-play/chip-dump to transfer a bankroll. This is exactly the attack our real-stakes guard is built for.

**Underlying need:** fair tables, non-farmable economy, and progress that survives a reinstall.

**Downcard status — Partial.** We have collusion guards (two-human bot tables stay practice; real-stakes
only on majority-human tables), server-owned rewards, and starter-grant dedup work. Account persistence
across reinstall depends on our anonymous-account + claim-account flow.

**Opportunity — Improve.** Confirm starter-grant dedup can't be farmed by reinstall, and that anonymous
accounts survive reinstall or are recoverable (nudge account-claim). Our escrow collusion guard is
already a real answer to the self-invite exploit; make sure it covers private friend tables too.

---

### 18. Play-with-friends across devices

Praised as a standout: start a private game and invite friends regardless of their phone.

| Date | Handle | Votes | Quote (trimmed) |
|------|--------|-------|-----------------|
| 2025-07 | C. Espree | 7 | "you can play with your buddies. No matter what type of phone they have." |
| 2024-01 | HYDR0GEN | 6 | "would like friends in the game — invite and play with them." |

**Underlying need:** poker night with your actual friends, cross-platform.

**Downcard status — Have it.** 6-char room codes + deep links, Android + iOS.

**Opportunity — Match.** Our core story too. Keep invite/join frictionless and lead with it.

---

### 19. Responsive, caring devs / community

A trust multiplier. Reviewers repeatedly praise fast dev replies, bugs fixed from an email, and help
after crashes. The Competitor publicly replies to almost every review, which visibly builds goodwill.

| Date | Handle | Votes | Quote (trimmed) |
|------|--------|-------|-----------------|
| 2024-10 | Sean Elliott | 34 | "found a glitch, emailed the devs, they responded and fixed it. Shows they really care." |
| 2026-05 | Ben Isboy | — | "the customer service is awesome and helped me after crash issues." |

**Underlying need:** feel heard; believe the app is actively cared for.

**Downcard status — Missing (process, not code).** We have in-app feedback + bug-report plumbing.

**Opportunity — Copy (cheap, high trust).** Reply to store reviews, act on feedback visibly, close the
loop. Outsized effect on rating and trust.

---

### 20. Clarity cues — whose turn, who won, win screen

Small but repeated: it's not always obvious whose action it is, who won the hand, or that *you* won.

| Date | Handle | Votes | Quote (trimmed) |
|------|--------|-------|-----------------|
| 2024-09 | Lisandro N | 9 | "win screens should be a little longer or more obvious on the user's pic." |
| 2024-04 | GlueyTM | — | "sometimes it's not easy to tell who won — a little text saying who won wouldn't hurt." |
| 2026-07 | NUGGET SALTSHAKER | 1 | "needs a better visual cue for when it's your turn." |

**Underlying need:** never be confused about turn or outcome.

**Downcard status — Partial.** Confirm turn indicator prominence and a clear winner/win moment.

**Opportunity — Improve.** Obvious active-turn cue and a satisfying, legible winner callout. Cheap polish
that removes real friction.

---

### 21. Satisfying sound, haptics, and feel

Called out as part of the polish, with several asking for more (sound effects, animation flair).

| Date | Handle | Votes | Quote (trimmed) |
|------|--------|-------|-----------------|
| 2025-02 | Overflow72 Px | 15 | "I really enjoy the satisfying sounds and interface." |
| 2026-03 | Kayden Chen | 4 | "sound effects could be added to enhance the experience." |

**Underlying need:** a table that feels good in the hand.

**Downcard status — Partial.** Confirm sound/haptic coverage.

**Opportunity — Improve.** Tasteful chip/card sounds + haptics on key actions, without turning into a
slot machine.

---

### 22. Not enough free chips / steep bankroll jumps

An economy pacing complaint: too few free chips (one bust and you're broke), and stake tiers spaced too
far apart for free players to climb.

| Date | Handle | Votes | Quote (trimmed) |
|------|--------|-------|-----------------|
| 2026-05 | Uriah Frieze | — | "the jump from 10K to 100K is too large for non-paying players." |
| 2023-09 | Joe Morris | 4 | "maybe weekly coins, enough to buy into the first league." |
| 2025-xx | Кирилл Яцковский | — | "make the first 1k of bankroll free, no ads." |

**Underlying need:** a reachable stake ladder without paying.

**Downcard status — Partial.** Finite faucet (starter + achievements + level rewards to L20, no recurring
faucet). Bust-protection grant exists.

**Opportunity — Watch.** Make sure free players can still climb; if the ladder isn't climbable without
buying, the "no pay-to-win" story cracks the way theirs did. Note the tension with theme 17 (don't make
free chips farmable) — solve both together.

---

### 23. Hidden house rake with no UI disclosure

A pointed complaint: cash games quietly rake the pot with nothing in the UI to explain it. The developer
publicly acknowledged it and promised clearer disclosure.

| Date | Handle | Votes | Quote (trimmed) |
|------|--------|-------|-----------------|
| 2025-07 | Bryan Wicker | 2 | "cash games have a house rake... win a 150 pot, get 125 back, nothing in the UI about it." |

**Underlying need:** transparency about where chips go.

**Downcard status — Deliberately different.** No rake today (a possible future sink lever).

**Opportunity — Avoid / disclose if added.** If we ever add a rake, make it visible and explained at the
table. Silent rake burned trust for them.

---

### 24. Tutorial / explain the stats and terms

Newer players want onboarding: a tutorial and plain-language explanations of poker terms and stats.

| Date | Handle | Votes | Quote (trimmed) |
|------|--------|-------|-----------------|
| 2026-03 | Kayden Chen | 4 | "maybe add a tutorial." |
| 2025-02 | Overflow72 Px | 15 | "go more into detail on what they mean by tight and loose." |

**Underlying need:** learn the game without leaving the app.

**Downcard status — Missing.** No tutorial today.

**Opportunity — Copy.** A light tutorial + inline term explainers fits our "learn poker, no sleaze" angle
and supports the stats feature.

---

### 25. App name / logo hard to find (ASO)

Small but strategically relevant to us: reviewers couldn't find the app because the name has no "poker"
in it and the logo is dim/unrecognizable.

| Date | Handle | Votes | Quote (trimmed) |
|------|--------|-------|-----------------|
| 2024-07 | nascent | 7 | "the game doesn't have poker in its name so I always struggle to search for it." |
| 2023-10 | Abdullah Ghaly | — | "the logo is so dim... hard to find when searching my apps list." |

**Underlying need:** find the app again.

**Downcard status — Same risk.** "Downcard" also has no "poker" in it.

**Opportunity — Note (ASO).** Put "Texas Hold'em / Poker" in the store title/subtitle and keyword field,
and make sure the launcher icon is high-contrast and recognizable at a glance.

---

### 26. Off-brand feature asks (Blackjack, roulette, Steam/PC)

A few requests to add casino games or ship on PC. Worth logging, but most cut against our positioning.

| Date | Handle | Votes | Quote (trimmed) |
|------|--------|-------|-----------------|
| 2025-02 | Rizeeen | 2 | "adding a Blackjack mode would bring more variety." |
| 2024-xx | kevinco | — | "you should make this for PC / Steam too." |

**Underlying need:** more to do; play on more devices.

**Downcard status — Out of scope.** We're honest poker, not a casino-games hub.

**Opportunity — Watch (don't dilute).** Adding roulette/blackjack risks the exact "casino floor" vibe we
avoid. PC is a real audience but a big scope call. Log, don't chase.

---

## Extracted opportunities (the shortlist)

Ranked by leverage — where the market is loudest and we're best positioned.

1. **Make "honest bots + real deck" the headline.** The Competitor's #1 and #4 complaints (bots that
   seem to cheat, deals that feel rigged) dominate every source. Our non-cheating, equity-based,
   server-dealt bots and single real deck are the answer. Lead the store page with it.
2. **Own "provably fair" as a campaign the incumbent said it couldn't run.** Their founder publicly
   fell back on "it comes down to trust." We can ship something a skeptic can actually check: a
   published hand-distribution / MC report, a commit-reveal or per-hand seed-disclosure shuffle, and an
   in-product "the bot only knew what you knew" artifact. Highest-leverage differentiator in this doc.
   Scope it as real engineering, not a slogan (see Strategic spotlight).
3. **Fix the "samey bots / every hand is 1v1" feel.** Distinct, bluff-able, adaptive personalities that
   don't all fold to the first raise. Beats a very high-frequency, very specific complaint.
4. **Close the small stats gap: add session net P/L.** We already ship the analytics layer their users
   love (win/fold rate, showdown stats, a VPIP/PFR Play Style radar) and a showdown recap — the doc's
   old "Missing" was wrong (verified in code). The remaining asks are session/running net P/L and inline
   style-term explainers; a fuller replayable hand history is deferrable. We already don't paywall
   showdown transparency (mucked cards are hidden by real-poker rules, not gated).
5. **Betting + pace quick wins:** accurate pot-fraction buttons, all-in button, pre-actions, a clear
   turn cue, a +time / time-bank option, a post-hand review beat, and never auto-fold a player mid-bet.
   All named directly; cheap.
6. **Confirm or add true offline solo vs bots.** A headline reason this audience picks an app.
7. **Sell cosmetics, not outcomes.** Avatars, emotes, card backs — fairly priced/earnable. The right
   monetization for a no-cash-out game. Keep any reward currency singular and spendable (their split
   reveals/skips/spins pile up unused).
8. **Don't self-sabotage:** no spin/wheel clutter, no spend buttons in hot zones (always confirm), and
   disclose any future rake. Three wounds they took that we can simply not take.
9. **Harden integrity:** non-farmable starter chips, reinstall-proof accounts, and collusion guards that
   also cover private friend tables — the chip-dumping exploit is confirmed in the wild, not theoretical.
10. **Reply to reviews / close the loop.** Process, not code. Their founder earns real goodwill doing
    exactly this on forums; it's cheap trust and rating lift.
11. **ASO:** keep "Poker / Texas Hold'em" in the store title/subtitle and ship a high-contrast icon,
    since "Downcard" doesn't say poker.

---

*Next batches: append a new "Batch N" note above, fold new quotes into the themes, add themes as they
emerge, and bump signal strength where a complaint intensifies. Keep the competitor unnamed.*
