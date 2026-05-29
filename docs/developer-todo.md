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

- [ ] **Supabase email-confirm site URL + redirect URLs + branded template.** Today the confirmation link in the email Supabase sends out still points at the default site URL (localhost) — users can't actually confirm by clicking it. Set the project's site URL + redirect URLs in the Supabase dashboard for dev *and* prod, and while there swap the default Supabase template for a Cards-branded one (copy lives in [voice-and-copy.md §5.x](./product/voice-and-copy.md)). The client-side `VerifyEmailScreen` already does `refreshSession()` + `emailConfirmedAt` check on the "I confirmed" tap — that bit is correct; the no-op symptom traces back to this dashboard misconfig.
- [ ] **Friend-game link previews — Universal Links + App Links + web host.** [product-spec.md §5.2](./product/product-spec.md#52-friend-games) promises iMessage/WhatsApp previews showing a Cards-branded card with stakes + seat count. Three external pieces gate the engineering work: (a) iOS Universal Links — configure the Associated Domains entitlement in Apple Developer, upload the AASA file to a web host; (b) Android App Links — configure Digital Asset Links JSON on the same host; (c) the web endpoint itself, which serves Open Graph meta (`og:title`, `og:image`, `og:description`) keyed by the friend-code in the URL. Once the host + dashboard pieces exist, engineering picks up the client-side intent-filter / Associated Domains plist entry + the OG image renderer. **V1-polish** — friend games work today via copy-code; the rich preview is a social-virality nicety, not a blocker.
- [ ] **Spin up a real prod Fly app + update `ServerInfo.PROD_BASE_URL`.** Today release builds talk to `cards-server-dev.fly.dev` because there's no prod Fly app yet. Create `cards-server-prod` (or equivalent) on Fly, deploy the server, and replace the placeholder in [`ServerInfo.kt`](../libraries/core/src/commonMain/kotlin/com/cards/libraries/core/ServerInfo.kt). Until this is done, every release build is hitting dev data — fine for early testing, blocking for any external rollout.

---

## Deferred product decisions

These need a call from you before engineering can pick them up. Once decided, move the item into `docs/todo.md` (or close it out).

- [ ] **Notifications (Phase 6).** Opt-in event-driven push only — league placement, friend activity, battle-pass tier, Rare/Legendary achievement unlock. Never time-of-day modeled, never "your chips are lonely," never "come back" pings (see [spec §8](./product/product-spec.md#8-notifications)). Per-category opt-in granularity, not just global on/off. **Explicitly Phase 6** on the roadmap — kept here so it doesn't drift into worker scope.
- [ ] **Cosmetic-pairing rarity tuning after first real playtest.** Today every rarity-EPIC-or-above achievement grants a permanent cosmetic — most notably `SHOW_ROYAL_FLUSH → "Royalty" title` and `SHOW_STRAIGHT_FLUSH → "Suited Run" title`. Real-poker frequencies (royal ~1 in 30K hands, straight flush ~1 in 3K) suggest both stay rare flexes, but bot tables play loose and the bot showdown rate is higher than IRL — once first-week playtest data is in, decide whether either should bump the criterion (e.g. "show 2× royal flush") or downgrade from a title to a lighter cosmetic. Same call applies to the five new `BEAT_*_10` per-bot emote packs if any one bot turns out trivially beatable.
- [ ] **`Profile.Fallback` per-feature audit — designer-in-the-loop pass.** The auth-failure → `Profile.Fallback` path is already wired ([SupabaseProfileRepositoryImpl](../libraries/identity/impl/src/commonMain/kotlin/com/cards/libraries/identity/impl/profile/SupabaseProfileRepositoryImpl.kt)); on bad-network first launch the user reaches `Profile.Fallback(id = clientLocalUuid)` and can play bots immediately. What's left is the per-surface UX call — for each of Home / Shop / Profile / Edit Profile / Claim Account / Inventory / Multiplayer / Settings, pick one of: **cached browse** (renders off local DB, no mutations), **hard-gate** ("you need to be online" — for anything mutating server state), or **soft-gate / read-only** (renders, but mutation affordances disabled). Once the per-surface behavior is decided, engineering picks up the screens.
  - **Edit Profile specifically** also depends on the avatar-pack server contract — drop-auth on `/v1/avatars` + append-only starter pack — captured in [todo.md §C "Avatar starter pack: lock the server contract"](./todo.md). When the avatars fetch has never landed (first install, offline) the screen falls back to a hardcoded 8-emoji starter list; whether patchMe is allowed to mutate the avatar from that fallback view, and how that error surfaces, is the call to make here.

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
