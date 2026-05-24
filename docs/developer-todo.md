# Developer TODO

Anything only the human (Elijah) can do — credentials, GitHub settings, dashboard / external-service config, device QA, content writing, deferred product decisions. Not part of the engineering punch list ([todo.md](./todo.md) is for that). Automated **workers** must never touch this file. The nightly **reviewer** may append a one-line entry when a PR creates a new human-only follow-up, but may not edit or delete existing entries.

For per-cycle items tied to a specific PR (visual deltas to eyeball, fixes that need device verification *this* cycle), see the PR's "Heads up" section instead — those don't belong here.

Check items off as you do them; delete when the whole section is empty.

---

## Device QA

- [ ] **App-store review prompt smoke test (Android + iOS).** Engineering is wired end-to-end (`ReviewPromptCoordinator`, `AndroidReviewPrompter`, `SKStoreReviewController`); meet the install-age + cooldown floors, then leave a bots table on a release build. Either outcome — Play Core / `SKStoreReviewController` showing the dialog or suppressing it — is correct per [spec §2.6](./product/product-spec.md#26-app-store-review-prompts). Never build a self-rolled fallback dialog.
- [ ] **Soft bust protection — device verification.** Server + client wired (`maybeApplyBustProtection` on `GET /v1/me/wallet` and `POST /v1/me/wallet/sync`; `UserMessage` polling picks up the welcome dialog; `ChipsRepository.observeBalance()` sees the +1000 delta). Verify on a real device that the dialog renders correctly with the chip-bubble emoji + body, and that the wallet observer fires after the grant. If the auto-pop dialog placement is wrong (e.g. fires mid-hand), report back and engineering will gate on session-start instead.
- [ ] **Device smoke test before merging `dev` → `main`.** Minimum checklist before any dev → main merge:
  1. Fresh install on Android (or iOS) against the dev server.
  2. Onboarding "Get Started" lands on Home without hanging.
  3. Chip balance hydrates cleanly (no 0 → 10K flash; null → authoritative).
  4. Sign up → verify email → claim account flow end-to-end on a real device.
  5. Edit profile, save, observe optimistic update + server-confirmed value.
  6. Shop purchase via the test billing path; chips deduct + restore correctly.

---

## Dashboard / external-service config

- [ ] **Supabase email-confirm site URL + redirect URLs + branded template.** Today the confirmation link in the email Supabase sends out still points at the default site URL (localhost) — users can't actually confirm by clicking it. Set the project's site URL + redirect URLs in the Supabase dashboard for dev *and* prod, and while there swap the default Supabase template for a Cards-branded one (copy lives in [voice-and-copy.md §5.x](./product/voice-and-copy.md)). The in-app `VerifyEmailScreen` "I confirmed" no-op is a separate engineering bug — tracked in `docs/todo.md` §B.

---

## Content writing

- [ ] **Privacy policy + Terms of Service page content.** Profile screen deep-links to a web page that is empty / placeholder. Hosting target can stay; just needs the actual copy.

---

## Deferred product decisions

These need a call from you before engineering can pick them up. Once decided, move the item into `docs/todo.md` (or close it out).

- [ ] **Notifications (Phase 6).** Opt-in event-driven push only — league placement, friend activity, battle-pass tier, Rare/Legendary achievement unlock. Never time-of-day modeled, never "your chips are lonely," never "come back" pings (see [spec §8](./product/product-spec.md#8-notifications)). Per-category opt-in granularity, not just global on/off. **Explicitly Phase 6** on the roadmap — kept here so it doesn't drift into worker scope.
- [ ] **Unlock-only catalog *content*.** Engineering for the earned-grant path is a `docs/todo.md` §B bullet. Choosing *which* legendary / league / RFT / achievement-chain cosmetics ship is a content call — acceptable to ship V1 with the unlock-only catalog empty.
- [ ] **Orphan-eviction policy after WS sweep.** When the server's WS-sweep evicts a user after a missed-heartbeats grace window (still being built — see `docs/todo.md` §C), should we *sit them out* (auto-fold their hands, hold the seat for a longer grace) or *fully remove* them from the room? Sit out is friendlier; remove is cleaner. Pick before wiring the sweep behavior.

---

## GitHub repo settings

### Branch protection on `main`
Rules are configured in the UI but **not enforced** — GitHub's banner: *"Your rulesets won't be enforced on this private repository until you move to GitHub Team organization account."* Track this so it gets flipped on once the repo lives in a Team org.

Intended rules (already saved in the UI, just inert):
- Require a pull request before merging (0 required approvals — solo repo)
- Require status checks to pass: `Build + test`, `Server tests`, `Validate PR title` *(checks must run at least once on a PR before they appear in the dropdown)*
- Do not enable "Require branches to be up to date" — strict mode is friction for the nightly PR and offers little on a solo repo
- Block force pushes
- Block deletions

- [ ] **Move repo into a GitHub Team org** (or wait for that to happen for other reasons) so the rulesets above start enforcing.

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
