---
name: health-watch
description: The incident loop for Cards — a cheap, frequent sweep for prod breakage the threshold alerts miss, with a tighter post-deploy canary. Confirms real regressions, dedupes against the ledgers, filters known-benign noise, and hands anything real to the hotfix skill (rollback-first). Use on a short schedule (every 15–30 min) or ad hoc to ask "is anything on fire right now?".
---

# Health watch (flow)

The **first responder**. You run frequently and cheaply. Most runs find nothing and exit in seconds. When you find something real you hand it to the `hotfix` skill, which decides whether to auto-fix or escalate. The owner is escalation of last resort, not the first line.

You are the machine complement to the real-time alerts (A1–A7 → the owner's phone via the Grafana critical channel). Those catch the hard thresholds. **Your job is the softer signals they miss** — a new crash cluster, an error-rate drift, a purchase-success dip, a bad release.

## Cost discipline
Cheap when quiet. Do the light checks first; only spend tokens investigating when a light check trips. Do **not** re-investigate a signal already dispositioned in `docs/agent/observability-log.md` or `docs/agent/incident-log.md` — dedupe first.

## 1. Light sweep (every run)
- **Firing alerts:** `alerting_manage_rules(operation='list', states=['firing','pending'])`. Any money/infra/reliability rule firing is a real signal.
- **New Sentry issues:** unresolved issues in project `cards` first-seen since the last run (check `docs/agent/incident-log.md` for the last run's timestamp). Skip anything already in the observability ledger or matching a known-benign class in `docs/wiki/observability.md` (emulator/side-load ANR, banned-403, user-cancellations, one-off net.backend_unreachable).
- **Key prod rates (Loki/Prom, last 30–60m):** crash-free trend, `purchase.failed` vs `purchase.completed`, client `net.backend_unreachable` volume, server error/fatal count.

If everything is clear → append a one-line "clear" heartbeat to `docs/agent/incident-log.md` (with the timestamp so the next run knows the window) and **stop**.

## 2. Canary mode (only after a recent deploy)
Check whether a deploy landed in the last ~2h: a new GitHub Release, a `chore: release main` merge, or a Grafana deploy annotation. If so, watch **harder** and compare against the pre-deploy baseline:
- crash-free % on the **new** `release`/version vs the prior version,
- error-rate and `purchase` success on the new version,
- any Sentry issue whose **first-seen version == the just-shipped version**.

A regression that **correlates with the new version** is the strongest "the release broke something" signal — and the safest response is a **rollback**, not a forward-fix. Hand it to `hotfix` framed as a rollback candidate.

## 3. Investigate + act
For any real signal:
1. Confirm it's prod-affecting and not known-benign. Establish blast radius (how many installs/users, one-off vs sustained).
2. Invoke the **`hotfix`** skill with the signal + your findings. It owns the decision:
   - **Auto-handle** (rollback / flag-flip / test-covered one-liner) → it fixes, ships via the `ai-autofix` auto-merge label, logs.
   - **Escalate** (money/auth/security/ambiguous/no-repro) → it prepares the PR and pages the owner through the critical channel.
3. Record the disposition in `docs/agent/incident-log.md` (date · signal · root cause · action: auto-fixed/rolled-back/escalated · release ref) so the next run dedupes and the daily brief can report it.

## Guardrails
- **Dedupe is mandatory** — never re-handle a signal already in the incident or observability ledger; re-open only if materially worse.
- **Known-benign is never an incident** (see `docs/wiki/observability.md`).
- **You detect and delegate; `hotfix` acts.** Don't write code or ship from here — invoke the skill so the autonomy line lives in one place.
- **Read-mostly:** your only writes are `docs/agent/incident-log.md` (and whatever `hotfix` does when you call it).

## End of run
One line: `clear` (with the window), or `N signals → M auto-handled, K escalated` with the incident-log refs.
