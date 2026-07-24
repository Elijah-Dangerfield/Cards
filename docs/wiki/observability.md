# Observability: which dashboard answers which question

The Grafana suite (built 2026-07-11) is organized so the owner can glance for 30 seconds and know
if anything needs attention. Three folders, seven dashboards, one rule: **if Pulse row 1 is all
green, close the tab.**

| Folder | Dashboard (uid) | Headline question |
|---|---|---|
| Downcard — Product | **Pulse** (`dc-pulse`, the home dashboard) | Is the app healthy and are people getting good usage? |
| Downcard — Product | Gameplay & Matchmaking (`dc-gameplay`) | How do people play — bots vs MP, friends vs global, sharks, matchmaking friction? |
| Downcard — Product | Funnel & Progression (`dc-funnel`) | Do new users make it through onboarding; do players level and unlock? |
| Downcard — Business | Cards — Economy (`cards-economy`) | In-game chips: supply, balances, busts, faucet vs sink, product adoption |
| Downcard — Business | Revenue (`dc-revenue`) | Real money only: $, % payers, per-pack breakdowns (pinned prod) |
| Downcard — Engineering | Infra (`dc-infra`) | Backend/DB health: Fly machine, Supabase, RED, memory/OOM |
| Downcard — Engineering | Game Server Pipeline (`cards-gameplay`) | Turn-processing internals from Tempo traces |
| Downcard — Engineering | Billing Health (`dc-billing-health`) | Chip-purchase recovery pipeline: stuck purchases with age, mismatch rate, grant-on-replay, refunds, wedged escalations, retry distribution, sliceable by reason |

## Conventions

- **Environment toggle** on every dashboard (prod default): a Postgres *datasource* variable plus a
  `loki_env`/`env` custom variable for Loki/Fly queries. Exceptions: Revenue is pinned prod; Infra's
  database panels are prod-only (no dev Supabase scrape job exists).
- **Loki query shape:** stream labels are only `service_name` + `deployment_environment`; everything
  else (`event_name`, `session_id`, `install_id`, attrs, `detected_level`) is structured metadata —
  filter with pipes, never line filters. Users/sessions are counted from `app.foregrounded`
  (never `app.launched`; ENG-24 orphan session).
- **Crash-free rates** come from the `previous_exit` attribute on `app.launched` (next-launch
  marking) — not Sentry; the Grafana Sentry datasource can only chart issues/events/usage. Sentry
  stays the stack-trace tool. iOS reports `previous_exit=unknown` until MetricKit lands (ENG-25).
- **Warn+ client logs** land in Loki behind `telemetry.klogForwardingEnabled` (default on):
  `{service_name="cards-client"} | detected_level=~"warn|error"`.
- **Platform split:** every client event carries a `platform` structured-metadata value
  (`android`/`ios`). Pulse's "Growth, money & platform" section splits active users and sessions by
  it; filter any Loki query with `| platform="ios"`. There's deliberately no crash-free-by-platform
  panel because iOS crash-free is blind until MetricKit (ENG-25).
- **Pulse growth section:** revenue-this-month + %-who-pay (mirrored from Revenue, prod-pinned),
  new-vs-returning players (engaged actives, Postgres `profiles.created_at` + the event ledgers), and
  the platform split. D1/D7 **retention** lives on **Funnel** (`dc-funnel`), not Pulse — it's a cohort
  grid over history, not a 30-second glance. Retention/new-vs-returning are *engaged* (took an action),
  account-based; they won't reconcile with the device-based `app.foregrounded` DAU.
- Event registry: [`app-events.md`](app-events.md). Ledger reasons: [`wallet.md`](wallet.md).
- **Billing Health source:** the `billing_events` disposition log (Postgres), one evolving row per store
  transaction. Env is the datasource toggle (`$env`), not a column, since `billing_events` has no
  environment field. Panels stay empty until the server ships the `V88__billing_events.sql` migration;
  the redeem route then upserts a row on every attempt. See [`purchases.md`](purchases.md).

## Alerts (folder Downcard — Engineering)

Seven rules, labelled `severity: critical|warning`. A1 ledger drift ≠ 0 (hourly, critical) ·
A2 Fly prod down (5m, critical) · A3 Supabase down (10m, critical) · A4 ≥3 installs reporting
`net.backend_unreachable` in 15m (critical) · A5 purchase success rate low (critical) · A6 any
OOM kill (critical) · A7 server silent 60m (warning, **live since launch 2026-07-24**). Root
policy repeats at most every 24h.

**Routing (live 2026-07-24):** the notification policy routes `severity=critical` → contact point
`downcard-critical` (email + IRM mobile push); everything else (warnings, e.g. A7) → `owner-email`
(email only). `downcard-critical`'s `oncall` receiver posts to the IRM `grafana_alerting`
integration "Downcard Critical" → escalation chain "Notify Elijah" → the owner's phone. So A1–A6
reach phone + email; A7 emails only. Contact points + policy were written with `X-Disable-Provenance`
so they stay UI-editable. Crash emails: enable Sentry's own new-issue alert (Sentry-side, not Grafana).

**Known false-alarm class:** A2/A3 use `no_data = Alerting`, so a dead metrics scrape (expired
Supabase key, Fly metrics hiccup) pages you even when prod is fine. Better a false page than a
missed outage, but check the datasource is actually scraping before assuming prod is down.

## Known-benign client signals (not incidents)

The app working as designed. Don't file these as bugs; treat them as noise on the error panels.

- **Banned 403** (`{"reason":"banned"}`, e.g. `POST /v1/equipment/sync`, Sentry class `CARDS-BG`) —
  a banned user's blocked request. Being removed at source by the banned circuit breaker (ENG-35):
  once shipped, a banned client stops firing and stops error-logging these entirely.
- **User-cancelled purchases** (`purchase.cancelled`) — a tap-then-back-out, not a failure. The
  purchase tiles and the A5 alert already exclude cancellations.
- **One-off `net.backend_unreachable`** from a single install — that user's own connectivity. Only
  meaningful in aggregate; A4 fires at ≥3 installs. Pulse's "Client reliability events" table is
  dominated by these by design (its description says so).

## Known gaps (deliberate)

- Offline-emitted events drop (at-most-once delivery) — ENG-25 owns the persistence upgrade.
- "Bust → walk-away %" on Economy is an event-level approximation, not sessionized.
- Matchmaking/multiplayer/Tempo panels are empty until real play resumes post-wipe; RED panels show
  health-check traffic only until launch.
