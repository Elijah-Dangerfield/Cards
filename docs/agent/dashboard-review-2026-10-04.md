# Dashboard review, 2026-10-04

Prod only. Loki numbers cover the last 30 days (its hard limit). Postgres numbers are all-time unless noted.

## Phase 1: facts

Four agents read the 12 Downcard boards in parallel: engineering health, economy and revenue, gameplay and retention, players and feedback. The facts that changed a decision are below. Everything else was background.

**Growth.** New accounts per week: 11, 20, 37, 71. Weekly players: 31, then 45. Hands per week went from 662 (Sep 14) to 2,687 (Sep 28). About 40% of hands are multiplayer.

**Matchmaking.**
- 30d: 186 public searches, 35 successes, 102 abandons.
- The bot offer appears after `SEARCH_WINDOW = 60.seconds` (`PublicSearchingViewModel.kt:526`). It was shown 16 times.
- Of the 102 abandons, 101 happened in under 100s and 40 in under 10s.
- Last 7d: 21 installs searched 83 times and got 6 matches.

**Player stats.** 37 accounts have multiplayer hands or buy-ins but `user_player_stats.hands_played = 0`. 440 finished hands belong to accounts whose stat is 0, the latest at 16:35 UTC today. `achievement_counters` lives on the same row.

**Activation.** Client telemetry, 14d: 111 installs finished onboarding, and 93 of them (84%) started a game. The server's "104 signed up, never played" comes from the stat bug above.

**Retention.** Of 142 accounts older than 8 days:
- 95 played at least once.
- 49 came back after day 1.
- 24 were still active after day 7 (17%).

The D7 panel shows 3.5% because it only counts activity on exactly day 7.

**Messages.**
- 203 founding-member inbox messages were sent. 100 of those players played afterwards, and 1 opened the inbox. The inbox lives at Profile > Notifications.
- The two dialogs (Aug 29) went to bust-protection players. Both quit within 20 minutes and never came back, so neither was ever seen.

**Economy.**
- All-time: 3.58M chips minted, 0.81M sunk.
- Achievement and level-up grants (642k) outweigh all non-table sinks (45k) by 14 to 1.
- No wallet is below 1,000 chips. Bust protection has fired twice ever.
- Revenue: $1.98 from 2 small packs. No medium or large pack has ever sold.

**Health.** Nothing is firing.
- ANR-free sessions are 97.4%. That's the Oct 1 cluster on `PlayMultiplayerRoute`, build 1209, already tracked as ENG-49.
- Wallet serialization 500s are ENG-48.

**Feedback.** Two Sentry reports in 30d, both banter between two Polish friends. Nothing to act on.

## Phase 2: ideas

1. Show the bot option sooner in public matchmaking.
2. Fix the multiplayer `hands_played` gap.
3. Ask active regulars for feedback.
4. Win back quiet regulars (SofiaCab, Ilucha, slavik).
5. Thank and follow up with the two buyers.
6. Repair the misleading panels.
7. Add a daily return hook (the daily bonus that was deferred).
8. Tighten the economy so chip packs have a reason to exist.
9. Investigate the reconnect-downtime p95 that doubled four weeks running.
10. Investigate the sandbox purchase credited in prod.
11. Investigate multiplayer chips netting positive over 30d.
12. Investigate `hand_finished` p99 of 12s.
13. Act on the 49% never-played number.

## Phase 3: cuts

| # | Verdict | Why |
|---|---|---|
| 4, 5 | Cut | In-app messages only reach people who open the app. These players haven't since Aug 31 to Sep 24. |
| 7, 8 | Cut for now | Real levers, but low conviction at 45 weekly players. Both pull against each other (a daily bonus inflates further). Kept as context. |
| 9 | Cut | Only 43 recoveries in 7d, so p95 is roughly the top two. Most long ones are build 1209 with `attempts=1`, which reads like time spent backgrounded, not a socket that failed to reconnect. |
| 10 | Cut | A prod App Store client buying in sandbox with no hands played looks like App Review. The `iap_sandbox.*` reason keeps it out of revenue by design. |
| 11 | Cut | Disclosed bots pay capped real chips by design (public matchmaking plan). |
| 12 | Cut | 24h window only, tiny sample, not tied to any user complaint. |
| 13 | Cut | False. Replaced by #2. |
| 1, 2, 3, 6 | Kept | Each has verified evidence and a concrete next step. |
