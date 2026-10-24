# Ads dashboard

One local page that answers: how much have the ads cost, how well are they working, and what should change next.

Run `/ad-report` in Claude Code to update it. It pulls each platform's numbers, compares them to normal ranges in `benchmarks.json`, reviews the video, writes a verdict per ad and one "do next" action, and rebuilds the page.

To look at it, start the `ads-dashboard` preview server (port 4610) and open `/dashboard/`. The Links page lists the tracked link for every ad and profile. `/dashboard/?example` shows made-up data.

Real data stays in `data/`, which is gitignored because the repo is public. `example-data/` is fake and committed. Which ad platform APIs are worth applying for, with drafted answers, is in [API-ACCESS.md](API-ACCESS.md).

`build.py` merges `data/ledger.json`, `data/snapshots/*.json` and `data/report.json` into `data/dashboard.json`, the only file the page reads. The full workflow is in `.claude/skills/ad-report/SKILL.md`.
