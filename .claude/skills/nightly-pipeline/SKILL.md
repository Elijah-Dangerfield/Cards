---
name: nightly-pipeline
description: Orchestrate the Cards nightly PIPELINE (autonomous dev work — feedback + observability triage → curate todos → workers → review → open PR). This is NOT a CI app build; it runs each phase as a subagent invoking its action skill. Use on the nightly schedule, or ad hoc for a full cycle. Pass "intake off" to work only the existing todo list.
---

# Nightly pipeline (flow)

The iteration loop — the autonomous dev pipeline, **not** a CI app build. You are the **orchestrator**: you don't write code or triage yourself, you run each phase by spawning it as a subagent (Agent/Task tool) that invokes an **action skill**, waiting for each to finish before the next. This flow replaces the old two-orchestrator split (full pipeline vs execute-only) — the difference is now a parameter.

**Everything runs in THIS session, one subagent at a time.** They share the same git checkout, so two at once would stomp each other. Never use the scheduled-task tools from here — you are the whole run.

**No one reads your chat output during the run.** Each subagent reports back to you; your only job at the end is a short status line per phase.

## Parameters
- **`intake: on` (default)** — run Phase 1 (triage) before building.
- **`intake: off`** — skip Phase 1 entirely; work only what's already in `docs/todo.md` (the old "execute-only" mode). Use when you want to burn down the list without re-scanning feedback/Sentry/Grafana.

## Branch discipline (inject into every subagent)
Working branch is `develop`. The human also commits here (often via worktrees merged separately), so it is **not** disposable. **Never reset or rebase `develop`** — every phase only adds commits on top of current HEAD. The intake commits must survive until the reviewer opens the PR. Sanity-check after prep that `git log --oneline <last in-flight-clear>..develop` still holds the intake commits.

## Phase 1 — Intake (skip if `intake: off`)
Two subagents, sequentially. **The two triage skills _are_ the intake — each subagent invokes its skill and lets it drive. Do not hand-roll triage** (no ad-hoc Sentry/Grafana querying): the skills already encode the queries, correlation, ledgers, case-file format, and todo house style. Both file into `docs/todo.md`, commit directly to `develop`, and open no PR.

**1a. Feedback triage.** Spawn a subagent:
> "Invoke the `feedback-triage` skill and let it drive — don't hand-roll it. It reads every unresolved in-app feedback report, traces each across Sentry + Grafana by `session_id`, files a `docs/todo.md` item or resolves it no-action, and records every disposition in `docs/agent/feedback-log.md`. Honor its post-launch routing (dev/beta = directive; prod = user signal; good actionable product asks become todos, big/ambiguous ones go to backlog for the brief). Commit, no PR, clean tree. **Do not reset or rebase `develop`.**"

**1b. Observability triage.** Spawn a subagent:
> "Invoke the `observability-triage` skill and let it drive. It reads unresolved Sentry crashes/errors (org `elijah-dangerfield`, project `cards`) and Grafana alerts/dashboards and files `docs/todo.md` items, keeping the ledger at `docs/agent/observability-log.md`. Commit, no PR, clean tree. **Do not reset or rebase `develop`; stack on the feedback-triage commit.**"

If either finds nothing new it commits nothing — fine and expected. Continue regardless.

## Phase 2 — Prep (reconcile the list)
One subagent:
> "Invoke the `curate-todos` skill: reconcile `docs/todo.md` against the repo — drop shipped/stale items, trim bloat, top up only if thin. The intake phase just committed fresh triage items + case files to `develop` this same run — do **not** reset or rebase; treat those as valid and only add commits on top of HEAD. Commit, no PR, clean tree."

## Phase 3 — Execute (workers, or janitor if the list is empty)
Look at `docs/todo.md`:

**If there is any actionable, worker-pickable work** → run workers. Spawn **one at a time** (up to ~4–5 this run), each:
> "Invoke the `work-item` skill in orchestrated mode: pick the top actionable todo, ship it as commits on `develop`, log to `docs/agent/in-flight.md` for the reviewer. Build + tests green before every commit. **Do not reset or rebase `develop`.**"

Wait for each to finish before spawning the next (they stack commits + log to in-flight.md). Then go to Phase 4.

**If there is NO actionable work at all** → skip workers, run the **janitor** instead:
> "Invoke the `janitor` skill exactly."

The janitor works in an isolated worktree, cleans a coherent slice, and **opens its own PR**. When the flow takes the janitor branch, **skip Phase 4** — the janitor reviewed itself and opened the PR. Report it and stop.

## Phase 4 — Review (workers branch only)
One subagent:
> "Invoke the `review-and-pr` skill exactly. Review this run's worker commits, fix what you'd flag, clear `docs/agent/in-flight.md`, and open (or append a cycle block to) the develop → main PR. If in-flight.md is missing, fall back to the commit-scope anchor. Don't merge."

Only the reviewer opens the PR on the worker path.

## Shipping
This flow **opens** a PR; it does not ship. Post-launch, shipping is deliberate: the `ship-release` skill (run by the owner or a morning schedule) merges the release PR and cuts the release. Autonomous overnight work should never auto-ship to live users — it batches to a PR the owner promotes.

## Rules
- **Strictly sequential.** One subagent at a time; they share the git tree.
- **Continue past any phase that no-ops.** Empty intake, a clean list, an empty worker cycle — all normal. Only stop early on a real error you can't hand past.
- **Only the reviewer (worker path) or the janitor (empty-list path) opens a PR.**
- **Never commit broken code**; build + tests green before any commit (each action skill enforces this).
- **Never reset or rebase `develop`.** Inject that into every subagent.
- **Don't use the scheduled-task tools.** The whole run lives in this one session.

## End of run
One line per phase — what each did (or that it no-opped) and, for the terminal phase, the PR URL:

```
1a feedback-triage : 2 reports → 1 todo, 1 no-action
1b observability   : 4 signals → 2 todos (1 P0), 2 no-action
2  curate-todos    : reconciled 3, added 0
3  workers         : 4 items shipped across 3 workers
4  review-and-pr   : PR #214 opened, CI green
```

Then stop.
