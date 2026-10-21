# Downcard marketing copy

App name: Downcard
Core truth: Simple, fun poker.
Voice: warm, simple, sincere. Poker night should be fun, not a casino that eats your money.
Avoid: cocky punchlines, clever antithesis ("X is fake, Y is real"), snark, and any claim we can't stand behind.

## Positioning

Downcard is the honest answer to every rigged-feeling, casino-style poker app. Two pillars, both true:

1. **No house.** The app never plays against you, never takes a rake, and you can't cash out. There's
   nothing for us to gain by you losing.
2. **Simple and fun.** Clean, quick, play-with-friends poker. It's poker night, not a casino floor.

Honesty note: we make **no** code-transparency claim. The repo is private and the code is all-rights-reserved,
so "open source" and "public code you can check" are out of every surface — copy, badges, screenshots, social.
The "bots can't see your cards / nothing's rigged" line stays, but it's something users take on our word: it's
only true as long as the shipped code keeps it true, so state it plainly and never dress it up as something a
user can independently verify.

## Subtitles (App Store, ~30 char)

- Simple, fun poker with friends
- Fair poker, no real money
- Simple, fun Texas Hold'em
- Poker night, no casino

## Promo line (short)

Simple, fun poker with your friends. No real money, no house, and no catch.

## App Store description

Simple, fun poker.

Downcard is No-Limit Texas Hold'em you can play with your friends in a couple of taps. Start a table, share the code, and you're dealing. That's the whole thing.

There's no real money in Downcard, and there's no house. The app never plays against you, never takes a rake, and you can't cash out, so nobody leaves the table in a worse spot than they showed up in. It's poker night, not a casino floor.

Nobody free to play? Deal in the bots. There are five of them, each plays a little differently, they're always labeled as bots, and they can't see your cards. When your friends aren't online you might wait for a public table to fill, but the bots are ready whenever you are, so you never have to wait to play.

Play a hand or play all night. Earn XP, climb the ranks, and track every hand as you go. Leave the table whenever you want and keep your chips for next time.

Simple, fun poker. Come play.

Features:
- Texas Hold'em with friends in a couple of taps
- Share a 6-character code to start a private table
- Five honest bots that play fair and can't see your cards
- No house, no rake, no cash-out, no ads
- XP, ranks, and stats to track your game
- Free on iPhone and Android

## Screenshot captions (matched to the actual six screens)

A caption has to fit the screen behind it, so each row names the screen shown. Slots 1-3 show in
search, so the honest core leads.

| # | Screen shown | Headline | Subhead |
|---|--------------|----------|---------|
| 1 | Hero table (cards on felt) | Simple, fun poker. | No real money. No house. No catch. |
| 2 | Home / public tables | Play anyone, anytime. | Sit down at a public table with other players. |
| 3 | Room code + Share invite | Poker night in your pocket. | Start a table and share the code. |
| 4 | Practice table with the BOT badge | Practice with bots. Build your game. | Five bots, each plays differently. No chips at stake. |
| 5 | Achievements | Climb the ranks as you play. | Earn XP and unlock achievements every hand. |
| 6 | Stats (level + stats) | Track your whole game. | Your level, your win rate, and every hand you play. |

Notes and pending confirmations:
- Frame 1: capitalization of "house" is your call; sentence case reads cleaner on a store page. If the
  exported hero art still carries an open-source badge/sticker, re-export without it.
- Frame 2: markets the human-multiplayer capability, not a guaranteed crowd or an instant seat, and
  says nothing about bots. Only ship this frame if public matchmaking is in the launch build (it was an
  original V1 non-goal), and don't imply a population we don't have.
- Frame 4: if the exported art carries an open-source badge/sticker, re-export without it.
- Frame 6: names your level, so only ship it if the stats screen shows level plus stats and is reachable.

## Custom product page: "Poker night with friends"

The default page sells Downcard to everyone. This one is for people who already want to play with their
own group, so it leads with the private room and drops the public-table pitch.

**Who lands here**
- The Reddit "Deal your friends in" ad (`Downcard Assets/5 Ads/Reddit/reddit-friends-*`). Point that ad
  at this page's URL instead of the default page, so the promise in the ad is the first thing they see.
- Organic search for the keywords below, once they're assigned to this page.

**Keywords to assign**
- poker with friends
- private poker
- poker night
- home game
- friends poker

None of these are in a keyword list in the repo. Add them to the app's keyword field in App Store
Connect first, since a custom page can only be assigned keywords the app already targets.

**Promotional text** (170 max)

Start a private table, share the 6-character code, and deal your friends in. No real money, no house,
and bots to fill any empty seat.

**Screenshot order** (all existing art from `Downcard Assets/1 App Store - iOS/`, no new design needed)

Captions are baked into the art, so the caption column is what each image already says.

| # | File | Caption already on it | Why it's here |
|---|------|-----------------------|---------------|
| 1 | duo1-left | Your table. Your crew. Your rules. | The friends promise, first thing in search results |
| 2 | duo1-right | (spread continues: room code card) | Shows how easy the invite is |
| 3 | 01-hero-left | Fun. Simple. Poker. No real money. No house. No catch. | The honest core, still visible in search |
| 4 | 02-hero-right | (spread continues: hand in play) | Real cards on the felt |
| 5 | duo2-left | Play. Win. Level up. | The fun of winning a hand against people you know |
| 6 | duo2-right | (spread continues: win and achievement) | |
| 7 | 05-practice-bots | Practice with bots. Build your game. | Answers "what if only two of us are free?" |

Left out on purpose: 03-play-anyone (strangers at public tables is the opposite pitch), 04-poker-night
(says the same thing as the duo1 spread), and 06/07 (progression matters less to this visitor). Keep
spreads in adjacent slots so they join up.

**Optional caption change** (needs a re-export from the design project)
- Slot 7 reads as solo practice. For this page, "Short a player? Add a bot." with the subhead "Five bots,
  always labeled, and they can't see your cards." fits better. Ship without it if you'd rather not touch
  the art.

**How to tell if it works**
Compare this page's conversion rate in App Analytics against the default page, using Reddit ad traffic
only. Give it a couple of weeks of spend before reading anything into it.

## Instagram captions

Launch post:
Downcard is here. Simple, fun poker with your friends, no real money, no house. Start a table, share the code, deal everyone in. Come play. Link in bio.

Feature post (fair bots):
Every poker app has the same problem: bots that feel like they can see your cards. Ours can't. They play off the same board you see, with no peek at your hole cards, and they're always labeled as bots. Come bluff a bot that's actually playing fair.

Feature post (no house):
There's no house at Downcard. The app never plays against you, never takes a rake, and there's no real money to cash out. We don't win when you lose. It's just poker night, made to be fun.

## Go-to phrases (reuse anywhere)

- Simple, fun poker.
- No house. No catch.
- Poker night, not a casino.
- Bots that play fair and can't see your cards.
- No real money. No pressure. Just poker.
