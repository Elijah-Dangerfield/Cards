---
name: hotfix
description: Use when a live prod regression needs an immediate fix or rollback; invoked on a prod alert or ad hoc ("hotfix the shop crash"). Assess the signal, confirm it's real and prod-affecting, prefer rolling back to the last-good release, then either fix it end-to-end (safe classes) or prepare the fix and escalate to the owner (money/auth/security/ambiguous). Always log to the incident log and close the Sentry loop.
---

# Hotfix

You are the **first responder** to a live production regression. The owner is the **last** resort, not the first. Move fast, prefer the reversible action, and be honest about the line between "I handled it" and "this needs a human."

Two ways in, same procedure:

1. **Alert-driven** — a prod alert or monitor hands you a signal (a firing Grafana alert, a Sentry crash cluster, a crash-free regression) that looks like a fresh prod problem.
2. **Ad hoc** — the owner points at something ("hotfix the shop crash", "prod is throwing 500s on redeem"). Take their pointer as the signal and start at step 1 anyway — confirm it's real before you touch anything.

Keep it tight and decisive. One-line report when done.

## Fixed coordinates

Stable for this repo — reconfirm only if a call 404s.

- **Repo:** `/Users/elijahdangerfield/Workspace/Cards`, slug `Elijah-Dangerfield/Cards`. `main` is release/prod; `develop` is integration (see the `ship-release` skill).
- **Prod server:** Fly app `cards-server-prod` (`apps/server/fly.prod.toml`), health `https://cards-server-prod.fly.dev/_health`. Dev is `cards-server` (debug builds). Deploy workflow `server-deploy-prod.yml` pauses at the `production` GitHub Environment (id `17290466540`) for approval.
- **Sentry:** org `elijah-dangerfield`, project `cards`, region `https://us.sentry.io`.
- **Grafana:** datasources per `observability-triage`; **alerts** in folder `downcard-engineering` (A1 ledger drift · A2 Fly prod down · A3 Supabase down · A4 clients can't reach backend · A5 purchase failures · A6 server OOM · A7 server silent). Question→dashboard map + **known-benign signals** live in `docs/wiki/observability.md`.
- **Escalation surface:** the Grafana **critical** contact point `downcard-critical` (email + IRM mobile push → the owner's phone) already carries `severity=critical` alerts. That's the page channel. See observability.md → "Alerts / Routing".
- **Auto-merge label:** `ai-autofix` — `auto-merge.yml` enables GitHub native auto-merge (squash) on any non-draft PR carrying it, and merges when required checks go green (`docs/practices/release.md`). Removing the label cancels the merge.
- **Incident log:** `docs/agent/incident-log.md` (append-only; create if missing — see step 6).
- **Correlation key:** `session_id` ties telemetry across Sentry / Tempo / Loki, same mechanics `feedback-triage` and `observability-triage` use. Lean on those skills' queries rather than re-deriving them.

## 1. Assess — is this real, prod, and new?

Before anything else, confirm the signal is a genuine prod regression and not noise. **A wrong "it's an incident" costs a needless release; a wrong "it's fine" costs users.** Establish:

- **Real and prod-affecting.** Is it firing/occurring **now**, on `deployment_environment="prod"` (or a `*-prod` release channel / retail build), across **more than one install**? A single install's blip is that user's environment, not an incident. Pull the alert's underlying query, or the Sentry issue's event volume + affected-user count.
- **Not known-benign.** Cross-check `docs/wiki/observability.md` → "Known-benign client signals" and "Known gaps". A banned-403 (`CARDS-BG`), a user-cancelled purchase, a one-off `net.backend_unreachable`, or the emulator/side-load PairIP-gate ANR (`CARDS-BR`) is **not** an incident — say so and stop. Gate the PairIP exemption carefully (zero first-party frames **and** the emulator/side-load fingerprint); a real ANR with Downcard frames across retail installs is a genuine bug.
- **Blast radius.** How many users / what fraction of sessions, and which flow (money? auth? core MP? cosmetic?). This sets both urgency and the autonomy line in step 3.
- **Version correlation.** Does the onset line up with the **most recent release**? Compare the signal's first-seen timestamp to the last `main` merge / release-please tag / prod deploy time (`gh release list`, `gh api .../deployments`, or the Sentry `release` on the events). A regression that started right after a ship is a **rollback candidate** (step 2). One that's been bleeding for weeks is a forward-fix.

If it fails the "real, prod, new, not-benign" test, write a one-line no-incident note to the incident log (step 6) and stop.

## 2. Prefer ROLLBACK

If the regression **correlates with the version just shipped**, reverting to the last-good release is almost always safer and faster than forward-fixing — you already know that version was healthy. Lead with it.

- **Server regression (Fly), need it stopped in seconds:** roll the machine back to the previous release image. This does **not** go through git/CI, so it's the fastest bleed-stop:
  ```
  flyctl releases --app cards-server-prod          # find the last-good version
  flyctl releases rollback --app cards-server-prod  # → immediately previous release
  ```
  (Requires Fly auth; the owner's local `flyctl` is logged in. If you can't authenticate, this is an escalate — hand the owner these exact two commands.) A machine rollback leaves `main` **ahead** of what's running, so you **must** still land the git revert (below) to reconcile — otherwise the next server-touching merge redeploys the bad code.
- **Client/app or server regression, the git-tracked way:** `git revert` the offending commit(s) → a `fix: revert <sha> (<subject>)` commit → the release path in step 5. release-please cuts a PATCH; for a server revert, the prod deploy redeploys the reverted code (superseding any manual Fly rollback).
- **Config / feature-flag regression:** if a runtime flag or config value caused it, revert the flag rather than shipping code. Use the hosted `/admin` console's revert/kill panel (the runtime config surface) — this is the lowest-blast-radius fix of all and needs no release. Then log it.

A pure rollback usually **has no reproducing test** (you're restoring known-good code, not fixing a diff). That's fine — the test-first rule below is for forward-fixes. For a rollback, the evidence is the version correlation from step 1; state that in the log instead of a red-then-green test.

## 3. The autonomy line — handle vs prepare-and-escalate

Decide which lane you're in **before** you write code. When in doubt, escalate — a prepared PR waiting for the owner is cheap; an autonomously-shipped wrong fix to a money path is not.

### Auto-handle end to end (fix or rollback → PR → `ai-autofix` label → ship → log)

Only when the change is **reversible, low-blast-radius, and verifiable**:

- a **rollback** of a version that correlates with the regression (server image rollback, `git revert`, or flag revert);
- a **feature-flag / config revert** that disables the broken path;
- an **obvious, contained forward-fix** — e.g. a null-guard one-liner — **with a reproducing test** (red → green) proving it.

In this lane you drive it to shipped: open the PR, apply the `ai-autofix` label, let auto-merge take it green→merged, approve the prod deploy **only after dev is confirmed healthy** (step 5), then log.

### Auto-PREPARE but do NOT merge — escalate to the owner

Open the PR **ready to merge** (fix written, tests where feasible, CI running) but **do not apply `ai-autofix`**, and page the owner. Use this lane for anything that is:

- **money / billing** (purchases, redeem, wallet, chip ledger, the A1/A5 alerts), **auth**, or **security**;
- a **schema or data migration** (`V##__*.sql`), or anything that could touch persisted user data irreversibly;
- **not reproducible** / has **no test** proving the fix;
- **ambiguous** — you can't cleanly tie the signal to a cause, or the fix involves a product/UX judgment call.

Page via the Grafana critical surface `downcard-critical` (or the clearest available channel), with: what's firing, blast radius, the PR link, and your recommended action (merge as-is / needs a look). Then hand off — do not merge on the owner's behalf. The owner applies the label (or merges) when they're satisfied.

## 4. Write the fix (forward-fix lane)

For a forward-fix, reproduce **test-first** per repo convention: write the failing test that captures the regression (red), then the smallest fix that turns it green. If you genuinely can't reproduce it, you're in the escalate lane — say so; don't ship a guess. (Rollbacks skip this — see step 2.)

- Match the surrounding code; use `Catching {}`, not `runCatching`. Follow the repo conventions in `AGENTS.md` / the memory feedback notes.
- Keep the diff minimal and single-purpose — a hotfix fixes the regression and nothing else. No opportunistic cleanup.
- Commit as a conventional **`fix:`** commit so release-please cuts a PATCH.

## 5. Release path

Fast-track a hotfix; reuse the `ship-release` skill's CI-green + prod-approval mechanics.

1. **Branch + commit.** Branch off `main` for a true hotfix (fastest path to prod), or off `develop` per repo norm if the fix can wait for the normal train. Push a `fix:` commit.
2. **Open the PR into `main`** (hotfix) with a tight body: signal, root cause, action. In the **auto-handle** lane, apply the `ai-autofix` label — `auto-merge.yml` enables squash auto-merge and lands it once required checks (**Build + test**, **Server tests**, **Validate PR title**) go green. **Never merge red**; if a check fails, fix at the source and re-poll every 1–2 min (see the `ship-release` skill). In the **escalate** lane, leave the label off.
3. **release-please** opens/updates a `chore: release main` PR that cuts a **PATCH**. Merging that PR is the app-store release — the **owner's call**; don't touch it unless the hotfix must reach the stores (then escalate that decision explicitly).
4. **Server prod deploy.** If the diff touched `apps/server/**` (or the other server-affecting paths in `server-deploy-prod.yml`), the merge queues a prod deploy that **auto-deploys dev, then pauses at the `production` gate**. Confirm **dev is healthy first**, then — auto-handle lane only — approve:
   ```
   RUN_ID=$(gh run list --workflow=server-deploy-prod.yml --branch main \
     --json databaseId,status -q '[.[] | select(.status=="waiting")][0].databaseId')
   gh api repos/Elijah-Dangerfield/Cards/actions/runs/$RUN_ID/pending_deployments \
     -X POST -F 'environment_ids[]=17290466540' -f state=approved \
     -f comment="Hotfix <ref>: dev healthy, promoting fix to prod."
   ```
   Then `gh run watch $RUN_ID` and confirm green + `/_health` OK. In the escalate lane, leave the gate for the owner.
5. **Back-merge to `develop`.** A hotfix landed straight on `main` must be merged back into `develop` (or cherry-picked) so it isn't clobbered by the next `develop → main` train. Don't skip this — a lost revert re-ships the bug.

## 6. Log it — always

Append to `docs/agent/incident-log.md` for **every** invocation, including a no-incident finding. Create the file with this header if missing:

```
# Incident log

Append-only record of prod regressions the hotfix action assessed — what fired, root cause,
what was done, and whether it was handled autonomously or escalated. Newest at the bottom.
```

One entry per incident:

```
## <YYYY-MM-DD HH:MMZ> · <short title>

- **Signal:** <alert / Sentry short-id / canary metric> · <where it fired> · blast radius <N installs / % sessions>
- **Version correlation:** <started after release X @ time | long-standing | none>
- **Root cause:** <one or two sentences>
- **Action:** <rollback (Fly image / git revert <sha> / flag revert) | forward-fix <PR #>> · <config/flag/code>
- **Lane:** <auto-handled | prepared + escalated to owner>
- **Release/rollback ref:** <PR # / merge sha / release tag / Fly version / N/A>
- **Sentry:** <issue URL + status left>
```

## 7. Close the Sentry loop

If the regression maps to a Sentry issue, close the loop the same way the triage skills do (token from env → Keychain fallback, never echoed):

```
TOKEN="${SENTRY_AUTH_TOKEN:-$(security find-generic-password -s cards-sentry-auth-token -w 2>/dev/null)}"
```

- **Fix/rollback actually shipped to prod** → resolve the issue:
  `curl -sS -X PUT "https://us.sentry.io/api/0/organizations/elijah-dangerfield/issues/<issueId>/" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"status":"resolved"}'`
- **Prepared + escalated (not yet merged)** → comment only, leave unresolved:
  `curl -sS -X POST ".../issues/<issueId>/comments/" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"text":"Hotfix prepared <PR #>, awaiting owner merge"}'`
- **`$TOKEN` empty** → note in the report that Sentry status wasn't flipped; the incident log is the source of truth.

## Guardrails

- **First responder, not cowboy.** The autonomy line in step 3 is the whole point — money/auth/security/migrations/no-test/ambiguous **always** escalate, however tempting the quick fix.
- **Prefer the reversible action.** Rollback / flag revert beats a forward-fix when the version correlates. A clever forward-fix under fire is how you turn one incident into two.
- **Never merge red**, never approve prod before dev is confirmed healthy, never merge in the escalate lane.
- **A machine (Fly) rollback isn't done until the git revert lands** — reconcile `main` with what's running or the next deploy re-ships the bug.
- **Back-merge hotfixes to `develop`.** A fix that only lives on `main` gets clobbered.
- **Don't touch the release-please `chore: release main` PR** — cutting the store release is the owner's call.
- **Log every run**, including "assessed, not an incident." The brief depends on it. Leave a clean working tree.
