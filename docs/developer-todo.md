# Developer TODO

Anything only the human (Elijah) can do — credentials, GitHub settings, dashboard / external-service config, device QA, content writing, deferred product decisions. Not part of the engineering punch list ([todo.md](./todo.md) is for that). Automated **workers** must never touch this file. The nightly **reviewer** may append a one-line entry when a PR creates a new human-only follow-up, but may not edit or delete existing entries.

For per-cycle items tied to a specific PR (visual deltas to eyeball, fixes that need device verification *this* cycle), see the PR's "Heads up" section instead — those don't belong here.

Check items off as you do them; delete when the whole section is empty.

---

## Parked engineering (worker-pickable, deferred by choice)

Normal engineering tasks pulled out of [todo.md](./todo.md) on purpose — to pick up later rather than hand to a worker.

- [ ] **Strip WiretapKMP from iOS App Store builds.** The debug network inspector (shake → "Network inspector") is wired into `:libraries:networking:impl`. Android already swaps the real plugin for the zero-overhead noop automatically via `debug`/`releaseImplementation`, but iOS has no such variant split — it's driven by the `cards.wiretap.ios` Gradle property, which **defaults to `true`** so it works in local dev out of the box. An unflagged iOS store build therefore links the real Wiretap klib (dead weight only — `BuildInfo.isDebug` still keeps the inspector from ever opening, so it's not a leak, just binary bloat + shipped debug UI code). **Interim manual step:** pass `-Pcards.wiretap.ios=false` when building the iOS release framework. **Proper fix (this task):** wire that flag into the iOS release build path (Xcode Release config / `release.yml`) so it's automatic and can't be forgotten. See [project memory: Wiretap network inspector](~/.claude/projects/-Users-elijahdangerfield-Workspace-Cards/memory/project_wiretap_inspector.md).

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
- [ ] **Min-supported-version upgrade wall.** Verify that setting `minSupportedVersionCode` above the installed build actually triggers the upgrade-wall screen (and `maintenanceMode` triggers the maintenance screen) on a real device, and that a compliant build passes through. Becomes a live flip-and-check once the DB-backed config ([todo.md §C Phase 1](./todo.md)) lands — until then it needs a server redeploy to test.

---

## Dashboard / external-service config

- [ ] **Supabase email-confirm site URL + redirect URLs + branded template.** Today the confirmation link in the email Supabase sends out still points at the default site URL (localhost) — users can't actually confirm by clicking it. Set Site URL = `cards://auth/confirmed` (matching the deep-link wire-up in [todo.md §A Auth & account onboarding](./todo.md)) and add it to the redirect-URL allowlist, in the dashboard for dev *and* prod. While there, swap the default Supabase template for a Cards-branded one — copy is in the conversation's email-template draft (subject "Confirm your email — Cards" / body with `{{ .ConfirmationURL }}` button + "Then return to Cards" line). The client-side `VerifyEmailScreen` already does `refreshSession()` + `emailConfirmedAt` check; once the deep link is wired, the page auto-refreshes on resume — no manual button tap needed on the same device. Dashboard URLs: https://supabase.com/dashboard/project/yuqrfhdoejonclgbixlw/auth/url-configuration + https://supabase.com/dashboard/project/yuqrfhdoejonclgbixlw/auth/templates
- [ ] **Set up custom SMTP for transactional email.** Supabase's built-in email service has aggressive rate limits (3-4 emails / hour / user) and isn't production-grade — visible as a yellow warning at the top of the Email Templates dashboard. **Recommendation: Resend** (developer-friendly, generous free tier — 100 emails/day + 3000/month, easy DNS setup, great deliverability for transactional). Alternatives: SendGrid (incumbent, more complex setup), Postmark (more expensive, reliability-focused), Amazon SES (cheapest at scale, most setup). Once chosen: sign up, add + verify the sending domain via DNS records, paste the SMTP host + creds into Supabase Auth → Settings → SMTP. Test by triggering a verify-email flow end-to-end.
- [ ] **If you rename the app, update the email templates.** Subject lines, body copy, and the "— Cards" sign-off all live in Supabase Auth → Email Templates. They don't auto-track the app name. Also update any sender domain / from-name configured on the SMTP provider side (Resend / SendGrid / etc.).
- [ ] **Don't schedule the orphan-anon sweep cron.** Per [decisions.md 2026-05-29 — Anon-account cleanup](./decisions.md), the time-based sweep is dormant by design — replaced by a client-driven on-orphan delete (tracked in [todo.md](./todo.md) §A Auth). No cron, no Fly scheduled machine, no GitHub Actions job pointing at `POST /v1/admin/sweep-anonymous-users`. Item kept here so the next dashboard pass doesn't reflexively wire one up. *(A **very conservative** 1-year-inactivity + low-XP + no-purchase sweep is planned post-launch — see [post-launch.md](./post-launch.md) — but still no cron for V1.)*
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
- [ ] **Confirm Postgres backups / point-in-time recovery on the *prod* Supabase project.** Before real users + a real chip ledger, verify PITR (or at least daily backups) is enabled on prod — Supabase's free tier has limited backup retention, so a paid tier or explicit PITR may be needed. Losing balances/purchases with no restore path is unrecoverable once money is involved.

---

## Deferred product decisions

These need a call from you before engineering can pick them up. Once decided, move the item into `docs/todo.md` (or close it out).

- [ ] **Notifications (Phase 6).** Opt-in event-driven push only — league placement, friend activity, battle-pass tier, Rare/Legendary achievement unlock. Never time-of-day modeled, never "your chips are lonely," never "come back" pings (see [spec §8](./product/product-spec.md#8-notifications)). Per-category opt-in granularity, not just global on/off. **Explicitly Phase 6** on the roadmap — kept here so it doesn't drift into worker scope.
- [ ] **Cosmetic-pairing rarity tuning after first real playtest.** Today every rarity-EPIC-or-above achievement grants a permanent cosmetic — most notably `SHOW_ROYAL_FLUSH → "Royalty" title` and `SHOW_STRAIGHT_FLUSH → "Suited Run" title`. Real-poker frequencies (royal ~1 in 30K hands, straight flush ~1 in 3K) suggest both stay rare flexes, but bot tables play loose and the bot showdown rate is higher than IRL — once first-week playtest data is in, decide whether either should bump the criterion (e.g. "show 2× royal flush") or downgrade from a title to a lighter cosmetic. Same call applies to the five new `BEAT_*_10` per-bot emote packs if any one bot turns out trivially beatable.
- [x] **Ban policy — decided 2026-06-19.** Manual for V1: bannable behavior = (1) collusion / chip-dumping, (2) payment fraud / chargebacks, (3) abusive display name or chat/emotes, (4) exploiting bugs / automation for chips or XP, (5) ban evasion. Reviewed manually by Elijah; reversible via an appeal **email**. Enforcement (the `403 {reason}` gate → `BlockingErrorScreen` with the appeal email) is in [todo.md §A](./todo.md); automation (weekly auto-ban sweep + in-app reporting with a ≥3-reports-in-72h rule) is in [post-launch.md](./post-launch.md). Full rationale in [decisions.md](./decisions.md) 2026-06-19.

  _(Resolved this pass and moved out of "deferred product decisions": the `Profile.Fallback` per-surface behavior — reframed as a verification item in [todo.md §A](./todo.md), the app is already offline-first; the abandoned-account deletion model — now a conservative never-delete-real-progress rule, engineering in [todo.md §A](./todo.md) + the 1-yr sweep in [post-launch.md](./post-launch.md); app attestation — deferred to [post-launch.md](./post-launch.md); the 100-hands achievement prize — now chips, not a cosmetic, in [todo.md §Achievements](./todo.md). All recorded in [decisions.md](./decisions.md) 2026-06-19.)_

---

## Launch readiness — legal & business

Non-engineering gates that block a public launch. None are worker-pickable.

- [ ] **Terms of Service + Privacy Policy.** Required by both app stores and by privacy law (GDPR/CCPA-style). Must cover: it's a play-money game (no cash-out, no gambling), what data we collect (account, gameplay, crash/analytics via Sentry, IAP), data deletion rights (ties to the account-deletion flow), and the abandoned-account deletion policy above. Host them at stable URLs and link from Settings (the "About / Privacy / Terms" Settings entries are an engineering follow-up once the URLs exist). Strongly consider a template service (e.g. Termly / iubenda) or a lawyer for the money-handling + data-deletion clauses.
- [ ] **Liability / insurance posture.** Decide what protection you want before taking real money — e.g. forming an LLC to cap personal liability, and/or business/tech-E&O insurance — for the scenario where a paying user's account or purchases are lost (e.g. an erroneous deletion) and they pursue it. The "never delete accounts with purchases" rule above is the first line of defense; this is the fallback. Talk to an accountant/lawyer; not something to DIY blind.
- [ ] **Finalize product pricing + create the store listings.** Lock the chip-pack price points, then create the matching IAP products in App Store Connect and Google Play Console (productIds must match the catalog the client requests). This is the gate on real billing — the engineering scaffold (`FakeBillingClient` / `DevBillingClient`) is already in place. Until the listings exist, release builds can't transact.
- [ ] **Launch-country selection.** Decide the initial set of countries/regions to release in. Play-money card games still face country-specific rules (some jurisdictions restrict even simulated gambling, some need age gating, tax/VAT differs). Start narrow (e.g. US + a few low-risk markets), expand later. Affects store listing availability + any age-gate / region copy.
- [ ] **Store data-safety / privacy disclosures.** Both stores require a structured privacy declaration that must match the Privacy Policy *and* what the app actually collects (account, gameplay, Sentry crash/analytics, IAP): Apple "Privacy Nutrition Labels" (ASC → App Privacy) + Google Play "Data Safety" form (Play Console → App content). Fill both from the same source of truth as the policy — a mismatch is a common rejection and a compliance risk.
- [ ] **Age / content ratings.** Apple age-rating questionnaire + Google IARC content rating. Play-money poker trips the "simulated gambling" questions — answer honestly (no real-money cash-out) and expect a mature-ish rating. Wrong answers risk rejection or bad shelf placement.
- [ ] **Support contact + public support URL.** Both stores require a support URL on the listing. It's also where account-deletion and ban appeals land (the ban-gate's `appeal_url`). A support email + a one-page web contact is enough to start.
- [ ] **Web-accessible account-deletion path.** Google Play requires users be able to *initiate* account/data deletion from outside the app (a public URL), not only in-app. In-app delete already covers Apple; add a web page that explains + triggers deletion (or routes to support) and link it from the Play listing.
- [ ] **Store listing creative assets.** App icon, per-device screenshots, Android feature graphic, short/full descriptions, promo text, keywords. Not engineering, but gates submission and takes longer than expected — start early.

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
