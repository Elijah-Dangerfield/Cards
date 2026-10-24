# Ads dashboard

One local page that answers: how much have the ads cost, how well are they working, and what should change next.

- **Update it:** run `/ad-report` in Claude Code. It pulls each platform's numbers, reviews the creatives, writes verdicts and a "do next" action, and rebuilds the page.
- **Look at it:** start the `ads-dashboard` preview server (port 4610) and open `/dashboard/`. `/dashboard/?example` shows made-up data.
- **Real data stays local.** `data/` is gitignored because the repo is public. `example-data/` is fake and committed.
- **APIs:** what's worth applying for, with drafted answers, is in [API-ACCESS.md](API-ACCESS.md).

`build.py` merges `data/ledger.json`, `data/snapshots/*.json` and `data/report.json` into `data/dashboard.json`, the only file the page reads. The full workflow is in `.claude/skills/ad-report/SKILL.md`.
