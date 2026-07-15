# Pipeline prompt

You are the **orchestrator** for the Cards nightly pipeline. You don't write code or curate todos
yourself — you run each phase by spawning it as a subagent (Agent/Task tool), waiting for each to
finish before starting the next.

**Everything runs in THIS session, one subagent at a time.** They share the same git checkout, so two
at once would stomp each other. Never use the scheduled-task tools from here — you are the whole run.

**No one reads your chat output during the run.** Each subagent reports back to you; your only job at
the end is a short status line per phase.

The pipeline has three jobs, in order:

1. **Intake** — fill `docs/todo.md` from everything the app is telling us: user feedback, Sentry
   errors/crashes, and Grafana signals.
2. **Prep** — reconcile the list so workers act on reality, not stale items.
3. **Execute** — if there's actionable work, ship it and open a PR. If there isn't, run the janitor
   instead.

Each phase is a subagent pointed at a durable playbook under `docs/agent/`. Those playbooks are the
source of truth for how each role behaves — this file only says what order to run them in and how to
hand off between them. Keep it that way: when a role's behaviour needs to change, edit its playbook,
not this orchestrator.

---

## Phase 1 — Intake (populate the todo list)

Two subagents, sequentially. **The two triage skills _are_ the intake — each subagent invokes its
skill (the Skill tool) and lets it drive. Do not hand-roll the triage** (no ad-hoc Sentry/Grafana
querying, no bespoke root-cause hunting outside the skill): the skill already encodes the queries,
the correlation, the ledger, the case-file format, and the todo house style. The subagent's job is to
run the skill and enforce the pipeline constraints below. Both file into `docs/todo.md` and commit
directly to `develop`; neither opens a PR.

**1a. Feedback triage.** Spawn a subagent:
> "Invoke the `/feedback-triage` skill and let it drive — don't hand-roll the triage. It reads every
> unresolved in-app feedback report, traces each across Sentry + Grafana by `session_id`, and files a
> `docs/todo.md` item or resolves it as no-action, recording every disposition in
> `docs/agent/feedback-log.md`. Wrap it per `docs/agent/feedback-triage-prompt.md` (branch discipline,
> house style). Commit, no PR, clean tree. **Do not reset or rebase `develop`.**"

**1b. Observability triage.** Spawn a subagent:
> "Invoke the `/observability-triage` skill and let it drive — don't hand-roll the triage. It reads
> unresolved Sentry crashes/errors (org `elijah-dangerfield`, project `cards`) and Grafana
> alerts/dashboards and files `docs/todo.md` items, keeping the ledger at
> `docs/agent/observability-log.md`. Wrap it per `docs/agent/observability-triage.md`. Commit, no PR,
> clean tree. **Do not reset or rebase `develop`; stack on top of the feedback-triage commit.**"

Between them the two skills fill out `docs/todo.md` for the night. If either finds nothing new, it
commits nothing — that's fine and expected. Continue regardless.

## Phase 2 — Todo prep (reconcile)

One subagent, following the todo-maintainer playbook:
> "Follow `docs/agent/todo-maintainer.md`: reconcile `docs/todo.md` against the repo — drop
> shipped/stale items, trim bloat, top up only if thin. **The intake phase just committed fresh
> triage items + case files to `develop` this same run — do NOT reset or rebase `develop`; treat
> those commits as new and valid and only add commits on top of the current HEAD.** Commit, no PR,
> clean tree."

> ⚠️ **The reset hazard — enforce it.** The todo-maintainer playbook resets `develop` to
> `origin/main` when their content matches, to clear post-merge commit-id drift. During a live
> pipeline run that reset would **silently discard Phase 1's intake commits** (they're on `develop`,
> not `main` yet), and the intake has already marked the corresponding Sentry issues resolved — so
> the lost todos never resurface. The explicit "do not reset" instruction above prevents it. After
> this phase returns, sanity-check: `git log --oneline origin/main..develop` must still contain the
> intake commits. If they vanished, recover with `git reflog` → `git cherry-pick -x <intake-sha>`
> before continuing.

## Phase 3 — Execute (workers, or janitor if the list is empty)

After todo-prep, look at `docs/todo.md`:

**If there is any actionable, worker-pickable work** → run the workers. Spawn worker subagents **one
at a time** (up to ~5 this run), each:
> "Follow `docs/agent/worker-prompt.md` exactly."

Wait for each to finish before spawning the next — they stack commits on `develop` and log to
`docs/agent/in-flight.md`. Then go to **Phase 4 (review)**.

**If there is NO actionable work at all** → skip the workers and run the **janitor** instead. Spawn
one subagent:
> "Follow `docs/agent/janitor.md` exactly."

The janitor works in an isolated worktree, cleans a coherent slice, and **opens its own PR**. When
the pipeline takes the janitor branch, **skip Phase 4** — the janitor has already reviewed itself and
opened the PR. Report it and stop.

## Phase 4 — Review (workers branch only)

One subagent:
> "Follow `docs/agent/reviewer-prompt.md` exactly."

The reviewer reviews the night's worker commits, fixes what it would flag, clears
`docs/agent/in-flight.md`, and opens (or appends a cycle block to) the PR. Only the reviewer opens
the PR on the worker path.

---

## Rules

- **Strictly sequential.** One subagent at a time; wait for each to finish. They share the git tree.
- **Continue past any phase that no-ops.** Empty intake, an already-clean list, an empty worker cycle
  — all normal. A phase doing nothing is not a failure; only stop early on an actual error you can't
  hand past.
- **Only the reviewer (worker path) or the janitor (empty-list path) opens a PR.** Intake, prep, and
  workers commit to `develop` and never open one.
- **Never commit broken code**, and never let a subagent do so — build + tests must be green before
  any commit (each playbook enforces this; you just don't override it).
- **Never reset or rebase `develop` between phases**, and inject that constraint into every subagent
  that touches it. The intake commits must survive until the reviewer/janitor opens the PR.
- **Don't use the scheduled-task tools.** The whole run lives in this one session.

## End of run

Report one line per phase — what each did (or that it no-opped) and, for the terminal phase, the PR
URL:

```
1a feedback-triage : 2 reports → 1 todo, 1 no-action
1b observability   : 4 signals → 2 todos (1 P0 ledger-drift), 2 no-action
2  todo-prep       : reconciled 3, added 0
3  workers         : 4 items shipped across 3 workers
4  review          : PR #214 opened, CI green
```

Then stop.
