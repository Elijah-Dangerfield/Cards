# Ship prompt (morning finisher)

You run **once in the morning**, after the overnight pipeline cycles have stacked their work onto a
single PR. Your job: take that PR from "green-ish" to **shipped** — get CI green, merge it, promote
the server to prod, and cut the TestFlight beta. You are the last mile; nobody is watching, so be
careful and finish the job.

**Repo:** `/Users/elijahdangerfield/Workspace/Cards` (slug `Elijah-Dangerfield/Cards`). You'll work
across `develop` (to fix CI) and `main` (to ship).

## 1. Find the PR

```
gh pr list --base main --head develop --state open --json number,url,title,headRefName
```

- **Exactly one `develop → main` PR** → that's your target.
- **None** → the overnight run produced nothing to promote (e.g. only the janitor ran — its PRs
  target `develop`, not `main`). **Report that and stop.** Do not invent a release. If a green
  janitor/`ai-autofix` PR into `develop` is open, leave it — it merges on its own path; just note it.
- **More than one** → pick the `develop → main` one; mention the others in your report.

## 2. Get CI green (keep on it until it is)

Required checks on `main`: **Build + test**, **Server tests**, **Validate PR title**.

```
gh pr checks <n>
```

- **Green** → go to step 3.
- **Red** → fix it, don't wait it out:
  - `gh run view <run-id> --log-failed` to see the failure.
  - **Fix at the source on `develop`** (commit + push) — a commitlint/title failure is a title edit
    (`gh pr edit <n> --title "..."`); a real build/test failure is a code fix. An obvious flake
    (Konan cache, network, runner hiccup) → `gh run rerun <run-id>` **once**.
  - Re-poll every ~1–2 minutes (short polls, not long sleeps) until all required checks pass.
- **Can't get it green** after honest effort → **stop and report. Never merge a red PR.**

## 3. Merge — with a merge commit

Only once every required check is green:

```
gh pr merge <n> --merge
```

- **`--merge` (a real merge commit), not squash or rebase** — owner preference for the nightly PR.
- **Do not delete the `develop` branch** (`develop` is permanent; never pass `--delete-branch`).
- Confirm: `gh pr view <n> --json state,mergedAt` shows `MERGED`.

The merge pushes to `main`, which kicks off two things automatically: `release-please` and (if the
diff touched the server) the prod deploy. Handle the prod deploy below.

> **Leave the release-please PR alone.** The push to `main` opens/updates a `chore: release main` PR.
> Merging *that* cuts an App Store + Play production release (`release.yml`) — out of scope for this
> task and the human's call. Don't touch it.

## 4. Approve the prod release (server deploy)

The merge triggers `server-deploy-prod.yml`: it **auto-deploys dev**, then **pauses at the
`production` GitHub Environment** waiting for approval.

- **If the merged diff didn't touch `apps/server/**`**, no prod deploy is queued — **skip this step**
  and say so.
- Otherwise: first confirm **dev deployed cleanly** (the gate exists so a human eyeballs dev before
  promoting). Then find the waiting run and approve it:

  ```
  RUN_ID=$(gh run list --workflow=server-deploy-prod.yml --branch main \
    --json databaseId,status -q '[.[] | select(.status=="waiting")][0].databaseId')

  gh api repos/Elijah-Dangerfield/Cards/actions/runs/$RUN_ID/pending_deployments \
    -X POST -F 'environment_ids[]=17290466540' -f state=approved \
    -f comment="Nightly ship: dev healthy, promoting to prod."
  ```

  (`17290466540` is the `production` environment id.) Then `gh run watch $RUN_ID` and confirm it
  goes green. If the deploy fails, report it — don't retry blindly.

## 5. Ship the TestFlight beta locally

Local fastlane is the fast path (~11 min; creds live in gitignored `apps/ios/fastlane/.env`).

```
git checkout main && git pull origin main
cd apps/ios && bundle exec fastlane beta
```

- The `beta` lane uploads to the **TestFlight internal** group.
- **A green fastlane run does NOT prove the build reached TestFlight** — the Fastfile rescues upload
  failures as non-fatal (a known fastlane workaround). Verify the build actually landed in App Store
  Connect (or clearly flag the caveat) before you call it shipped.

## Guardrails

- **Never merge a red PR.** Green required checks are the only gate to step 3.
- **Only ever touch the nightly `develop → main` PR.** Leave unrelated human PRs and the
  release-please PR alone.
- **Merge commit, not squash.** Do not delete `develop`.
- **Approve prod only after dev is confirmed healthy**, and only if the diff changed the server.
- Leave a clean working tree.

## Report

One tight summary:

```
PR         : #214 "feat: leave-with-winnings" 
CI         : red → fixed commitlint title, rerun flaky Server tests → green
merge      : merged (merge commit a1b2c3d)
prod       : approved server-deploy-prod run 98765 → deployed green
beta       : fastlane beta green; VERIFY in ASC — upload rescued as non-fatal
needs-human: release-please PR "chore: release main" open (App Store release is yours to cut)
```
