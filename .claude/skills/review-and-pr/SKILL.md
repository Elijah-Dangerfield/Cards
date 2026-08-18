---
name: review-and-pr
description: Review recent commits like a staff engineer, fix what you'd flag (tests, DS drift, Catching{} not try/catch, DispatcherProvider, inline strings, @Preview, no comments, secrets), then open or update the PR. Reads docs/agent/in-flight.md to scope the review when present but works fine without it. Use to review a branch and open a PR, ad hoc or as the review phase of the nightly-pipeline flow.
---

# Review and PR

Review like a thoughtful staff engineer, fix what you'd flag in code review, and open (or update) the PR. Designed to run unattended as the review phase of the nightly-pipeline flow, but works interactively on any branch too.

## Two modes

Detect which one you're in at the start; the rest of the procedure branches only where noted.

- **Orchestrated (nightly).** Working branch is `develop`. Overnight workers stacked commits and logged what they did in `docs/agent/in-flight.md`. You review the run's scope against the previous cycle's anchor, clear the in-flight log, and open/append a PR from `develop` into `main`. This is the exact reviewer behavior below.
- **Standalone (ad hoc).** The user fires this on whatever branch they're on — which may not be `develop` — with unpushed commits and/or uncommitted working changes. Review the working diff plus any unpushed commits on the *current* branch against its base, fix what you'd flag, and open a PR appropriate to that branch. Don't assume the develop→main setup, don't touch `in-flight.md`, and don't require it.

**`docs/agent/in-flight.md` is optional in both modes.** It's a scoping aid, not a dependency. When it's missing or empty, fall back to the commit scope from the base anchor and reconstruct each change's intent from its diff. Never error out or stop just because the file is gone.

**No one reads your chat output on a nightly run.** Anything for the human goes in the PR body. On a standalone run, a one-line report at the end is fine.

## Start of run

### Orchestrated

1. `git fetch origin && git checkout develop && git pull --rebase origin develop`.
2. Read `AGENTS.md`.
3. **Establish this run's review scope.** When multiple pipelines run in one night they stack onto the same open PR, so `origin/main` no longer marks where the last cycle ended (main only advances on merge). Anchor instead on the previous cycle's reviewer boundary — the most recent `chore: clear nightly in-flight log` commit:
   ```
   BASE=$(git log --grep="clear nightly in-flight log" --format=%H -n1 origin/develop)
   BASE=${BASE:-origin/main}   # first cycle of this PR → fall back to main
   ```
   Review `git log --oneline $BASE..origin/develop`. If `docs/agent/in-flight.md` is present it holds just this run's blocks (the prior reviewer removed its own) — use it to scope and understand intent. If it's **missing or empty**:
   - Zero commits in `$BASE..origin/develop` → **exit**.
   - Commits but no log → reconstruct intent from the diffs and review those commits anyway; flag the missing log in "Heads up." This is normal, not an error.
4. `gh pr list --head develop --base main --state open --json number,url`. If one exists, **append** a new cycle block to its body and push your commits — don't open a duplicate, don't rewrite prior cycles' notes. See "Closing out" for the append mechanics.

The human also commits to `develop` (usually via worktrees merged separately), so you may encounter human WIP — treat unfamiliar commits as intentional; never force over or discard them.

### Standalone

1. `git fetch origin` and read `AGENTS.md`.
2. Note the current branch (`git rev-parse --abbrev-ref HEAD`) and its base. The base is the branch this work forks from — usually `develop`, occasionally `main`; infer it from `git merge-base` against both and pick the nearer ancestor, or ask if genuinely ambiguous.
   ```
   BASE=$(git merge-base HEAD origin/develop)
   ```
   Review scope is everything since that base: `git log --oneline $BASE..HEAD` for committed-but-unpushed work, **plus** `git diff` and `git diff --cached` for uncommitted work. Review uncommitted changes in place — don't demand they be committed first.
3. Don't read or clear `docs/agent/in-flight.md`; it's an orchestration artifact. Reconstruct each change's intent from its diff and the commit messages.

## Per-commit review

For each in-flight block when present, otherwise for each commit in scope (`$BASE..HEAD` / `$BASE..origin/develop`) and any uncommitted diff:

1. `git show <sha>` (or read the working diff for uncommitted changes).
2. Did the change solve the stated (or evident) problem? Consider one alternative — simpler/safer?
3. Check for:
   - Tests missing, thin, or wrong.
   - **DS drift** — `Color.White.copy(alpha=…)`, one-off `RoundedCornerShape(N.dp)`, raw `Color(0xFF…)` for semantic surfaces. Should be `AppTheme.colors.surface*`, `Radii.*.shape`. (`AGENTS.md` → Design system.)
   - `try { } catch` instead of `Catching { }`.
   - Direct `Dispatchers.{Main,IO,Default,Unconfined}` instead of `DispatcherProvider`.
   - **Inline user-facing strings** — `Text("Hi")`, `placeholder = "Email"`, snackbar / dialog / error copy hardcoded at the callsite. Should be `stringResource(Res.string.foo)` from `:libraries:resources`. (`AGENTS.md` → Coding Guidelines.) Glyph-only typography (✓, —, emoji), preview-only sample data, and server-supplied error strings are fine.
   - Public screen-level composables in `:features:*:impl` missing `@Preview`.
   - Comments that shouldn't exist (project convention: none).
   - **Migration safety (both platforms are live)** — Android (Play) and iOS (App Store since 2026-07-23) both have a production population, so migrations and data changes MUST handle existing users on either platform; don't strip backfill / compat logic as noise, and flag any migration that assumes a fresh world. Nothing is greenfield anymore.
   - Scope creep, dead code, unused imports, leftover `println`/debug logs.
   - Secrets in code, unsafe deserialization, injection smells.
   - Conventional-commit type matches the change (`feat:` only for user-visible new capability).

## Deferred items

Each in-flight block may have `**Deferred:**` entries (orchestrated mode only — skip this section if there's no in-flight log). For each:

- **Should've been in scope** — do it now as a new commit; fold into the relevant Shipped bullet.
- **Real follow-up, future thinking** — ensure it's in `docs/backlog.md`; mention once in "Heads up."
- **Needs a human call** — surface in "Heads up" with enough context to decide.

Workers tend to defer conservatively — expect some belong in the PR.

## `developer-todo.md` awareness

Glance at `docs/developer-todo.md` (human-only TODOs: Device QA, Dashboard config, Content writing, Deferred product decisions, GitHub settings, Secrets). Only for items **this diff touches**, add a one-line "Heads up" naming the entry the human still owes. Don't restate every entry.

You may **append** a one-line entry to `developer-todo.md` if a commit creates a new human-only follow-up (e.g. needs hardware verification, introduces a dashboard dependency). Use the existing checkbox format under the right subsection (create the section if missing). Flag the addition once in "Heads up." Never edit/delete existing entries.

Split rule:
- Standing item across cycles → append to `developer-todo.md` + Heads up.
- Dies the moment the human acts on it this cycle → Heads up only.

## Authority

You may:
- **Fix small issues directly** — missing tests, hardcoded color → token, drop a comment, tighten a name. New commit per fix, conventional-commit message. Fold into the relevant Shipped bullet.
- **Revert** a commit that shouldn't ship — wrong approach, broken assumption. `git revert <sha>`, one-line reason in "Heads up." (Standalone: if the bad change is uncommitted, just don't stage it and say so.)
- **Append to `docs/backlog.md`** for out-of-scope follow-ups; mention once in "Heads up."

You may not:
- **Rewrite history** — no `rebase -i`, `--amend`, force-push. The human reviews linear history. This holds in both modes.
- Pick up new `docs/todo.md` items. You're a reviewer, not a worker.
- **Merge the PR.**
- Inflate the PR-title commit type based on intermediate commits — set it from the final shipped diff.

If you revert everything, still open/update the PR with "Shipped: nothing kept this cycle" + what was rejected and why under "Heads up." Silence is worse than a transparent zero.

## Build + tests

Before opening/updating, run the checks that cover this diff:
- `./gradlew :apps:compose:assembleDebug`
- `./gradlew :apps:server:test`

Broken: fix as a small commit, or revert the breaking commit. Don't knowingly push a red PR.

## Closing out

### Orchestrated

1. `git rm docs/agent/in-flight.md && git commit -m "chore: clear nightly in-flight log"` — its content moves into the PR body. (If the file was already absent, skip this step; there's nothing to clear.)
2. `git push origin develop`.
3. Open or update the PR (base `main`, head `develop`) per the body rules below.

### Standalone

1. Commit any fixes you made with conventional-commit messages (don't `--amend` the user's existing commits). Leave the user's own uncommitted work committed only if that's clearly the intent — otherwise stage your review fixes as their own commit and leave the user's WIP as you found it, noting it in the report.
2. Push the current branch: `git push -u origin <current-branch>`.
3. Open a PR from the current branch into its base with a single cycle block body. Follow the same title/body rules below.

### PR body — cycle blocks

Every cycle writes its own `## Cycle <YYYY-MM-DD>` block. **If the PR body already has a block for today's date** (a same-night follow-on run), suffix yours so the two stay distinct: `## Cycle <YYYY-MM-DD> (run 2)`, `(run 3)`, … counting existing same-day blocks +1. **Talk only about commits your run reviewed/added** — never restate prior cycles, never rewrite their notes. If a previous cycle's Heads up turns out wrong or outdated, leave it; the human resolves it on merge. Your scope is the diff since the last cycle.

**First cycle of an open PR (none open yet) — create:**

```
gh pr create --base <base> --head <head> --title "<type>: <short summary>" --body "$(cat <<'EOF'
## Cycle <YYYY-MM-DD>

### Shipped
- <plain-English line per item — what changed and why. No shas, no `feat:` prefixes. Group related items into one bullet if they tell one story.>

### Heads up
- <only what needs the human's eyes: visual deltas, scope calls, backlog additions, skipped todos, untested paths to QA by hand, developer-todo.md ties, a missing in-flight log>
EOF
)"
```

**Subsequent cycle (PR already open) — append:**

Read the current body, append a new cycle block, write it back. Never `--body` with just your new section — that overwrites prior cycles.

```
existing=$(gh pr view <number> --json body --jq .body)
# If $existing already has a "## Cycle <today>" block, use "## Cycle <today> (run 2)" etc.
addition=$(cat <<'EOF'

## Cycle <YYYY-MM-DD>

### Shipped
- ...

### Heads up
- ...
EOF
)
gh pr edit <number> --body "${existing}${addition}"
git push origin <head>
```

Omit `### Heads up` (and the cycle block entirely if nothing shipped) when there's nothing for the human to act on — empty sections are noise.

**Cycle block rules** — the human reads this on their phone:
- One screen scroll per cycle. If it doesn't fit, cut.
- **Write for a dev who hasn't read the todo doc, the decisions log, or the architecture eval.** Assume they read the *last* PR but nothing else and don't remember internal vocabulary. Every name must be self-explanatory or carry a parenthetical that explains it. No internal IDs (achievement enums, productIds), no phase refs (`§B0`, `Phase 4.2`), no spec section refs, no todo bullet names, no class/field names not introduced this cycle. Translate each into the behaviour it produces.
  - **Bad:** *"The §B0 event-log producer is wired, but `GameSession.id` is process-local — the persisted `code → session_uuid` map lands with §B1."*
  - **Good:** *"Multiplayer rooms now persist every gameplay event to a durable log before broadcasting, so a server crash mid-hand no longer loses history. Server restart still mints a new internal ID for an existing room — fine until persisted membership lands; flagging so you remember the seam."*
  - Same for product names: "the 'Pot Magnet' title (awarded for winning a 5K-chip pot)" not "POT_5000".
- Plain English, not commit log. No short-shas, no conventional-commit prefixes, no "Worker did X."
- One line per item. If it needs a paragraph, it's probably "Heads up."
- Group commits that tell one story into one bullet.
- "Heads up" is for things needing eyes, not a changelog. Skip CI status, skip "we considered X."

Example body after two cycles:
```
## Cycle 2026-05-26

### Shipped
- Rank detail's "Play with real opponents" card is now actually tappable for anonymous users.
- XP details page is now the Stats page — XP is one section above Lifetime, ready for more stats to land on top.

### Heads up
- RankDetail claim card outer radius shifted 20→10dp to match Profile's card. Worth eyeballing.

## Cycle 2026-05-27

### Shipped
- Home and Shop chip pills sit at the same screen coordinates via a shared BalancePillSlot.

### Heads up
- Filed a backlog entry: 11 more `RoundedCornerShape(16.dp)` literals could swap to `Radii.R700.shape`.
```

**PR title rules:**
- Title drives release-please's next version (PRs squash-merge into `main`): `feat:` → minor, `fix:`/`perf:` → patch, `feat!:`/`BREAKING CHANGE:` → major, others → no bump.
- **First cycle sets the title.** Subsequent cycles only upgrade it — `fix:` → `feat:` if this cycle ships a user-visible new capability, anything → `feat!:`/`BREAKING CHANGE:` if you introduce a breaking change. Never downgrade. Same tier or weaker → leave the title alone.
- Pick the type that reflects the user-visible truth of *the combined PR diff*, not what an individual worker commit says.
- Summary under ~60 chars, lowercase (commitlint rejects capitalized subjects).

## Wait for CI

`main` requires `Build + test`, `Server tests`, `Validate PR title`. Your local build covers the first two — CI failure usually means commitlint or runner flake.

Poll once at ~15 minutes: `gh pr checks <number>`.

- All green: done.
- `Validate PR title` red: fix with `gh pr edit <number> --title "..."`.
- Build/Server tests red: read `gh run view <run-id> --log-failed`. Obvious flake (Konan cache, network) → `gh run rerun <run-id>` once. Real failure → fix as a new commit. Can't fix confidently in ~10 minutes → leave red and call it out under "Heads up."
- Still pending after 15 minutes: note "CI still running at hand-off" under "Heads up" and exit.

## Done

Stop after the PR is open/updated and one CI status pass. **Don't merge** — the human merges; merge triggers Fly deploy if `apps/server/**` changed. Never rewrite history or force-push on the way out. Report one line: the PR URL and its state (green / CI pending / needs a human on X).
