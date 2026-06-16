# Developer TODO

Anything only the human (Elijah) can do — credentials, GitHub settings, dashboard / external-service config, device QA, content writing, deferred product decisions. Not part of the engineering punch list ([todo.md](./todo.md) is for that). Automated **workers** must never touch this file. The nightly **reviewer** may append a one-line entry when a PR creates a new human-only follow-up, but may not edit or delete existing entries.

For per-cycle items tied to a specific PR (visual deltas to eyeball, fixes that need device verification *this* cycle), see the PR's "Heads up" section instead — those don't belong here.

Check items off as you do them; delete when the whole section is empty.

---

## Parked engineering (worker-pickable, deferred by choice)

Normal engineering tasks pulled out of [todo.md](./todo.md) on purpose — to pick up later rather than hand to a worker.

- [ ] **Integration test fan-out (testing-plan Round 2).** The harness landed: `:apps:integration` boots a real in-process Ktor server and drives the **real client + real `LobbyViewModel`s** against it, with a green, non-flaky friends-game golden path (create → join → presence → start → both navigate) and a dedicated `integration-test` CI job. Remaining is the fan-out on top of the harness — wire-format round trips, reconnect-mid-setup, host-disconnect/promotion, join-rejection cases, "add a bot", and the public-game path when it ships. See [`testing-plan.md`](./testing-plan.md) Round 2.

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

- [ ] **Supabase email-confirm site URL + redirect URLs + branded template.** Today the confirmation link in the email Supabase sends out still points at the default site URL (localhost) — users can't actually confirm by clicking it. Set Site URL = `cards://auth/confirmed` (matching the deep-link wire-up in [todo.md §A Auth & account onboarding](./todo.md)) and add it to the redirect-URL allowlist, in the dashboard for dev *and* prod. While there, swap the default Supabase template for a Cards-branded one — copy is in the conversation's email-template draft (subject "Confirm your email — Cards" / body with `{{ .ConfirmationURL }}` button + "Then return to Cards" line). The client-side `VerifyEmailScreen` already does `refreshSession()` + `emailConfirmedAt` check; once the deep link is wired, the page auto-refreshes on resume — no manual button tap needed on the same device. Dashboard URLs: https://supabase.com/dashboard/project/yuqrfhdoejonclgbixlw/auth/url-configuration + https://supabase.com/dashboard/project/yuqrfhdoejonclgbixlw/auth/templates
- [ ] **Set up custom SMTP for transactional email.** Supabase's built-in email service has aggressive rate limits (3-4 emails / hour / user) and isn't production-grade — visible as a yellow warning at the top of the Email Templates dashboard. **Recommendation: Resend** (developer-friendly, generous free tier — 100 emails/day + 3000/month, easy DNS setup, great deliverability for transactional). Alternatives: SendGrid (incumbent, more complex setup), Postmark (more expensive, reliability-focused), Amazon SES (cheapest at scale, most setup). Once chosen: sign up, add + verify the sending domain via DNS records, paste the SMTP host + creds into Supabase Auth → Settings → SMTP. Test by triggering a verify-email flow end-to-end.
- [ ] **If you rename the app, update the email templates.** Subject lines, body copy, and the "— Cards" sign-off all live in Supabase Auth → Email Templates. They don't auto-track the app name. Also update any sender domain / from-name configured on the SMTP provider side (Resend / SendGrid / etc.).
- [ ] **Don't schedule the orphan-anon sweep cron.** Per [decisions.md 2026-05-29 — Anon-account cleanup](./decisions.md), the time-based sweep is dormant by design — replaced by a client-driven on-orphan delete (tracked in [todo.md](./todo.md) §A Auth). No cron, no Fly scheduled machine, no GitHub Actions job pointing at `POST /v1/admin/sweep-anonymous-users`. Item kept here so the next dashboard pass doesn't reflexively wire one up.
- [ ] **Friend-game link previews — Universal Links + App Links + web host.** [product-spec.md §5.2](./product/product-spec.md#52-friend-games) promises iMessage/WhatsApp previews showing a Cards-branded card with stakes + seat count. Three external pieces gate the engineering work: (a) iOS Universal Links — configure the Associated Domains entitlement in Apple Developer, upload the AASA file to a web host; (b) Android App Links — configure Digital Asset Links JSON on the same host; (c) the web endpoint itself, which serves Open Graph meta (`og:title`, `og:image`, `og:description`) keyed by the friend-code in the URL. Once the host + dashboard pieces exist, engineering picks up the client-side intent-filter / Associated Domains plist entry + the OG image renderer. **V1-polish** — friend games work today via copy-code; the rich preview is a social-virality nicety, not a blocker.
- [ ] **Spin up a real prod Fly app + update `ServerInfo.PROD_BASE_URL`.** Today release builds talk to `cards-server-dev.fly.dev` because there's no prod Fly app yet. Create `cards-server-prod` (or equivalent) on Fly, deploy the server, and replace the placeholder in [`ServerInfo.kt`](../libraries/core/src/commonMain/kotlin/com/cards/libraries/core/ServerInfo.kt). Until this is done, every release build is hitting dev data — fine for early testing, blocking for any external rollout.
- [x] **Sentry project created (`cards`, org `o327796`, project `4511478399565824`).** One project covers client + server; events are tagged `platform=ios|android|server` + `release=<version>` so cross-platform timelines stay unified. The **client** DSN is now wired in code ([`AppTelemetry.kt`](../libraries/cards/impl/src/commonMain/kotlin/com/cards/libraries/cards/impl/AppTelemetry.kt) — all platforms/build-types point at the one project; the `environment` tag separates debug vs release vs channel). Remaining human steps:
  - [ ] **Put the `cards` DSN in the Fly secret store on the server.** The server-side Sentry SDK is **already wired** — `installSentry(config.sentry)` runs at startup and reads `SENTRY_DSN` from the env (`SentryConfig.fromEnv` in [`ServerConfig.kt`](../apps/server/src/main/kotlin/com/cards/server/config/ServerConfig.kt)), staying dormant while the DSN is blank. So the only step is the secret:
    ```
    fly secrets set SENTRY_DSN="https://2010decd1b11057a4038b99bcd75878b@o327796.ingest.us.sentry.io/4511478399565824" -a cards-server-dev
    ```
    That triggers a rolling deploy; the server inits Sentry on boot. `SENTRY_ENVIRONMENT` auto-derives from the Fly app name (`cards-server` → prod, anything else → dev), so `cards-server-dev` tags as `dev`. Optionally set `SENTRY_RELEASE` to the git short SHA in the deploy.
    - ⚠️ **Do NOT use Fly's "Set Up Sentry" integration / "Deploy Secrets" button.** It provisions a *separate* Sentry project inside Fly's managed Sentry org (not your `o327796` `cards` project) and points `SENTRY_DSN` at that throwaway project — fragmenting server errors away from the client. Set the secret manually to the `cards` DSN above instead, and discard the Fly-provisioned project (`fly ext sentry list` → destroy, or via the dashboard). Reusing the one `cards` DSN for the JVM server is fine; the Sentry SDK name distinguishes server (java) from client (cocoa/android/kmp) events. *(Optional polish: create a second client-key in the `cards` project so server vs client DSNs can be rotated independently.)*
  - [ ] **(Optional) Connect Sentry to Claude Code via MCP.** Sentry ships an official remote MCP server (`https://mcp.sentry.dev`) — add it so you can query issues / releases / uptime from here. Lets you correlate crashes by release and triage from the terminal. Auth is OAuth against your Sentry org.
  - [ ] **If debug noise floods the project,** blank the debug DSN branch in `AppTelemetry.kt` so only release builds report (the four DSN constants were collapsed to one; re-split if you want per-platform projects later).
- [ ] **Sign up for Grafana Cloud free tier + provision the OTLP endpoint.** Confirmed Fly's bundled `fly-metrics.net` Grafana is locked down (Editor-only, no custom datasources) and their Quickwit deployment is logs-only — traces have no home in Fly's bundle. Grafana Cloud free tier (50 GB logs + 50 GB traces + 10K metrics series, 14-day retention) is the cleanest path. Sign up at grafana.com → grab the OTLP endpoint URL + auth token (basic-auth header with the instance ID + API token) → paste into the server's secret store so the `apps/server` OTel exporter can self-initialize. Engineering picks up the SDK wiring from [todo.md §C Observability](./todo.md). **Also:** add Fly's Prometheus (`https://api.fly.io/prometheus/elijah-dangerfield/`) as a remote datasource in your new Grafana Cloud instance using `flyctl auth token` as a custom HTTP header — that way Fly's infra metrics are queryable from the same Grafana UI where you'll be reading logs + traces, no daily trips to `fly-metrics.net`. Fallback options if Grafana Cloud is ever outgrown: self-host Grafana + Tempo + Loki as a Fly app (Fly's official workaround for the locked bundle), or Honeycomb (paid, best trace-query UX).

---

## Deferred product decisions

These need a call from you before engineering can pick them up. Once decided, move the item into `docs/todo.md` (or close it out).

- [ ] **Notifications (Phase 6).** Opt-in event-driven push only — league placement, friend activity, battle-pass tier, Rare/Legendary achievement unlock. Never time-of-day modeled, never "your chips are lonely," never "come back" pings (see [spec §8](./product/product-spec.md#8-notifications)). Per-category opt-in granularity, not just global on/off. **Explicitly Phase 6** on the roadmap — kept here so it doesn't drift into worker scope.
- [ ] **Cosmetic-pairing rarity tuning after first real playtest.** Today every rarity-EPIC-or-above achievement grants a permanent cosmetic — most notably `SHOW_ROYAL_FLUSH → "Royalty" title` and `SHOW_STRAIGHT_FLUSH → "Suited Run" title`. Real-poker frequencies (royal ~1 in 30K hands, straight flush ~1 in 3K) suggest both stay rare flexes, but bot tables play loose and the bot showdown rate is higher than IRL — once first-week playtest data is in, decide whether either should bump the criterion (e.g. "show 2× royal flush") or downgrade from a title to a lighter cosmetic. Same call applies to the five new `BEAT_*_10` per-bot emote packs if any one bot turns out trivially beatable.
- [ ] **`Profile.Fallback` per-feature audit — designer-in-the-loop pass.** The auth-failure → `Profile.Fallback` path is already wired ([SupabaseProfileRepositoryImpl](../libraries/identity/impl/src/commonMain/kotlin/com/cards/libraries/identity/impl/profile/SupabaseProfileRepositoryImpl.kt)); on bad-network first launch the user reaches `Profile.Fallback(id = clientLocalUuid)` and can play bots immediately. What's left is the per-surface UX call — for each of Home / Shop / Profile / Edit Profile / Claim Account / Inventory / Multiplayer / Settings, pick one of: **cached browse** (renders off local DB, no mutations), **hard-gate** ("you need to be online" — for anything mutating server state), or **soft-gate / read-only** (renders, but mutation affordances disabled). Once the per-surface behavior is decided, engineering picks up the screens. **Cross-impact:** if [Option B or C](./recovery-and-orphaned-accounts.md) is ever picked up from backlog (revival on reinstall), the recovery lookup on splash adds an offline path to think about — block boot vs defer until network returns. Not a V1 concern (we deliberately scope-cut revival), but a check to include in the per-feature audit if/when revival re-enters scope.
  - **Edit Profile specifically** also depends on the avatar-pack server contract — drop-auth on `/v1/avatars` + append-only starter pack — captured in [todo.md §C "Avatar starter pack: lock the server contract"](./todo.md). When the avatars fetch has never landed (first install, offline) the screen falls back to a hardcoded 8-emoji starter list; whether patchMe is allowed to mutate the avatar from that fallback view, and how that error surfaces, is the call to make here.
- [ ] **Abandoned-account deletion model — hard delete vs mark-for-deletion + grace.** Today the design hard-deletes orphaned anonymous accounts on install reuse. Open question raised 2026-05-30: instead of deleting outright, **mark for deletion** with a ~90-day date, notify the user ("this account will be deleted unless you reach out / claim it"), and only purge after the grace if still untouched. Decide: (a) keep hard-delete for the cases we're *certain* about (anon, zero engagement, never claimed) and only soft-mark the ambiguous ones, or (b) soft-mark everything. **Firm rule either way: never delete an account that has *any* real-money purchase.** Notification copy + the reach-out channel are content/dashboard tasks once the model is chosen. Engineering ([todo.md](./todo.md)) picks up the soft-mark flow once decided.
- [ ] **App attestation — Play Integrity / App Attest go/no-go.** Decide whether to require device/app attestation on sensitive endpoints (purchase verification, wallet sync, achievement grants) to make backend abuse from a forged client harder. Tradeoff: meaningful anti-abuse value vs. setup cost + a small legitimate-user failure rate (rooted devices, attestation outages). If yes, it becomes a server gate + per-platform client integration; if no, document why so it doesn't get re-litigated.
- [ ] **Ban policy — who, why, and the process.** Engineering is adding the *enforcement* mechanism (a suspended-account gate — [todo.md §C Abuse & security](./todo.md)). This item is the *policy*: what behavior earns a suspension (collusion / chip-dumping, abuse, fraud chargebacks), who reviews, whether it's reversible, and how the user is told. Needed before the ban switch is meaningfully usable.

---

## Launch readiness — legal & business

Non-engineering gates that block a public launch. None are worker-pickable.

- [ ] **Terms of Service + Privacy Policy.** Required by both app stores and by privacy law (GDPR/CCPA-style). Must cover: it's a play-money game (no cash-out, no gambling), what data we collect (account, gameplay, crash/analytics via Sentry, IAP), data deletion rights (ties to the account-deletion flow), and the abandoned-account deletion policy above. Host them at stable URLs and link from Settings (the "About / Privacy / Terms" Settings entries are an engineering follow-up once the URLs exist). Strongly consider a template service (e.g. Termly / iubenda) or a lawyer for the money-handling + data-deletion clauses.
- [ ] **Liability / insurance posture.** Decide what protection you want before taking real money — e.g. forming an LLC to cap personal liability, and/or business/tech-E&O insurance — for the scenario where a paying user's account or purchases are lost (e.g. an erroneous deletion) and they pursue it. The "never delete accounts with purchases" rule above is the first line of defense; this is the fallback. Talk to an accountant/lawyer; not something to DIY blind.
- [ ] **Finalize product pricing + create the store listings.** Lock the chip-pack price points, then create the matching IAP products in App Store Connect and Google Play Console (productIds must match the catalog the client requests). This is the gate on real billing — the engineering scaffold (`FakeBillingClient` / `DevBillingClient`) is already in place. Until the listings exist, release builds can't transact.
- [ ] **Launch-country selection.** Decide the initial set of countries/regions to release in. Play-money card games still face country-specific rules (some jurisdictions restrict even simulated gambling, some need age gating, tax/VAT differs). Start narrow (e.g. US + a few low-risk markets), expand later. Affects store listing availability + any age-gate / region copy.

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
- [ ] **Confirm CI compiles the Kotlin/Native (iOS) test target.** A test name with parentheses silently broke `:features:room:impl` `commonTest` for the iOS target on 2026-05-31 and went unnoticed for ~a month — which means the CI test job likely only runs the Android JVM target. Check the workflow runs `compileTestKotlinIosSimulatorArm64` (or equivalent) so native-only test breaks fail the build.

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
