# Developer TODO

Things only the human (Elijah) can do — credentials to provide, GitHub settings to flip, console configuration to set up. Not part of the engineering punch list ([todo.md](./todo.md) is for that). Automated workers must never touch this file.

Check items off as you do them; delete when the whole section is empty.

---

## GitHub repo settings

### Branch protection on `main`
The repo has no protection on `main` — PRs can merge without CI green, and direct pushes are allowed. CI is wired (`.github/workflows/ci.yml` runs `Build + test` on macOS and `Server tests` on ubuntu; `.github/workflows/server-deploy.yml` re-runs server tests before `fly deploy`), so flipping these on is just a settings change.

- [ ] **Settings → Branches → Add rule for `main`** — require status checks `Build + test` and `Server tests`, require branches to be up to date, disallow force-pushes and deletions.
- [ ] **Allow `release-please--branches--main`** to bypass the up-to-date requirement (or accept release-please rebasing its PR) so the release flow doesn't get stuck.
- [ ] **Confirm** the `Auto-merge` workflow still works — it uses `gh pr merge --auto`, which already waits for required checks, so no change needed there.

---

## Secrets to add via `gh secret set <NAME> --repo Elijah-Dangerfield/Cards`

The `Release` workflow auto-triggered after release-please tagged `v0.2.0` and failed because these aren't set. You need to add each one before re-triggering with `gh workflow run release.yml -f tag=v0.2.0 --repo Elijah-Dangerfield/Cards`.

### Android signing (Play upload)
- [ ] `ANDROID_KEYSTORE_BASE64` — `base64 -i your-release-keystore.jks | pbcopy`
- [ ] `ANDROID_KEYSTORE_PASSWORD` — from the keystore
- [ ] `ANDROID_KEY_ALIAS` — from the keystore
- [ ] `ANDROID_KEY_PASSWORD` — from the keystore
- [ ] `PLAY_SERVICE_ACCOUNT_JSON` — full JSON contents. Google Play Console → API access → service account, grant *Release manager* role.

### Apple / App Store Connect (TestFlight + ASC submit)
- [ ] `APPLE_TEAM_ID` — 10-char team ID. Apple Developer → Membership.
- [ ] `ASC_KEY_ID` — App Store Connect API key ID. ASC → Users and Access → Integrations → App Store Connect API.
- [ ] `ASC_ISSUER_ID` — issuer UUID, same page as above.
- [ ] `ASC_KEY_P8_BASE64` — `base64 -i AuthKey_XXX.p8 | pbcopy`
- [ ] `APPLE_DIST_CERT_P12_BASE64` — `base64 -i distribution.p12 | pbcopy`. Export from Keychain Access (private key + cert).
- [ ] `APPLE_DIST_CERT_PASSWORD` — password you set when exporting the `.p12`.

### Sentry (release uploads from CI)
- [ ] `SENTRY_AUTH_TOKEN` — Sentry → Account → API → Auth Tokens. Scopes: `project:releases`, `org:read`.

---

## Already done — for reference

These are already set so you don't have to:
- `FLY_API_TOKEN_DEV` — generated 2026-05-19 via `fly tokens create deploy -a cards-server-dev --expiry 8760h --name github-actions-deploy`. Used by `server-deploy.yml`.
- `CARDS_ADMIN_API_TOKEN_DEV` (repo) + `ADMIN_API_TOKEN` (Fly app env on cards-server-dev) — random 64-char hex set in both places so the sweep workflows can hit the admin endpoints. Generated 2026-05-19.
- Actions setting **"Allow GitHub Actions to create and approve pull requests"** — required for release-please.
