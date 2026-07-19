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
- Event registry: [`app-events.md`](app-events.md). Ledger reasons: [`wallet.md`](wallet.md).
- **Billing Health source:** the `billing_events` disposition log (Postgres), one evolving row per store
  transaction. Env is the datasource toggle (`$env`), not a column, since `billing_events` has no
  environment field. Panels stay empty until the server ships the `V88__billing_events.sql` migration;
  the redeem route then upserts a row on every attempt. See [`purchases.md`](purchases.md).

## Alerts (folder Downcard — Engineering → email `owner-email`)

A1 ledger drift ≠ 0 (hourly) · A2 Fly prod down (5m) · A3 Supabase down (10m) · A4 ≥3 installs
reporting `net.backend_unreachable` in 15m · A5 ≥2 `purchase.failed` in 1h · A6 any OOM kill ·
A7 server silent 60m (**paused — enable at launch**). Root policy repeats at most every 24h.
Crash emails: enable Sentry's own new-issue alert (Sentry-side, not Grafana).

## Known gaps (deliberate)

- Offline-emitted events drop (at-most-once delivery) — ENG-25 owns the persistence upgrade.
- "Bust → walk-away %" on Economy is an event-level approximation, not sessionized.
- Matchmaking/multiplayer/Tempo panels are empty until real play resumes post-wipe; RED panels show
  health-check traffic only until launch.
