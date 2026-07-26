# Cards agent system

Post-launch operating setup (Android live, iOS not yet). Two kinds of thing:

- **Actions** = single-purpose **skills** in `.claude/skills/`. Each does one thing and is invocable on demand (`/skill-name`) *and* callable by a flow. Every action is **dual-mode**: it works standalone on the current branch and wired into a flow.
- **Flows** = **skills** that orchestrate actions. Thin coordinators; the logic lives in the actions.

**Claude does judgment; machines do mechanics.** Claude runs the judgment / natural-language / investigation / authoring work below. Build, test, merge-on-green, cut a release, deploy, and threshold detection stay in CI / GitHub Actions / release-please / the Grafana + Sentry alerts. Don't wrap deterministic mechanics in an agent.

## The loops

| Loop | How it runs | What |
|---|---|---|
| **Iterate** | `nightly-pipeline` — scheduled nightly | autonomous dev: intake (triage) → curate → workers → review → **opens a PR, never auto-ships**. `intake: off` works only the existing todo list. (This is a dev pipeline, NOT a CI app build.) |
| **Incident** | **alert-driven, not a poll** | the A1–A7 Grafana alerts + Sentry alerts detect in real time; when a real prod problem fires, `hotfix` responds (rollback-first; auto-handle safe classes, escalate money/auth/security/ambiguous). Run `/hotfix` ad hoc too. |
| **Awareness** | `brief` — **on demand** | run `/brief` (or the manual task) whenever you want to catch up. Composes the "since last run" digest via `write-brief` and delivers it (email if a Gmail MCP is wired, else a file + PR comment). |

`janitor` runs weekly (its own PR).

## Actions (skills)

| Skill | Role |
|---|---|
| `feedback-triage` | In-app feedback → routed outcome (channel-aware; see routing). |
| `observability-triage` | Sentry crashes + Grafana signals nobody reported → todo / no-action. |
| `curate-todos` | Sole curator of `docs/todo.md` — reconcile + top up. |
| `work-item` | Ship one todo as commits on `develop`. |
| `review-and-pr` | Review a branch, fix what it'd flag, open/update the PR. |
| `hotfix` | Incident action — rollback-first; auto-handle safe classes, escalate the rest. |
| `write-brief` | Compose the since-last-run digest. |
| `janitor` | Weekly craft cleanup; opens its own PR. |
| `ship-release` | On-demand helper: merge the release PR, watch the release. (Mostly release-please + the `ai-autofix` auto-merge do this — Claude isn't required to ship.) |

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
- `incident-log.md` — every hotfix disposition (feeds the brief).
- `brief-log.md`, `briefs/` — brief window marker + rendered briefs.
- `janitor-log.md` — cleanup ledger.
- `feedback-cases/` — one investigation write-up per report.
- `in-flight.md` — workers' notes for the reviewer (transient, cleared each cycle).
- `ai-style-guide.md` — style the agents follow.

## Running one by hand

Any action or flow is a skill: invoke `/<name>` (e.g. `/nightly-pipeline`, `/work-item`, `/hotfix`, `/brief`). The nightly pipeline is scheduled; the brief is manual; `hotfix` is alert-driven / ad hoc. Actions degrade gracefully when orchestration artifacts (like `in-flight.md`) are absent.

## Still to wire (machine layer, not built)

- **Alert → `hotfix`**: route a firing critical alert to a GitHub Action (`repository_dispatch`) that invokes `hotfix` headless, so incident response is event-driven. Until then, alerts page the owner and `hotfix` is run on demand.
- **Ship on auto-merge**: enable GitHub auto-merge on the release-please PR so releases cut without a Claude step.
- **Email**: consolidate the Porkbun inboxes into Gmail/Workspace so the brief can ingest store/support mail and deliver to the inbox.
- **Phone dispatch**: Claude Code in GitHub Actions triggered by an issue comment/label from the GitHub mobile app.
