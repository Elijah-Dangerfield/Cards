---
name: ad-report
description: Pull spend and results for every Downcard ad (TikTok, Instagram/Meta, Reddit) plus App Store installs, review the ad creatives, decide keep/change/stop per ad, and refresh the local ads dashboard with one "do next" action and ready-to-paste Claude Design prompts for the next variant. Use when the owner asks how the ads are doing, what they've spent, what's working, what to try next, or says "ad report", "check my ads", "update the ads dashboard". Also use when a new ad goes live, to add it to the ledger.
---

# Ad report

The owner wants to glance at one page and know three things: **how much has gone out, how well it's working, and what to do next.** This skill produces that page. Machines do the arithmetic (`marketing/ads/build.py`); you do the reading, the judgment and the writing.

## Where things live

| Path | What | Committed? |
|---|---|---|
| `marketing/ads/dashboard/` | The page (static HTML/JS, no build) | yes |
| `marketing/ads/build.py` | Merges ledger + snapshots + report into `dashboard.json` | yes |
| `marketing/ads/example-data/` | Fake numbers for working on the page | yes |
| `marketing/ads/data/` | **Real** ledger, snapshots, report, creative strips, CSV imports | **no, gitignored** |

The repo is public. Spend, budgets and account IDs stay in `marketing/ads/data/`. Never copy real numbers into a committed file, commit message or PR.

## 1. Read the ledger

`marketing/ads/data/ledger.json` lists every ad: `id`, `platform` (`tiktok` | `instagram` | `reddit`), `name`, `campaign`, `status`, `start`/`end`, `budget {type, amount}`, `creative {kind, file, hook}`, `link`, `campaignToken`, `targeting`, `managerUrl`.

If an ad manager shows a campaign the ledger doesn't have, add it (ask the owner for the creative file path if it isn't obvious). If `data/` doesn't exist yet, create it and start from `example-data/ledger.json`'s shape with real entries only.

## 2. Pull lifetime totals per ad

Read **lifetime** numbers (not "last 7 days") so snapshots stay cumulative. For each ad record `spend`, `impressions`, `clicks`, and for video: `videoViews`, `views2s`, `views6s`, `completions` (100% views), `avgWatchSec`. Use `null` for anything the platform doesn't report, never `0`.

**TikTok** (Claude in Chrome, account `managerUrl`): Campaigns → Ad tab, date range = lifetime. Destination clicks are the "Clicks (destination)" column. For video metrics add the columns (Custom columns → Video engagement: 2-second views, 6-second views, 100% video views, average play time) or use Video insights.
- The Ads Manager drops fast typed input. Paste via `pbcopy` + cmd+v.

**Instagram boosts** (Claude in Chrome, logged in as @nightjarlabs): instagram.com → Insights icon in the sidebar → **Ad tools** (`instagram.com/ad_tools/`) lists every boost with views, website visits, spend and audience. Each one's **View insights** has the full set: views, reach, 3-second plays, initial plays, link clicks, website visits, cost per visit, spend, duration, audience and top locations. Meta reports 3-second plays, not 2-second views: record them as `views3s`. Use link clicks as `clicks`.

**Meta Ads Manager** (adsmanager.facebook.com): only for ads created there. The owner's personal Meta login has no ad history, so boosts won't show up here.

**Reddit**: Claude in Chrome blocks reddit.com outright. Ask the owner to export a report (Reddit Ads → Reports → lifetime, ad level → Export CSV) into `marketing/ads/data/imports/reddit/`, then read the CSV.

**Screenshots from the owner** are a fine source too. Record exactly what's visible, note the date range shown (a 7-day view is not lifetime), and put anything missing in the report's `gaps` rather than guessing.

**Any platform as a fallback**: a CSV export dropped in `data/imports/<platform>/` works the same way. Read the newest file; note its date as the snapshot source.

**Installs** (Claude in Chrome, App Store Connect → Apps → Downcard → App Analytics → Acquisition → Campaigns): App Units per campaign token, lifetime. Match tokens to ledger `campaignToken`. Numbers lag about a day, and Apple hides a campaign until at least 5 Apple Accounts installed through it, so a small ad can read "not enough data" for a while. An ad without a `campaignToken` gets `installs: null`, and the report should say so plainly, because cost per install is the number that matters.

Write `marketing/ads/data/snapshots/<YYYY-MM-DD>.json`:

```json
{ "date": "2026-10-26", "source": "what you read and where", "ads": { "<ad id>": { "spend": 0, "impressions": 0, "clicks": 0, "videoViews": null, "views2s": null, "views6s": null, "completions": null, "avgWatchSec": null, "installs": null } } }
```

Same-day reruns overwrite that day's file.

**Every new ad's link needs a campaign token**, or its installs can't be counted. Build it as the App Store (or custom product page) URL plus `pt=120164045&ct=<ad id>&mt=8`, e.g. `https://apps.apple.com/us/app/downcard-poker-with-friends/id6788423648?ppid=<page id>&pt=120164045&ct=tiktok-friends15s-v1&mt=8`. `pt` is Nightjar Labs' provider token (public, it's in every link); `ct` is up to 30 characters, use the ledger `id`. UTM tags do nothing on App Store links; ignore the ad platforms' UTM warnings.

## 3. Review the creatives

For each ad whose creative has no notes yet, or whose numbers moved enough to change the story:

- **Video**: `ffmpeg -y -i <file> -vf "fps=1,scale=180:-1,tile=8x2:padding=6:color=0x1a1a19" -frames:v 1 -q:v 3 marketing/ads/data/creatives/<id>.jpg` for the dashboard strip, and a larger sheet in `/tmp` (`fps=1,scale=360:-1,tile=5x3`) to actually look at. Read it second by second.
- **Image**: look at the file itself.

What to look for, in order of how much it moves results:
1. **The first second.** It decides the scroll. Is something happening, or is it setup?
2. **Where the payoff lands.** The fun part (friends joining, a win) should be early, not in the last fifth.
3. **Text on a phone.** Anything smaller than a headline is unreadable in the feed.
4. **Pace.** Headlines holding 2 to 3 seconds read as slow for TikTok.
5. **The ask.** Is "Come play." / the CTA big and on screen long enough?

Tie every note to retention when you have it: hook rate (2s views ÷ impressions) says whether the opening works, hold rate (6s ÷ 2s) whether the middle does. Say where people leave, not just what you'd change.

## 4. Judge, then write `report.json`

Per ad, one verdict with a one-sentence reason that cites a number:

| Verdict | When |
|---|---|
| `wait` | Under about $30 spent, under 1,000 impressions, or live under two days. Don't judge noise. |
| `keep` | Cheapest cost per install so far, or under the owner's target if one is set. |
| `iterate` | Installs are fine but the creative leaks: hook rate under ~25% or hold rate under ~30%. Re-cut, don't kill. |
| `kill` | After $50+, cost per install over 2x the best ad, or CTR under 0.5% after 5,000 impressions. |

These thresholds are starting points for a small budget. Say so when a call is close.

Then write `marketing/ads/data/report.json`:

- `headline`: one sentence, the state of things in plain words.
- `gaps`: `[{ "what", "fix" }]` for every hole in the numbers (unknown spend, a non-lifetime date range, links without campaign tokens). The dashboard shows these right under "Do next", so a partial total never reads as the whole story.
- `nextAction`: **one** action with `title` and `why`. The single highest-value move (shift budget, re-cut an opening, add a campaign token, launch a variant). Not a list.
- `verdicts`: `{ "<ad id>": { "verdict", "why" } }`
- `creativeNotes`: `{ "<ad id>": { "strip": "creatives/<id>.jpg", "dropOff", "notes": [...] } }`
- `experiments`: one to three `{ title, hypothesis, designPrompt }`. Each tests **one** change the data points at. The `designPrompt` is pasted straight into Claude Design, so make it complete: format (15s, 1080x1920 for TikTok/Reels), what Downcard is, the exact shot order and headlines, the real app screens and the warm yellow-to-teal gradient, the end card with "Come play." as the largest text, and the guardrails below.

**Copy and prompt guardrails.** Voice is warm, simple, sincere (`docs/marketing-copy.md`). Never imply real money, cash prizes, winnings or a casino. "No real money. No house." is the honest core. Never claim anything the app doesn't do.

## 5. Build and show it

```bash
python3 marketing/ads/build.py
```

Then open the dashboard with the `ads-dashboard` preview server (`.claude/launch.json`, port 4610) at `/dashboard/`. The example data is at `/dashboard/?example`. Check it renders and the "do next" card says what you meant.

## Guardrails

- **Read-only on ad accounts.** Never publish, pause, edit, change budgets or targeting in any ad manager unless the owner asks in this conversation. Editing an ad in review restarts review.
- **No new accounts, terms or applications** on the owner's behalf. Draft them; the owner submits.
- **Data stays local.** Everything real goes in `marketing/ads/data/`.

## End of run

Two or three lines to the owner: total spent, installs and cost per install, the "do next" action, and the dashboard link. The page carries the rest.
