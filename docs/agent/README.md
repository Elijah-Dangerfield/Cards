# Cards agent system

Post-launch operating setup. Two kinds of thing:

- **Actions** = single-purpose **skills** in `.claude/skills/`. Each does one thing and is invocable on demand (`/skill-name`) *and* callable by a flow. Every action is **dual-mode**: it works standalone on the current branch and wired into a flow.
- **Flows** = **skills** that orchestrate actions on a schedule. Thin coordinators; the logic lives in the actions.

## The three loops

| Loop | Flow | Cadence | What it does |
|---|---|---|---|
| **Iterate** | `nightly-build` | nightly | intake (triage) → curate → workers → review → PR. `intake: off` works only the existing todo list. Opens a PR; never auto-ships. |
| **Incident** | `health-watch` | every 15–30 min | cheap sweep for breakage the A1–A7 alerts miss + post-deploy canary. Hands real regressions to `hotfix`. Agent is first responder. |
| **Awareness** | `daily-brief` | daily | compose the "since last brief" digest via `write-brief` and deliver it (email if a Gmail MCP is wired, else a file + PR comment). |

## Actions (skills)

| Skill | Role |
|---|---|
| `feedback-triage` | In-app feedback → routed outcome (channel-aware; see routing). |
| `observability-triage` | Sentry crashes + Grafana signals nobody reported → todo / no-action. |
| `curate-todos` | Sole curator of `docs/todo.md` — reconcile + top up. |
| `work-item` | Ship one todo as commits on `develop`. |
| `review-and-pr` | Review a branch, fix what it'd flag, open/update the PR. |
| `ship-release` | Merge the release PR, cut + watch the release. |
| `hotfix` | Incident action — rollback-first; auto-handle safe classes, escalate the rest. |
| `janitor` | Weekly craft cleanup; opens its own PR. |
| `write-brief` | Compose the since-last-brief digest. |

## Routing rule (the brain of triage)

Channel decides directive vs signal:

- **dev / beta** feedback (`release_channel` ≠ `store`) → directive → `docs/todo.md`.
- **prod bug**, urgent → incident (`hotfix`) + todo; minor → next-release todo.
- **prod feature/opinion** → the agent judges: clearly good + actionable → auto-todo (or built); big/ambiguous → `docs/backlog.md` for the owner. The brief reports which bucket.

## Autonomy line (incident path)

- **Auto-handle end to end** (fix/rollback → `ai-autofix` label → ship → log): rollbacks, flag/config reverts, test-covered one-liners. Reversible, low blast radius.
- **Auto-prepare + page the owner:** money, auth, security, migrations, no-repro, ambiguous.

## Where state lives (`docs/agent/`)

- `feedback-log.md`, `observability-log.md` — processed-signal ledgers (dedupe).
- `incident-log.md` — every health-watch/hotfix disposition (feeds the brief).
- `brief-log.md`, `briefs/` — brief window marker + rendered briefs.
- `janitor-log.md` — cleanup ledger.
- `feedback-cases/` — one investigation write-up per report.
- `in-flight.md` — workers' notes for the reviewer (transient, cleared each cycle).
- `ai-style-guide.md` — style the agents follow.

## Running one by hand

Any action or flow is a skill: invoke `/<name>` (e.g. `/health-watch`, `/work-item`, `/hotfix`). Flows are also on schedules (see the scheduled-tasks). Actions degrade gracefully when orchestration artifacts (like `in-flight.md`) are absent.
