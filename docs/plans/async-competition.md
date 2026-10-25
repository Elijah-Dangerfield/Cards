# Playing together without being online together

With about 300 players spread across the day, two people are rarely on at once, so public tables sit empty and the app can feel lonely. Getting everyone online at the same time is hard. Competition that doesn't need anyone else online is easier.

Start with a **daily challenge**, the smallest build and the quickest read on whether people want to compete. Then add **weekly leagues** on top of the XP you already award, and use the challenge to power **async brackets** in the Home screen's "Tournament: Coming soon" slot. Each step works on its own and makes the next one better.

## The four options

| | What it is | Needs players online? | New gameplay? | Build size | Feels social? |
|---|---|---|---|---|---|
| **A. Weekly leagues** | Duolingo-style. You're grouped with about 15 people. Weekly XP ranks you. Top few move up a tier, bottom few move down. | No | No, uses existing XP | Medium | Some |
| **B. Daily challenge** | Everyone plays the same dealt hands against the same bots. One leaderboard per day. | No | Small: fixed deck seed | Small to medium | Yes, "beat my score" |
| **C. Async brackets** | 8 or 16 players. Each round, you and your opponent play the same challenge deal. Higher stack advances. | No | Builds on B | Medium | Yes, head-to-head |
| **D. Scheduled game night** | A weekly time when public tables are promoted and pushed. | Yes | No | Small | Very, if people show up |

D is the cheapest test, but it depends on the thing that's hard: people showing up at the same time.

## A. Weekly leagues

- Leagues run Monday to Sunday. Anyone who plays a hand that week gets placed in a group.
- Groups have up to 15 players at the same tier. The weekly score is XP earned that week, which you already calculate per hand.
- At the end of the week, the top 3 move up a tier, the bottom 3 move down, and everyone else stays.
- Tiers with poker flavor that don't sound like a casino: **Kitchen Table, Back Room, Card Club, Main Event, Final Table**.
- Prizes: chips on promotion (play money, so nothing of real value), a tier badge on the profile, and a card back or felt for reaching a new tier for the first time. Your backlog already plans "earned from Bronze league" labels on cosmetics.

The hard part at your size is a group with almost nobody in it. There are three ways to handle it, and they combine:

1. **Small groups.** Form groups at whatever size you have, down to 4 or 5, and promote fewer people from small groups (the top 1 in a group of 5, say). Simplest, and always honest.
2. **Pace players, labeled as bots.** Fill a group to about 10 with clearly marked bots whose weekly XP follows a realistic curve, so the board never looks dead. **They can't take promotion or relegation spots;** those are decided among humans only. This matches your existing rule that bots are always disclosed.
3. **Your own last week.** Show "you vs. last week" next to the group, so even a solo player has something to beat.

I'd do 1 plus 2: real groups of any size, topped up with labeled pace bots, with moves decided only among humans.

What it takes:
- On the server, a weekly job that forms groups, a `league_memberships` table, and weekly XP summed from the `xp_events` you already sync.
- Server-side XP. Your backlog notes the server currently accepts the XP the client sends (with a cap) and should calculate it from synced hand facts once XP decides anything. Leagues are that trigger, so it's part of this build.
- In the app, a League screen (the group, your rank, time left), a week-end result screen, and a Home card.
- A guard against grinding. XP from bot games already counts half; add a daily cap on league XP so one marathon session can't win the week.

## B. Daily challenge

- Every day, everyone gets the same seeded deck and plays the same 10 hands against the same three bots.
- Your score is your ending stack. One global leaderboard per day, plus your friends' scores.
- It suits poker unusually well. With identical cards, skill shows and luck mostly cancels out. Tournament poker calls this "duplicate poker."
- It's a daily reason to open the app, and something to text a friend about: "I got +1,800 on today's deal."
- To build it: a seeded deck in the engine (the bots are already deterministic or close to it), a server endpoint for today's seed and scores, and a challenge screen plus results. Feeds league XP too.

## C. Async brackets (the "Tournament" slot)

- Sign up for a bracket of 8. Each round lasts a day: you and your opponent each play that round's challenge deal whenever you like. The higher stack advances.
- Fill empty seats with labeled bots that play the deal for real, so a bracket always starts. A bracket with only one human is just a challenge run, which is still fine.
- The winner gets chips and a trophy badge.
- To build it: bracket formation, round timing and seeding. It mostly reuses B. Do it after B proves people play the challenge.

## Suggested order

1. **Daily challenge (B).** Smallest build, quickest to tell you whether people want to compete.
2. **Weekly leagues (A),** with challenge scores and regular play both earning league XP. Includes the server-side XP trust work.
3. **Async brackets (C)** in the Tournament slot.
4. Run **game night (D)** any time as a free experiment, and tie it in: double league XP on Friday nights.

## How you'll know it's working

- More players come back within 7 days (the main one).
- Share of weekly players who play the challenge or appear in a league.
- Promotions per week, and whether promoted players come back the next week.
- If fewer than about 20% of weekly players touch the challenge after two weeks, rethink before building leagues on top of it.

## Open questions for you

- Can players see each other's names on leaderboards, or initials only? This interacts with the social features you descoped to V2.
- Should league chips be large enough to matter in the shop, or token amounts? This needs a decision on how it affects chip purchases.
