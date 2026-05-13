# Release automation

This template ships to the App Store and Play Store with no human clicks after one-time setup. This doc has two short sections for the two things most people ask, then reference material for when something breaks.

---

## Cutting a release (for humans)

> TL;DR: merge the "release" PR. That's it.

1. There is always (well — whenever there are new `fix:`/`feat:`/`perf:` commits on main) an open PR titled **`chore(main): release vX.Y.Z`**, opened automatically by the release-please bot. It contains the version bump + changelog.
2. **Merge that PR.** release-please creates the `vX.Y.Z` tag + GitHub Release.
3. release-please.yml then dispatches [release.yml](../.github/workflows/release.yml) for that tag (it can't rely on the tag-push trigger — GitHub's default `GITHUB_TOKEN` deliberately doesn't cascade workflow triggers). release.yml:
   - Android → Play Console production track, 10% staged rollout
   - iOS → TestFlight external group "main" → submitted to App Store review with Apple's built-in phased release
4. Apple review (1–3 days) and Play review (few hours) approve. Builds roll out automatically.

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
| [auto-merge.yml](../.github/workflows/auto-merge.yml) | PR labeled `ai-autofix` | Enables GitHub auto-merge. |

Sentry triage is not a workflow — it runs as a Claude Code routine on the maintainer's machine. See [scripts/prompts/sentry-triage.md](../scripts/prompts/sentry-triage.md).

## Versioning

- **`versionName` / `MARKETING_VERSION`** — owned by release-please. Do not edit in feature PRs. Markers in [versions.properties](../versions.properties) and [Config.xcconfig](../apps/ios/Configuration/Config.xcconfig) tell the bot where to write.
- **`versionCode` / iOS build number** — auto-overridden in CI with `GITHUB_RUN_NUMBER` via env vars `VERSION_CODE_OVERRIDE` and `BUILD_NUMBER_OVERRIDE`. They bump monotonically without commits. (See [Versioning.kt](../build-logic/src/main/java/com/cards/util/Versioning.kt).)
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
