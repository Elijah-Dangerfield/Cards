# Release

This template ships to the App Store and Play Store with no human clicks after one-time setup. Two short sections for the things most people ask, then reference material for when something breaks.

## Branching model: trunk-based

`main` is always shippable; short-lived branches merge into `main`; releases are tags on `main` cut by release-please. No long-lived release branches. The full rationale (and the alternatives we rejected) is in [`../decisions.md`](../decisions.md) under the 2026-06-24 entry — "Branching: trunk-based, no release branches."

---

## Two release tracks: app vs server

There are **two independent release tracks**, and most of this doc is about the first one:

1. **The mobile app** — a *versioned artifact* shipped to the App Store / Play Store via release-please + [release.yml](../.github/workflows/release.yml). Store review, staged rollout, slow rollback. This is the "Cutting a release" flow below.
2. **The server** (`cards-server-dev` / `cards-server-prod` on Fly) — a *continuously-deployed service*, **not** in release-please and **not** tied to the app version. Fly deploys are instant and reversible.

**Why decoupled:** the server must serve every app version in the wild (that's what version-targeted config is for), and the two have opposite cadence/rollback shapes. Coupling them would block server hotfixes on store review, or block app releases on server deploys. **Coordinate a breaking client⇄server change with config flags, not a lockstep deploy** — ship the backward-compatible server first, release the app, then flip the flag once enough clients have updated.

**Server deploy model:**

| | When it deploys |
| --- | --- |
| **dev** (`cards-server-dev`) | Auto on every push to `main` (server-affecting paths). See [server-deploy.yml](../.github/workflows/server-deploy.yml). |
| **prod** (`cards-server-prod`) | Auto-**queued** on push to `main`, then **paused for approval** on the `production` GitHub Environment. Approve it in **Actions → the run → "Review deployments"** (one click) once dev looks good. A `workflow_dispatch` break-glass path also exists. See [server-deploy-prod.yml](../.github/workflows/server-deploy-prod.yml). |

So a server change lands in prod when a human approves the auto-queued deploy — deliberate, audited, and one click, with no "did anyone remember to deploy?" toil. Ops details (secrets, standing up prod) live in [`apps/server/DEPLOY.md`](../../apps/server/DEPLOY.md).

---

## Cutting a release (for humans)

> TL;DR: merge the "release" PR. That's it.

1. There is always (well — whenever there are new `fix:`/`feat:`/`perf:` commits on main) an open PR titled **`chore(main): release vX.Y.Z`**, opened automatically by the release-please bot. It contains the version bump + changelog.
2. **Merge that PR.** release-please creates the `vX.Y.Z` tag + GitHub Release.
3. release-please.yml then dispatches [release.yml](../.github/workflows/release.yml) for that tag (it can't rely on the tag-push trigger — GitHub's default `GITHUB_TOKEN` deliberately doesn't cascade workflow triggers). release.yml:
   - Android → Play Console production track, 10% staged rollout
   - iOS → TestFlight external group "main" → submitted to App Store review with Apple's built-in phased release
4. Apple review (1–3 days) and Play review (few hours) approve. Builds roll out automatically.

### Read the top of the release PR before you merge

The body starts with a **"What merging this actually does"** block, rebuilt by
[release_pr_context.py](../../.github/scripts/release_pr_context.py) every time release-please
touches the PR. Unlike the rest of the body it is not boilerplate; it asks the stores and the git
history what is true for *this* release:

- **Android.** Whether Play actually has a production release (if not, the run routes to
  `internal` and you must promote by hand), and whether a **previous staged rollout is still in
  progress**, since shipping supersedes it.
- **iOS.** Whether a version is already `WAITING_FOR_REVIEW`, `IN_REVIEW` or
  `PENDING_DEVELOPER_RELEASE`. App Store Connect holds one version in flight at a time, so
  merging while one sits there collides with it. **This is the check worth waiting on.**
- **Scope.** How many commits touch client code (what merging actually delivers) versus
  server-only commits, which deploy on their own cadence and are usually already live in prod
  before you ever see the release PR. Without this the changelog reads as though a server fix
  ships with the app, which it does not.

Every probe degrades to an explicit "not checked" line when a credential is missing. It never
guesses, because a body the reader can't trust is what this replaced. If the whole step fails the
PR still updates, just without the block (`continue-on-error: true`).

The static remainder of the body lives in `pull-request-header` in
[release-please-config.json](../../release-please-config.json) and should only ever contain
claims that are true for every release.

### What if the release PR doesn't exist?

No conventional-commit changes since the last release. Merge a `fix:` or `feat:` PR and the bot will open one within a minute.

### What if I want a specific version number?

Edit the release PR body to add `Release-As: 2.0.0` on its own line. The bot rewrites the PR with that version.

### What if I want to skip one store for this release?

Actions → **Release** → Run workflow → pick the tag, tick **skip_play_store** or **skip_app_store**.

### What if a release run failed halfway?

Actions → **Release** → Run workflow → pick the same tag. It re-runs idempotently (each store tolerates duplicate uploads of the same build).

### What if main is broken and I need a hotfix without shipping the broken code?

This is the one case the pipeline is not optimized for. Branch from the previous tag, cherry-pick the fix, tag manually (`git tag v1.2.4 && git push origin v1.2.4`), and `release.yml` will pick it up.

---

## Shipping a TestFlight beta

Two interchangeable triggers, same result (build → sign → TestFlight internal group):

**Remote (GitHub-hosted runner):**
- UI: Actions → **Beta (internal)** → Run workflow from `main` (tick `skip_android` until the Play internal track exists), or
- terminal: `gh workflow run beta.yml --ref main -f skip_android=true`

Free on the public repo, but slower than local. The Kotlin/Native release
link used to be the wall (~45–60 min per build, heap spilling into swap on
the 7GB runner). Two changes on 2026-07-23 attacked it directly: Kotlin
2.4.0 (halves link-release memory, KT-80367) and
`kotlin.native.binary.smallBinary=true` in gradle.properties (-Oz for the
LLVM phase, which dominated link time — see KT-78518). A full local release
link now takes ~9–10 min; expect the runner to land somewhere above that.
Fine for fire-and-forget.

**Local (your Mac):**

```bash
cd apps/ios
cp fastlane/.env.example fastlane/.env   # first time only — fill in the ASC key values
bundle exec fastlane beta
```

fastlane auto-loads `fastlane/.env` (gitignored; see `.env.example` for
where each value comes from). Signing uses your login keychain — no CI
keychain import. Much faster than the hosted runner (no swap, warm caches)
and it uploads to the same TestFlight internal group with the same
timestamp build number, so the two triggers can't collide. Wiretap is
stripped either way (`CARDS_WIRETAP_IOS=false`, set by both the workflows
and the Fastfile).

---

## How automated fixes land (what the bots do)

Sentry triage runs as a **Claude Code routine on the maintainer's machine**, not as a CI job — so it uses your normal Claude subscription and the Sentry MCP instead of a paid API key + curl. Schedule it weekly (or on demand) with the prompt at [scripts/prompts/sentry-triage.md](../scripts/prompts/sentry-triage.md). The prompt is the only thing you edit to change behavior.

Each run:

1. Pulls top 5 unresolved **production** Sentry issues from the last 7 days via the Sentry MCP. Debug/preview builds are filtered out.
2. For each, creates a `ai/sentry-<id>` branch, forms a hypothesis, writes the smallest plausible fix, adds a regression test if possible.
3. Opens a PR titled `fix: …` with labels `ai-autofix` and `sentry`.
4. Issues that can't be fixed from source (third-party SDK frames, user-environment noise) become tracking GitHub issues instead of PRs.

`auto-merge.yml` watches for the `ai-autofix` label and enables GitHub's native auto-merge on the PR. When CI is green, GitHub squash-merges it. The fix is now on main and the next release PR from release-please includes it.

**To block a specific PR from auto-merging**: remove the `ai-autofix` label or close the PR — GitHub's auto-merge cancels.

**To pause triage entirely**: disable the routine in Claude Code. No CI to touch.

### Post-release monitoring

There's no automated rollout guard. Apple's built-in phased release (7-day gradual rollout from 1% → 100%) and Play's staged rollout (10% initial) give you a safety window. For detection, configure Sentry's native alerting (Sentry → Alerts → create a rule on *crash-free sessions* below 99% for the relevant project) and halt manually in App Store Connect / Play Console when needed.

---

## System map

```
PR   ──►  commitlint + CI                 Claude routine (local, weekly)
  │          │                                       │ reads Sentry MCP
  │          ▼ (green)                                ▼
  │      merge to main ◄──────── opens fix: PRs (label ai-autofix)
  │                                                  │
  │                                                  ▼
  │                                         auto-merge on green CI
  ▼
main ─── push ──► release-please (bot) maintains open "release vX.Y.Z" PR
                        │
                        ▼ merge
                 tag v*, GitHub Release
                        │
                        │ release-please.yml dispatches release.yml
                        │ (GITHUB_TOKEN doesn't cascade tag triggers)
                        ▼
                 release.yml ── Android AAB ──► Play production (10% staged)
                             ── iOS IPA ─────► TestFlight "main" external
                             ── App Store ──► submit + phased rollout
                             ── Sentry ─────► create release + upload mappings

Post-release: Sentry's own alerting + App Store / Play's native phased
rollouts. Halt manually if crash-free rate tanks.
```

## Workflows at a glance

| Workflow | Fires on | What it does |
|---|---|---|
| [ci.yml](../.github/workflows/ci.yml) | PRs, push to main | Compile + tests. No uploads — shipping happens via release.yml. Skipped on release-please's PR and its merge commit (bumps-only changes can't break the build). |
| [commitlint.yml](../.github/workflows/commitlint.yml) | PRs | Rejects non-conventional PR titles. |
| [release-please.yml](../.github/workflows/release-please.yml) | push to main | Maintains the release PR, creates tag + GH Release on merge. |
| [release.yml](../.github/workflows/release.yml) | dispatched by release-please.yml after tag creation; also `workflow_dispatch` with an explicit tag for re-runs; also fires on tag pushes made by a human | Full production release to both stores. |
| [beta.yml](../.github/workflows/beta.yml) | manual `workflow_dispatch` only (pick the branch) | Internal beta: Android → Play internal track, iOS → TestFlight internal (`fastlane beta`). The channel for friends testing + real sandbox IAP. |
| [auto-merge.yml](../.github/workflows/auto-merge.yml) | PR labeled `ai-autofix` | Enables GitHub auto-merge. |

Sentry triage is not a workflow — it runs as a Claude Code routine on the maintainer's machine. See [scripts/prompts/sentry-triage.md](../scripts/prompts/sentry-triage.md).

## Versioning

- **`versionName` / `MARKETING_VERSION`** — owned by release-please. Do not edit in feature PRs. Markers in [versions.properties](../versions.properties) and [Config.xcconfig](../apps/ios/Configuration/Config.xcconfig) tell the bot where to write.
- **`versionCode` / in-app `BuildInfo.buildNumber`** — CI overrides them via env vars `VERSION_CODE_OVERRIDE`, `BUILD_NUMBER_OVERRIDE`, `RELEASE_CHANNEL_OVERRIDE`, honored by [Versioning.kt](../build-logic/src/main/java/com/cards/util/Versioning.kt) (falling back to [versions.properties](../versions.properties) for local builds). `release.yml` uses `GITHUB_RUN_NUMBER`; `beta.yml` uses the commit count (`git rev-list --count HEAD`) — different bands so beta + release never reuse a `versionCode`. This drives the Android `versionCode` and the in-app number shown in Settings.
- **iOS store build number (`CFBundleVersion`)** — separate: a timestamp set by fastlane (`Time.now.strftime`, see [Fastfile](../apps/ios/fastlane/Fastfile)), *not* the override above. So the number App Store Connect shows differs from the in-app `BuildInfo.buildNumber`; that's intentional (avoids ASC "Redundant Binary Upload").
- **Sentry release ID**: `cards@{version}+{build}` (e.g. `cards@0.2.0+42`). Created on both platforms in release.yml.

## Secrets and variables

Set under **Settings → Secrets and variables → Actions**. Secrets are encrypted, variables (`vars.*`) are plaintext.

### Already set

- `APPLE_TEAM_ID`, `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_KEY_P8_BASE64`

### Needed for iOS releases (cert reuse)

| Name | Kind | Value |
|---|---|---|
| `APPLE_DIST_CERT_P12_BASE64` | secret | Base64 of your exported Apple Distribution .p12 (cert + private key). |
| `APPLE_DIST_CERT_PASSWORD` | secret | The password set when exporting the .p12. |

Without these, Xcode's automatic signing creates a new Apple Distribution cert on every CI run and eventually hits the team cap (~2–3 per type). With them, every run imports the same cert into a temp keychain and reuses it forever.

**One-time setup:**

1. On your Mac, open **Xcode → Settings → Accounts → [your team] → Manage Certificates** (or **Keychain Access**).
2. Find or create an "Apple Distribution: *your name* (TEAMID)" cert.
3. Right-click → **Export → Personal Information Exchange (.p12)** → set a password → save it (e.g. `~/apple-dist.p12`).
4. Base64 and stash:
   ```sh
   base64 -i ~/apple-dist.p12 | gh secret set APPLE_DIST_CERT_P12_BASE64
   gh secret set APPLE_DIST_CERT_PASSWORD    # paste the password you chose
   rm ~/apple-dist.p12                        # delete the local .p12
   ```

The cert is valid for ~1 year — when it expires, re-export and update the secret.

### Needed for Android production releases

| Name | Kind | Value |
|---|---|---|
| `ANDROID_KEYSTORE_BASE64` | secret | `base64 -i cards-upload.p12 \| pbcopy` |
| `ANDROID_KEYSTORE_PASSWORD` | secret | Set when keystore was created. |
| `ANDROID_KEY_ALIAS` | secret | e.g. `cards-release`. |
| `ANDROID_KEY_PASSWORD` | secret | For PKCS12: same as keystore password. |
| `PLAY_SERVICE_ACCOUNT_JSON` | secret | Full JSON body of the Play Console service account key (not base64). |

Generate the keystore once:

```sh
keytool -genkey -v -keystore cards-upload.p12 -storetype PKCS12 \
  -keyalg RSA -keysize 4096 -validity 10000 -alias cards-release
```

Keep the `.p12` in 1Password **and** on disk. Losing both = you can never update the Play Store listing. Ever.

### Needed for Sentry release tracking

| Name | Kind | Value |
|---|---|---|
| `SENTRY_AUTH_TOKEN` | secret | Personal auth token with `org:read project:read project:write project:releases`. |
| `SENTRY_ORG` | variable | Sentry org slug (from https://sentry.io/settings/). |
| `SENTRY_PROJECT` | variable | Sentry project slug. |

All three empty = Sentry release creation is skipped; the pipeline otherwise runs fine.

### Play service account — one-time setup

1. Google Cloud Console → new project "This template CI".
2. APIs & Services → Library → **Google Play Android Developer API** → Enable.
3. IAM & Admin → Service Accounts → Create → name `cards-ci`, role none.
4. Click the service account → Keys → Add Key → JSON → save the downloaded file.
5. Play Console → Users and permissions → Invite new user → paste the service-account email → grant **Release manager** on the This template app.
6. GitHub secrets: paste the **full JSON** (not base64) into `PLAY_SERVICE_ACCOUNT_JSON`.

### One-time App Store Connect setup

The pipeline ships only the binary + release notes (`skip_metadata: true`, `skip_screenshots: true`). The listing must be complete in App Store Connect before submission, or Apple rejects:

- [ ] App info: name, subtitle, category, privacy policy URL
- [ ] Pricing + availability
- [ ] Screenshots (≥ 6.5" iPhone set)
- [ ] Description, keywords, support URL
- [ ] Age rating + App Privacy declaration
- [ ] Review info (contact, demo account if relevant)
- [ ] External TestFlight group named **main** with invited testers — the pipeline uploads to this group by name

### One-time Play Console setup

- [ ] Create the app entry for `com.dangerfield.cards`
- [ ] Complete Store listing (description, screenshots, feature graphic, icon)
- [ ] Content rating, target audience, data safety, category, contact
- [ ] **Ship the first production release manually from Play Console.** `r0adkll/upload-google-play` can't push to production until there's an approved prod release to update. Use `track: internal` in [release.yml](../.github/workflows/release.yml) for the first few releases if you prefer automation all the way down.

### One-time GitHub Pages source

Marketing/landing pages (`index.html`, `privacy.html`, `terms.html`, `style.css`) live in [pages/](../pages/) so that `docs/` can stay developer-focused. Set **Settings → Pages → Source** to `main` / `/pages` so the site serves at `https://<user>.github.io/<repo>/` without any path change. The URLs referenced from the app (`/privacy.html`, `/terms.html`) stay the same.

## Runbook: something broke

| Symptom | First thing to check |
|---|---|
| No release PR appearing | No conventional-commit changes since last release — expected. Or check the latest run of `release-please.yml` in Actions. |
| commitlint blocking a PR | PR title isn't `type: lowercase subject`. Edit the title. |
| `release.yml` Android job: keystore missing | One of the `ANDROID_*` secrets is empty. |
| `release.yml` Android job: Play upload skipped with warning | `PLAY_SERVICE_ACCOUNT_JSON` is empty or malformed. |
| Apple rejects submission | Usually metadata or privacy-related. Fix in App Store Connect — the binary is already uploaded, resubmit from ASC, no rebuild. |
| Crash-free rate spikes after release | Halt phased rollout manually: App Store Connect → your app → Phased Release → **Pause Rollout**. Play Console → Production → **Halt rollout**. Ship a fix via normal flow; next release supersedes. |
| Tag created but release.yml didn't fire | release-please.yml failed at the dispatch step (check its run). Manual remediation: Actions → Release → Run workflow → enter the tag. If a human pushed the tag (not release-please), the push trigger would have fired it — if that's not the case, the `v*` prefix is probably wrong. |
| AI triage PR failed CI | Check the PR — if the fix is wrong, close it. `ai-autofix` PRs don't auto-merge without green CI. |

## Extending the pipeline

Small additions that keep the existing shape:

- **Weekly unattended release**: cron that merges the open release PR every Sunday unless labeled `hold-release`. ~10 lines.
- **`fastlane snapshot` screenshots**: once UI is stable, auto-generate screenshots from XCUITests instead of committing PNGs. Drops `skip_screenshots: true`.
- **Slack/Discord notifications**: webhook step at the end of `release.yml`.
- **Android dSYM/mapping to Sentry**: already wired in `release.yml` (the "Create Sentry release" step). Verify after the first Android release.
