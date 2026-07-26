---
name: work-item
description: Ship one docs/todo.md item end-to-end as an incremental commit — implement per house standards, add tests, get build + tests green, remove the todo bullet in the same commit, log to docs/agent/in-flight.md, and push to develop. Use to pick up and ship a todo item, whether invoked ad hoc by a human on the current branch or as one of N workers driven by the nightly-build flow.
---

# Work item

Take a single `docs/todo.md` item from "open bullet" to "pushed commit" — the full loop: implement, test, build green, commit, remove the bullet, log the work, push. Everything in `docs/todo.md` is worker-pickable. Human-only items live in `docs/developer-todo.md` — **never touch that file.**

## Two modes

Decide which you're in before you start; the difference is only in branch handling and how you pick.

- **Standalone (a human ran this on the current branch).** Respect the branch you're on — it may not be `develop`, and it may already carry uncommitted or unpushed work. Do **not** checkout/pull/reset to `develop`, and do not force anything. Ship on top of what's here. Pick the todo the user named, or the top actionable item if they didn't name one. `docs/agent/in-flight.md` may not exist — that's fine; create/append it if you want a record, but never fail on its absence. You may talk to the user here (the silence convention below is for orchestrated runs).
- **Orchestrated (the nightly-build flow spawned you as one of N workers).** Exact worker behavior: you stack on `develop` alongside peer workers, a reviewer later reads your `in-flight.md` blocks and opens/updates the PR. Follow the branch discipline and silence convention below to the letter.

When it's not stated which mode you're in: if you were handed a specific item and a branch that isn't `develop`, treat it as standalone; if you were spawned with no human in the loop, treat it as orchestrated.

## Branch discipline

- **Orchestrated working branch is `develop`.** The human also works here (often via worktrees merged separately), so it is **not** disposable — never assume it only holds bot commits. Your commits **stack** on whatever's already there.
- **Never reset `develop`.** No `reset --hard`, no force-push, ever. It's the human's long-lived rolling branch — they edit on it and squash-merge to `main` when ready — so it sits ahead of `main` between merges. That drift is normal and not yours to clear.
- **Never rewrite history** (`rebase -i`, `--amend`). If a pushed commit was broken, push a `fix:` on top or `git revert` — never rewrite.
- **Never commit to `main` or open a PR.** The reviewer opens the PR in the orchestrated flow.
- **Standalone stays on the current branch.** No checkout to `develop`, no `pull --rebase` onto it. Same no-reset / no-history-rewrite / no-`main` rules apply.

## Start of run (orchestrated)

1. `git fetch origin`.
2. `gh pr list --head develop --state open --json number,url`. A PR already open is fine — keep working; multiple cycles stack onto the same PR. Don't open a new one.
3. Align `develop`: `git checkout develop && git pull --rebase origin develop`, then stack on top. Never reset it.
4. Read `AGENTS.md` for the house standards (see below).
5. Read `docs/todo.md`. All of it is worker-pickable. `docs/developer-todo.md` is off-limits.
6. Before claiming an item, check `git log origin/main..HEAD` and existing `docs/agent/in-flight.md` blocks so you don't double-pick something a peer already took.

**Standalone start** is lighter: read `AGENTS.md` for standards, read `docs/todo.md`, pick the named item (or the top actionable one), and confirm no local WIP already covers it. Skip the fetch/PR/develop-align steps.

## House coding standards — enforce, don't skim

`AGENTS.md` is the source of truth; hold the line on all of it:

- **DS-first.** Lean on design-system tokens and existing primitives in `libraries/` before hand-rolling. No stray `Color.White.copy(alpha=…)`; reusable primitives belong in `:libraries:ui`. Survey `libraries/` before building new shared infra.
- **`Catching {}`, never `runCatching`** — the repo convention, always (`runCatching` swallows `CancellationException`).
- **`DispatcherProvider`** for dispatchers; **SEAViewModel** pattern for view models.
- **No comments** — code speaks for itself per house style.
- **Conventional Commits**, subject under ~70 chars.
- Feature screens call `Screen(...)` themselves; EntryPoints just wire VM + callbacks.
- Routes must be `class`, not `data object` (a `data object` route SIGSEGVs the iOS navigator at navigate-time). Enum route args must be `@Serializable` or the nav graph crashes on iOS/Native.

Match surrounding patterns; when in doubt mirror an existing sibling file rather than inventing.

## Picking work (orchestrated cycles)

A standalone run ships the one named item and stops. Orchestrated cycles do more:

- **Target 3–6 items per cycle, biased toward substance.** Landing one cosmetic tweak and stopping wastes the cycle. Keep going until you've shipped a meaningful chunk or genuinely run out of confident picks.
- **Reach for at least one meatier item** — a feature, a multi-file refactor, a non-trivial server change. A copy fix or single-line DS swap is a fine warm-up but not enough alone.
- **Confidence gates ambition; ambition is the default.** The only real skip-reasons: the item contradicts the spec, or depends on a technical prerequisite that genuinely doesn't exist in the codebase yet (not just "tagged for Phase X"). Directional ambiguity, vague scoping, and fuzzy boundaries are all shippable with the safeguards below. "Hard to undo" is **not** a skip-reason pre-launch.
- **Treat the app as greenfield — unshipped, zero production users.** Reshaping an endpoint, adding a route, changing a schema, restructuring a shared library, or building a new tool to do the job right is in-bounds. If an item needs a `PlayerStats` field that doesn't exist, add it. Don't downscope to "blocked" because the proper fix touches the server or schema — the proper fix is the assignment.
- **Build the best thing, not the smallest change.** Greenfield removes both "hard to undo" as an excuse to skip and "smallest diff" as the default. Aim for scalable, maintainable, production-ready systems. When the right fix means restructuring or replacing existing code, do that — don't stack a minimal patch to avoid touching what's there. Minimal-first is for a hotfix, not the norm.
- **A judgement call is not a blocker — make it, ship it, flag it.** When an item needs deliberation (which library, which UX shape, which API name, which of several designs), draft 2–3 options, pick the one you'd defend in review, ship it, and make the call **loud in the Approach line** of your in-flight block (one sentence on what you chose, one on the alternative you rejected). The reviewer course-corrects; that's the safety net. Money flows and schema migrations still ship — just land them with a test and a loud note, never silently.
- **Read the linked feedback case before rewriting a bug-derived todo.** Items carry a `case docs/agent/feedback-cases/<id>.md` path in their Hints — that file holds the real root-cause diagnosis from triage. Read it before re-theorizing or rewriting; don't replace a correct diagnosis with a guess.
- **Phase tags are descriptive, not prescriptive.** "Gated on Phase 4.2" describes when the human expected the work, not an absolute blocker. If you can describe a self-contained slice without invoking the missing phase work, that slice is fair game.
- **Slicing a larger item is fine.** If you ship part of a multi-part item, rewrite the `docs/todo.md` entry to describe what's *left* (same minimum-context shape the `curate-todos` skill enforces — describe the remaining gap, don't append a changelog of what shipped). Keep the original ID. Don't delete the whole item or leave wording that claims unshipped sub-parts are still scoped.
- **After each commit, keep going** until (a) you've shipped a substantial cycle, (b) every remaining item needs a judgement call you can't confidently make, or (c) another item would touch the same code paths and risk stomping a prior commit.

### If `docs/todo.md` is thin

The `todo-maintainer` runs right before the workers and tops the list up, so you should rarely hit an empty list. **Don't self-hydrate or invent work** — that path produced cycles of low-value test churn. If you've genuinely got fewer than 3 confident picks, ship what you can do well and stop; a short or empty cycle is fine. Only exception: while slicing an item you're already shipping, if you find a concrete, cited follow-up gap in the same code path, you may add it to `docs/todo.md` in the lean format — flag it with a `**Source:** worker-added this cycle, not human-curated` line in the in-flight block.

## Per item

1. **Implement end-to-end** per `AGENTS.md` + surrounding patterns. In scope means do it — don't artificially shrink the change.
2. **Add/update tests** (`CoroutineTest` + Turbine where it fits). **For a bug fix, write the failing test that reproduces the bug FIRST** (red), then make it pass (green) — it proves you found the real cause and leaves a regression guard. If you can't reproduce it in a test, the harness is missing something — build that before the fix. If genuinely untestable, say so in the in-flight note.
3. **Green before commit — non-negotiable.** Run the build and tests locally and confirm they pass before committing: `./gradlew :apps:compose:assembleDebug` for client work, `./gradlew :apps:server:test` for server work, plus the targeted module tests for what you touched. Never commit red.
4. **One logical commit per item**, Conventional Commits subject under ~70 chars. Refer to the item by its stable ID (e.g. `feat(stats): graduate hand counters to server (PROG-1)`).
5. **Remove the item from `docs/todo.md` in the same commit.** Not optional. Fully shipped → delete the bullet. Partial slice → rewrite the bullet to describe what's left. A todo entry left in the file after its item shipped is the single most common failure of this workflow — re-check before pushing.
   - **Clean up scaffolding too:** orphan section headers with no remaining bullets, `_Shipped._` subsections, stale "Phase A / Phase B" stubs, `*(proposed YYYY-MM-DD)*` footnotes, "State of play" paragraphs. If your removal empties a section, delete its header. The doc is a punch list, not a decision history.
   - **Never add a `_Shipped._` note.** The item being gone *is* the shipped signal; the narrative lives in the commit body and in-flight block.
   - **Item IDs are stable.** A fully-shipped item retires its ID — never reuse it. A partial slice keeps the original ID.
6. **QA doc.** If the item ships a user-facing change, decide whether `docs/QA.md` needs an entry: new feature → new test entry; UX tweak → sub-bullet on existing coverage; backend/invisible → skip. Match the file's format (ID + priority emoji + platform tag + **State** / numbered steps / **Expected**); cross-reference the todo ID.
7. **If the item came from triage and you FULLY shipped it, resolve its Sentry issue.** Triage leaves the issue *unresolved* and tagged "triaged, fix pending"; closing it is tied to the real fix. The issue id is in the item's Hints (`Sentry <ID>`) or its case file. Resolve via REST with the shared token (env `SENTRY_AUTH_TOKEN`, falling back to keychain `cards-sentry-auth-token`; never echo it):
   ```
   curl -sS -X PUT "https://us.sentry.io/api/0/organizations/elijah-dangerfield/issues/<issueId>/" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"status":"resolved"}'
   ```
   A **partial** slice does NOT resolve — leave the issue open until the item is fully gone. No token? Note it in the in-flight block so the reviewer/human resolves it.
8. **Append a block to `docs/agent/in-flight.md`** (create if missing). This is your channel to the human via the reviewer — put anything you'd otherwise say out loud here:
   ```
   ## <conventional commit subject>

   **Problem:** <one sentence — the gap the todo described>
   **Approach:** <1–2 sentences — what you did and why this over alternatives>
   **Reviewer notes:** <surprising/untested/needs-second-eyes about THIS commit. "None." is fine.>
   **Deferred:** <related items you didn't do — one bullet each, with where you put it (backlog / inline comment / nothing yet). Omit if nothing applies.>
   ```
9. **Push** (`git push origin develop` in the orchestrated flow; push the current branch's upstream in standalone). If a hook fails, fix the root cause — no `--no-verify`, no `--no-gpg-sign`. If a pushed commit was broken, push a `fix:` on top or `git revert`.

## Scope boundaries

- **In scope:** do it, don't shrink it.
- **Adjacent + confident, not this commit:** note under `**Deferred:**` in the in-flight block; the reviewer triages.
- **Future thinking, clearly out of scope:** append to `docs/backlog.md` and mention under `**Deferred:**`.
- You may modify `docs/todo.md` in exactly three cases: removing an item you fully shipped, rewriting an item you partially shipped (slicing), and the thin-list hydration exception above. Otherwise it's the human's curation surface — don't reshape it.

## Silence convention (orchestrated)

**No one reads your chat output.** Stay silent — ideally zero text outside tool calls. Anything a human should see goes in `docs/agent/in-flight.md`, which the reviewer reads when writing the PR. (Standalone runs can talk to the user normally.)

## Pre-launch posture

The app hasn't launched; there are no production users. When a change touches data (migrations, schema, catalog content, persisted state), don't pad it with defensive backfill logic or "existing users won't get X" caveats — make the migration do the right thing for a fresh world. Skip those footnotes in commit bodies and in-flight notes too; they document a population that doesn't exist. Drop this the moment we ship.

## Hard rules

- **Never** touch `docs/developer-todo.md`.
- **Never** commit to `main` or open a PR.
- **Never** reset or force-push `develop`; **never** rewrite history (`rebase -i`, `--amend`).
- **Never** commit red — build + tests must pass first.
- No `--no-verify` / `--no-gpg-sign`.
- If a task is half-done when you stop, **revert its in-progress changes.** No partial commits.
- If you can't make confident progress, **exit with no commits.** Empty cycles are fine.

## End of run

- All commits pushed (to `origin/develop` orchestrated; to the current branch's upstream standalone).
- `docs/todo.md` reflects every removal/slice. **Re-scan every item you shipped this cycle — if any bullet is still there, that's a bug; push a follow-up commit that deletes it.**
- `docs/agent/in-flight.md` has one block per commit you added.
- Working tree is clean. Stray modifications mean you left work behind — resolve before stopping.

Then stop.
