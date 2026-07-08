# Post-launch

Committed work we intend to do, but **not** before the V1 launch. Distinct from
[`backlog.md`](./backlog.md) (someday/maybe ideas we may never do) and from
[`todo.md`](./todo.md) (the launch punch list). When launch is behind us, items graduate
from here into `todo.md`.

Each item carries enough context to pick up cold. Append; delete when graduated or dropped.

---

## Anti-abuse

### App attestation (Play Integrity / App Attest)
Decided **no for V1** (2026-06-19). Apple App Attest + Google Play Integrity let the server
confirm a request comes from a genuine, untampered build on a real device — real anti-fraud
value on sensitive endpoints (purchase verification, wallet sync, achievement grants). Deferred
because chips aren't cash-out-able, so the cheating payoff is low, and attestation adds setup cost
plus a small legitimate-user failure rate (rooted devices, attestation outages). **Revisit if
backend abuse from forged clients materializes.** If adopted: a server gate on the sensitive routes
+ per-platform client integration.

### Automated ban sweep
Manual banning is the V1 model (triggers + enforcement live in `developer-todo.md` / `todo.md`).
Post-launch, add a **weekly sweep** that flags obvious bad actors above a confidence threshold
(e.g. clear chip-dumping / collusion patterns) and auto-bans the unambiguous ones; everything below
the bar surfaces for manual review rather than auto-acting. Pairs with the reporting feature below.

### In-app reporting + report-threshold auto-ban
Let players report another player (abusive name / chat / emotes, suspected collusion). Post-launch,
once reporting exists, add a rule: **≥ 3 reports against one account within 72 hours → auto-ban**
(reviewable / reversible via the same appeal email as manual bans). Feeds the sweep above.

## Accounts

### Auto-trigger the inactivity-based orphan sweep
Opportunistic orphan deletion is shipped (`DefaultOrphanInstallSweep`, fires on `/v1/me` when a
device re-binds to a different active anon, with the no-purchase / no-meaningful-XP guards from
`decisions.md` 2026-06-19). The ≥-1-year inactivity sweep is also built (`DefaultOrphanAnonymousSweep`,
exposed at `POST /v1/admin/sweep-anonymous-users`) — but it only runs when something hits the route.
Post-launch, wire an automatic trigger (Fly scheduled task, cron, GitHub Actions cron) so the sweep
runs without a manual kick. Low priority: orphan rows are cheap, and the conservative-by-design
guards (no purchases, no high XP, no active room seat) mean a missed sweep just leaks rows, never
deletes someone's progress.

*(The remote-config / feature-flag system that used to be tracked here shipped 2026-06-26 and
graduated to [`wiki/remote-config.md`](./wiki/remote-config.md).)*
