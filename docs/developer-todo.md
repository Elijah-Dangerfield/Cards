# Developer TODO

Anything only the human (Elijah) can do — credentials, GitHub settings, dashboard / external-service config, device QA, content writing, deferred product decisions. Not part of the engineering punch list ([todo.md](./todo.md) is for that). Automated **workers** must never touch this file. The nightly **reviewer** may append a one-line entry when a PR creates a new human-only follow-up, but may not edit or delete existing entries.

For per-cycle items tied to a specific PR (visual deltas to eyeball, fixes that need device verification *this* cycle), see the PR's "Heads up" section instead — those don't belong here.

Check items off as you do them; delete when the whole section is empty.

---

## Dashboard / external-service config

- [ ] **Test email sign-up + verification end-to-end via Resend** (sign up with a real inbox → confirm email arrives → tap link → lands back in-app confirmed).
- [ ] **Put the `cards` DSN in the Fly secret store on the server.** The server-side Sentry SDK is **already wired** — `installSentry(config.sentry)` runs at startup and reads `SENTRY_DSN` from the env (`SentryConfig.fromEnv` in [`ServerConfig.kt`](../apps/server/src/main/kotlin/com/cards/server/config/ServerConfig.kt)), staying dormant while the DSN is blank. So the only step is the secret:
  ```
  fly secrets set SENTRY_DSN="https://2010decd1b11057a4038b99bcd75878b@o327796.ingest.us.sentry.io/4511478399565824" -a cards-server-dev
  ```
  That triggers a rolling deploy; the server inits Sentry on boot. `SENTRY_ENVIRONMENT` auto-derives from the Fly app name (`cards-server` → prod, anything else → dev), so `cards-server-dev` tags as `dev`. Optionally set `SENTRY_RELEASE` to the git short SHA in the deploy.
  - ⚠️ **Do NOT use Fly's "Set Up Sentry" integration / "Deploy Secrets" button.** It provisions a *separate* Sentry project inside Fly's managed Sentry org (not your `o327796` `cards` project) and points `SENTRY_DSN` at that throwaway project — fragmenting server errors away from the client. Set the secret manually to the `cards` DSN above instead, and discard the Fly-provisioned project (`fly ext sentry list` → destroy, or via the dashboard). Reusing the one `cards` DSN for the JVM server is fine; the Sentry SDK name distinguishes server (java) from client (cocoa/android/kmp) events. *(Optional polish: create a second client-key in the `cards` project so server vs client DSNs can be rotated independently.)*
- [ ] **(Optional) Connect Sentry to Claude Code via MCP.** Sentry ships an official remote MCP server (`https://mcp.sentry.dev`) — add it so you can query issues / releases / uptime from here. Lets you correlate crashes by release and triage from the terminal. Auth is OAuth against your Sentry org.
- [ ] **If debug noise floods the project,** blank the debug DSN branch in `AppTelemetry.kt` so only release builds report (the four DSN constants were collapsed to one; re-split if you want per-platform projects later).
- [ ] **Sign up for Grafana Cloud free tier + provision the OTLP endpoint.** Confirmed Fly's bundled `fly-metrics.net` Grafana is locked down (Editor-only, no custom datasources) and their Quickwit deployment is logs-only — traces have no home in Fly's bundle. Grafana Cloud free tier (50 GB logs + 50 GB traces + 10K metrics series, 14-day retention) is the cleanest path. Sign up at grafana.com → grab the OTLP endpoint URL + auth token (basic-auth header with the instance ID + API token) → paste into the server's secret store so the `apps/server` OTel exporter can self-initialize. Engineering picks up the SDK wiring from [todo.md §C Observability](./todo.md). **Also:** add Fly's Prometheus (`https://api.fly.io/prometheus/elijah-dangerfield/`) as a remote datasource in your new Grafana Cloud instance using `flyctl auth token` as a custom HTTP header — that way Fly's infra metrics are queryable from the same Grafana UI where you'll be reading logs + traces, no daily trips to `fly-metrics.net`. Fallback options if Grafana Cloud is ever outgrown: self-host Grafana + Tempo + Loki as a Fly app (Fly's official workaround for the locked bundle), or Honeycomb (paid, best trace-query UX).
- [ ] **Wire a Grafana alert rule (+ notification contact point) for the "Ledger conservation drift" stat.** ECON-1 put the drift query (`SUM(wallets.balance) - SUM(wallet_events.delta)`, anything non-zero is money the ledger can't explain) on the `cards-economy` dashboard as a red-threshold stat, but a real alert needs a contact point (email? Slack?) — your call. Grafana → Alerting → Contact points, then an alert rule over the same Postgres query.
- [ ] **Confirm Postgres backups / point-in-time recovery on the *prod* Supabase project.** Before real users + a real chip ledger, verify PITR (or at least daily backups) is enabled on prod — Supabase's free tier has limited backup retention, so a paid tier or explicit PITR may be needed. Losing balances/purchases with no restore path is unrecoverable once money is involved.

---

## Deferred product decisions

These need a call from you before engineering can pick them up. Once decided, move the item into `docs/todo.md` (or close it out).

- [ ] **Notifications (Phase 6).** Opt-in event-driven push only — league placement, friend activity, battle-pass tier, Rare/Legendary achievement unlock. Never time-of-day modeled, never "your chips are lonely," never "come back" pings (was spec §8; the product-spec doc is deleted, principle stands). Per-category opt-in granularity, not just global on/off. **Explicitly Phase 6** on the roadmap — kept here so it doesn't drift into worker scope.
- [ ] **Cosmetic-pairing rarity tuning after first real playtest.** Today every rarity-EPIC-or-above achievement grants a permanent cosmetic — most notably `SHOW_ROYAL_FLUSH → "Royalty" title` and `SHOW_STRAIGHT_FLUSH → "Suited Run" title`. Real-poker frequencies (royal ~1 in 30K hands, straight flush ~1 in 3K) suggest both stay rare flexes, but bot tables play loose and the bot showdown rate is higher than IRL — once first-week playtest data is in, decide whether either should bump the criterion (e.g. "show 2× royal flush") or downgrade from a title to a lighter cosmetic. Same call applies to the five new `BEAT_*_10` per-bot emote packs if any one bot turns out trivially beatable.

---

## Launch readiness — legal & business

Non-engineering gates that block a public launch. None are worker-pickable.

- [ ] **Terms of Service + Privacy Policy.** Required by both app stores and by privacy law (GDPR/CCPA-style). Must cover: it's a play-money game (no cash-out, no gambling), what data we collect (account, gameplay, crash/analytics via Sentry, IAP), data deletion rights (ties to the account-deletion flow), and the abandoned-account deletion policy above. Host them at stable URLs and link from Settings (the "About / Privacy / Terms" Settings entries are an engineering follow-up once the URLs exist). Strongly consider a template service (e.g. Termly / iubenda) or a lawyer for the money-handling + data-deletion clauses.
- [ ] **Liability / insurance posture.** Decide what protection you want before taking real money — e.g. forming an LLC to cap personal liability, and/or business/tech-E&O insurance — for the scenario where a paying user's account or purchases are lost (e.g. an erroneous deletion) and they pursue it. The "never delete accounts with purchases" rule above is the first line of defense; this is the fallback. Talk to an accountant/lawyer; not something to DIY blind.
- [ ] **Finalize product pricing + create the store listings.** Lock the chip-pack price points, then create the matching IAP products in App Store Connect and Google Play Console (productIds must match the catalog the client requests). This is the gate on real billing — the engineering scaffold (`FakeBillingClient` / `DevBillingClient`) is already in place. Until the listings exist, release builds can't transact.
- [ ] **IAP receipt-validation credentials (Apple + Google).** The validators are **already built and shipped** (`AppStoreReceiptValidator` / `GooglePlayReceiptValidator`, see `apps/server/.../data/`); they stay dormant + fail-closed until you set their Fly secrets on `cards-server-dev` (and `-prod` at launch). Names below are exactly what `BillingConfig.fromEnv` (`apps/server/.../config/ServerConfig.kt`) reads — don't invent others.
  - **Apple** — no App Store Connect key needed: verification is **offline** JWS validation of the StoreKit 2 signed transaction against bundled Apple root CAs. Just: `fly secrets set APPLE_BUNDLE_ID=com.dangerfield.cards.Cards APPLE_STORE_ENVIRONMENT=Sandbox -a cards-server-dev` (flip to `Production` on the prod app; note the bundle id is `com.dangerfield.cards.Cards` — iOS appends `.Cards`). `APPLE_APP_APPLE_ID=<numeric app id>` is **required to verify production (real-money) receipts** since BILL-7 — sandbox/TestFlight validate without it, so reviewers pass but **real buyers fail** if it's missing (see prod-secrets note at bottom). Leave unset only on a sandbox-only dev server; it exists in App Store Connect → App Information → Apple ID once the app record is created.
  - **Google** — needs a service account with the **Android Publisher API** enabled (grant it access in Play Console → Users & permissions), then download its JSON key: `fly secrets set GOOGLE_PLAY_PACKAGE_NAME=com.dangerfield.cards GOOGLE_PLAY_SERVICE_ACCOUNT_JSON="$(cat service-account.json)" -a cards-server-dev`. (May be the same service account as the `PLAY_SERVICE_ACCOUNT_JSON` GitHub upload secret if it holds both roles.)
  - Gated on the store-listings item below (productIds must exist first). ~~Once secrets are set + listings exist, flip the `billing.realPurchasesEnabled` config flag on~~ *(2026-07-07: flag now defaults **on** — see decisions.md; just make sure no config override sets it off.)*
  - *(2026-07-07: **Apple secrets are set on BOTH `cards-server-dev` and `cards-server-prod`**, with the corrected bundle id `com.dangerfield.cards.Cards` — the `com.dangerfield.cards` value above was wrong, iOS appends `.Cards`. Google secrets still pending.)*
- [ ] **Launch day: flip prod Apple receipt validation from Sandbox to Production.** Pre-launch, `cards-server-prod` accepts **Sandbox** receipts so TestFlight testers exercise real purchases (set 2026-07-07). The moment the app is live on the App Store, run `fly secrets set APPLE_STORE_ENVIRONMENT=Production -a cards-server-prod`. After the flip, TestFlight purchases are rejected **by design** — test future chip packs on a debug build against dev instead. See [decisions.md](./decisions.md) 2026-07-07 for the rationale and the revisit trigger (a guarded dual-environment validator, only if post-launch TestFlight purchasing is ever missed).
- [ ] **(Optional, post-launch) Refund / chargeback webhooks.** Wire **App Store Server Notifications V2** (ASC → your app → App Information → URL) and **Google Real-time Developer Notifications** (Play Console → Monetization setup → a Cloud Pub/Sub topic) at server webhook routes so refunds/chargebacks can claw back granted chips. Not a launch blocker — redemption is already idempotent and one-directional without it; this just closes the refund-abuse loop.
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

### Fly server env (set via `fly secrets set <NAME>=... -a cards-server-dev`)
- [ ] `APPEAL_URL` — public page/email a banned user is pointed at. The server now returns it verbatim in the ban-block `403` so the app can show an "appeal this" link; left blank the block still works but offers no appeal affordance. Ties to the support-URL item above. Set once the support/appeal page exists.
- [ ] `APPLE_BUNDLE_ID`, `APPLE_STORE_ENVIRONMENT` (+ optional `APPLE_APP_APPLE_ID`) — Apple IAP receipt validation (offline JWS; no `.p8` key). See "IAP receipt-validation credentials" above.
- [ ] `APPLE_APP_APPLE_ID` on the **prod** app (`fly secrets set APPLE_APP_APPLE_ID=<numeric id> -a cards-server`) — since BILL-7 this is **required** for verifying production-signed receipts (Apple refuses to build the Production verifier without it; the server degrades to sandbox-only and logs the gap). Numeric id lives in App Store Connect → App Information → Apple ID. Sandbox/TestFlight purchases work without it.
- [ ] `GOOGLE_PLAY_PACKAGE_NAME`, `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` — Google Play receipt validation. See the same item above.

---

## Already done — for reference

These are already set so you don't have to:
- `FLY_API_TOKEN_DEV` — generated 2026-05-19 via `fly tokens create deploy -a cards-server-dev --expiry 8760h --name github-actions-deploy`. Used by `server-deploy.yml`.
- `CARDS_ADMIN_API_TOKEN_DEV` (repo) + `ADMIN_API_TOKEN` (Fly app env on cards-server-dev) — random 64-char hex set in both places so the sweep workflows can hit the admin endpoints. Generated 2026-05-19.
- Actions setting **"Allow GitHub Actions to create and approve pull requests"** — required for release-please.
