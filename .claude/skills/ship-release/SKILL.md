---
name: ship-release
description: Use to merge the release PR and cut/watch a release; the ship step of the flow or invoked ad hoc. Merge the nightly develop → main PR (green only), then handle the release-please "chore: release main" PR that actually cuts the App Store + Play release, and watch release.yml to green. Optional tail: approve the prod server-deploy gate and ship the TestFlight beta.
---

# Ship release

Take the nightly work from "green-ish" to **released**. The owner's mental model is simple: **ship-release mostly just merges the release PR and watches the release GitHub Action.** Everything below is the how behind that.

**Dual-mode.** This runs two ways, and behaves the same either way:
- **Standalone** — a human says "ship it" / "cut the release" / "ship the beta". You're the last mile, nobody's watching, so be careful and finish the job.
- **Orchestrated** — invoked as the ship step of the nightly flow, after the overnight pipeline stacked its work onto one PR.

When done, report **one tight line** (format at the bottom).

## The release model (read once)

Two PRs, two merges, in order:

1. The nightly work lands as a **`develop → main` PR**. Merging it to `main` triggers `release-please`, which opens or updates a **`chore: release main` PR**, and (if the diff touched the server) the prod deploy.
2. Merging the **`chore: release main` PR** is what actually cuts the App Store + Play production release, via **`release.yml`**. That's the release. Watch it to green.

There's also an **`ai-autofix`** GitHub label the owner uses to auto-merge a PR once its checks pass — the configured hands-off path for step 2 below, and usable for step 1.

**Repo:** `/Users/elijahdangerfield/Workspace/Cards` (slug `Elijah-Dangerfield/Cards`). You work across `develop` (to fix CI) and `main` (to ship).

## 1. Find the nightly PR and get it green

```
gh pr list --base main --head develop --state open --json number,url,title,headRefName
```

- **Exactly one `develop → main` PR** → that's your target.
- **None** → nothing to promote (e.g. only the janitor ran — its PRs target `develop`, not `main`). **Report that and stop.** Don't invent a release. A green janitor / `ai-autofix` PR into `develop` merges on its own path; leave it, just note it.
- **More than one** → pick the `develop → main` one; mention the others in your report.

Required checks on `main`: **Build + test**, **Server tests**, **Validate PR title**.

```
gh pr checks <n>
```

- **Green** → step 2.
- **Red** → fix it, don't wait it out:
  - `gh run view <run-id> --log-failed` to see the failure.
  - **Fix at the source on `develop`** (commit + push). A commitlint / title failure is a title edit (`gh pr edit <n> --title "..."`); a real build/test failure is a code fix. An obvious flake (Konan cache, network, runner hiccup) → `gh run rerun <run-id>` **once**.
  - Re-poll every ~1–2 minutes (short polls, not long sleeps) until every required check passes.
- **Can't get it green** after honest effort → **stop and report. Never merge a red PR.**

## 2. Merge the nightly PR

Only once every required check is green. Two paths:

- **Direct merge (default):**
  ```
  gh pr merge <n> --merge
  ```
  **`--merge` (a real merge commit), not squash or rebase** — owner preference for the nightly PR. **Do not delete `develop`** (it's permanent; never pass `--delete-branch`). Confirm `gh pr view <n> --json state,mergedAt` shows `MERGED`.
- **Automerge label (hands-off path):** if the owner wants it queued rather than merged now, apply the label instead — GitHub merges it once checks pass:
  ```
  gh pr edit <n> --add-label ai-autofix
  ```
  Still confirm it actually merged before moving on; don't assume the label fired.

The merge pushes `main`, which kicks off `release-please` (opens/updates the `chore: release main` PR) and, if the diff touched the server, the prod deploy.

## 3. The crux — handle the `chore: release main` PR

This is the PR that actually releases. After the merge in step 2, `release-please` opens or updates it against `main`.

```
gh pr list --base main --state open --json number,url,title,headRefName \
  --search 'chore: release main in:title'
```

- It may take a minute to appear after the step-2 merge — re-poll a couple of times before concluding it's absent.
- **Respect the owner's approval preference.** This cuts a production App Store + Play release, so if you're running standalone and the human didn't clearly say to cut the release itself (only "get it merged" / "ship the beta"), **surface it and let them make the call** rather than merging it yourself. When they've asked to cut the release (or the orchestrated flow is configured to), proceed:
  - Get its checks green first (same mechanics as step 1 — never merge it red).
  - Merge it — `gh pr merge <n> --merge`, or apply the **`ai-autofix`** label for the hands-off path.

**Then watch the release action to green** — this is the payoff:

```
gh run watch $(gh run list --workflow=release.yml --branch main \
  --json databaseId -q '.[0].databaseId')
```

Confirm `release.yml` finishes green. If it fails, report it — don't retry blindly.

## 4. Optional tail — prod server deploy and TestFlight

Both of these **may already be automated** off the merges above. Treat them as documented fallbacks: do them only if the automated path didn't cover it, and skip with a note when not applicable.

**4a. Approve the prod server deploy.** The step-2 merge triggers `server-deploy-prod.yml`: it auto-deploys **dev**, then pauses at the `production` GitHub Environment for approval.

- **If the merged diff didn't touch `apps/server/**`**, no prod deploy is queued — **skip and say so.**
- Otherwise: first confirm **dev deployed cleanly** (the gate exists so a human eyeballs dev before promoting). Only then approve the waiting run:
  ```
  RUN_ID=$(gh run list --workflow=server-deploy-prod.yml --branch main \
    --json databaseId,status -q '[.[] | select(.status=="waiting")][0].databaseId')

  gh api repos/Elijah-Dangerfield/Cards/actions/runs/$RUN_ID/pending_deployments \
    -X POST -F 'environment_ids[]=17290466540' -f state=approved \
    -f comment="Nightly ship: dev healthy, promoting to prod."
  ```
  (`17290466540` is the `production` environment id.) Then `gh run watch $RUN_ID` and confirm green. If the deploy fails, report it — don't retry blindly.

**4b. Ship the TestFlight beta locally.** Local fastlane is the fast path (~11 min; creds live in gitignored `apps/ios/fastlane/.env`).

```
git checkout main && git pull origin main
cd apps/ios && bundle exec fastlane beta
```

- The `beta` lane uploads to the **TestFlight internal** group.
- **A green fastlane run does NOT prove the build reached TestFlight** — the Fastfile rescues upload failures as non-fatal (a known fastlane workaround). Verify the build actually landed in App Store Connect, or clearly flag the caveat, before calling it shipped.

## Guardrails

- **Never merge a red PR.** Green required checks are the only gate to any merge — the nightly PR *and* the release PR.
- **Only ever touch the intended PRs** — the nightly `develop → main` PR and (when cutting the release) the `chore: release main` PR. Leave unrelated human PRs alone.
- **Merge commit, not squash.** Do not delete `develop`.
- **Respect the approval preference** on the release PR — it cuts a production App Store + Play release. When standalone and not clearly authorized, surface it instead of merging.
- **Approve prod only after dev is confirmed healthy**, and only if the diff changed the server.
- **Leave a clean working tree.**

## Report

One tight summary:

```
nightly PR : #214 "feat: leave-with-winnings"
CI         : red → fixed commitlint title, rerun flaky Server tests → green
merge      : merged (merge commit a1b2c3d)
release PR : "chore: release main" #215 merged → release.yml green (App Store + Play cut)
prod       : approved server-deploy-prod run 98765 → deployed green
beta       : fastlane beta green; VERIFY in ASC — upload rescued as non-fatal
needs-human: none
```

Adjust lines to what actually happened — e.g. `release PR : surfaced #215, left for owner to cut` when you stopped short of the production release by design.
