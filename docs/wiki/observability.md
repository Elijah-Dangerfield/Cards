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

Eight rules, labelled `severity: critical|warning`. A1 ledger drift ≠ 0 (hourly, critical) ·
A2 Fly prod down (5m, critical) · A3 Supabase down (10m, critical) · A4 ≥3 installs reporting
`net.backend_unreachable` in 15m (critical) · A5 purchase success rate low (critical) · A6 any
OOM kill (critical) · A7 server silent 60m (warning, **live since launch 2026-07-24**) ·
A8 store dropped chip-pack SKUs in prod (hourly, warning). Root policy repeats at most every 24h.

**A8 covers the blind spot A5 structurally cannot see (ENG-43, CARDS-8V).** A5 divides purchase
completions by attempts, so a store that recognizes none of our SKUs produces zero visible packs,
zero attempts, and a permanently green A5 while the shop sells nothing. A8 fires on the
`shop.catalog_skus_dropped` event instead. It is a warning rather than critical on purpose: the fix
lives in App Store Connect / Play Console during business hours, so paging overnight buys nothing.

**Routing (live 2026-07-24):** the notification policy routes `severity=critical` → contact point
`downcard-critical` (email + IRM mobile push); everything else (warnings, e.g. A7) → `owner-email`
(email only). `downcard-critical`'s `oncall` receiver posts to the IRM `grafana_alerting`
integration "Downcard Critical" → escalation chain "Notify Elijah" → the owner's phone. So A1–A6
reach phone + email; A7 emails only. Contact points + policy were written with `X-Disable-Provenance`
so they stay UI-editable. Crash emails: enable Sentry's own new-issue alert (Sentry-side, not Grafana).

**Known false-alarm class:** A2/A3 use `no_data = Alerting`, so a dead metrics scrape (expired
Supabase key, Fly metrics hiccup) pages you even when prod is fine. Better a false page than a
missed outage, but check the datasource is actually scraping before assuming prod is down.

## Admin-surface probes (ENG-41)

`/v1/admin` can mint chips, so it's the one route where guessing the secret pays for itself. Every
rejected `X-Admin-Token` now emits one WARN from the `AdminAuth` logger carrying the method, path,
client IP, and *why* (no token presented / token mismatch / no token configured server-side). The
presented value is never logged — a near-miss guess in the log store is a secret in a softer place
than the secret store. Query it in Loki with the server stream filtered to that logger.

The route also sits in a dedicated bucket where **only failed attempts count**: 20 wrong tokens per
IP per hour, correct ones free. A flat cap would have throttled the hosted config console, which
fires several authenticated reads per page load, and throttling a caller who already holds the
secret protects nothing. A scanner probed `POST /v1/admin/grant-chips` on prod in 2026-08 and left
no trace on any dashboard; that's the gap this closes.

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
- **Emulator / side-load ANR at the PairIP license gate** (Sentry class `CARDS-BR`) — an ANR (or
  native crash) whose stack is *entirely* Android framework + native graphics (e.g. main thread in
  `HardwareRenderer.setStopped` → `pthread_cond_wait`, RenderThread stuck in `eglSwapBuffers` →
  SurfaceFlinger), **with zero first-party frames**, on a **non-retail build image** (`os.build`
  contains `sdk_phone_arm64` / `test-keys`; the Sentry `simulator` flag can read `false` when the
  emulator's model/brand are spoofed to a real device) and/or `isSideLoaded=true` with
  `com.pairip.licensecheck.LicenseActivity` foregrounded. That's Play's licensing wrapper engaging on
  an unlicensed side-loaded copy, plus a software-GPU `swapBuffers` stall — the app working as
  intended, not our code. **Gate carefully:** this exemption needs *no app frames* AND the
  emulator/side-load fingerprint. A real ANR with Downcard frames, or one recurring across many
  *retail* installs, is a genuine bug — file it.

## Durable vs ephemeral panels

**Loki is capped at 31 days and fails loudly past it.** A 90-day query returns
`this data is no longer available, it is past now - max_query_lookback (31d)`. Widening the time
picker does not widen the answer, so any panel built on client or server *events* has a hard
horizon. Postgres has no such limit: `profiles`, `wallets`, `wallet_events`, `xp_events`,
`player_stat_events`, `table_sessions`, `achievements_earned` and `billing_transactions` are the
durable record.

**The rule:** a panel may only say *all time*, *ever*, *lifetime* or *since launch* if every one of
its queries is Postgres. Audited 2026-09-01 and all existing claims pass — `Accounts (all time)`,
`Chip sources/sinks (all time)`, `Distinct achievements ever earned`, `Cumulative accounts
(all-time growth)` and `Total chip supply over time` are Postgres-backed. Keep it that way.

**The subtler trap is the panel that claims nothing.** A Loki panel labelled "over the dashboard
range" is honest about its window and silent about its ceiling, so a 90-day picker quietly answers
for 31. Every Loki-heavy dashboard now says so in its description.

**Prefer Postgres when both can answer the same question.** Converted 2026-09-01:

| Panel | Was | Now |
|---|---|---|
| Pulse → Hands per day by mode | `game.started` events (Loki) | `player_stat_events` — durable, and already reaches back to 2026-07-24, further than Loki can |
| Gameplay → Player hand win % | `hand.completed` events (Loki) | `player_stat_events.won` |
| Gameplay → Bots vs multiplayer | `game.started` events (Loki) | `player_stat_events.mode` |

**Still Loki-only, because no DB equivalent exists:** device-based DAU/sessions and installs (keyed
by `install_id`, which only reaches Postgres via `profiles` for accounts that finished onboarding),
crash-free rates (`previous_exit` on `app.launched`), the onboarding step funnel (step views are
client-side), matchmaking friction, and client reliability events. These are legitimately
ephemeral; label them, don't pretend.

## What the suite cannot see (audited 2026-09-01)

Read this before concluding "the dashboards are green, so we're fine." ENG-45 ran for eight days
with a real user's sync taking 300-500 seconds and **every panel stayed green**. The audit below is
what that cost us.

**Slow-but-successful is the blind spot that matters.** A request returning `200 OK` after eight
minutes produces no error, no 5xx, no crash, and trips none of A1-A8. Three separate things hid it:

1. **The RED panels were ~100% health checks.** `/_health` runs every 30s = ~2,880 requests/day
   against ~3,000 total. Real user traffic was a rounding error, so `p99` read **4.95 ms** flat
   through the entire incident. *Fixed 2026-09-01: the latency, request-rate and top-routes panels
   now exclude `http_route="/_health"`.*
2. **The histogram cannot express it anyway.** The largest finite bucket is `le=10` seconds, so
   anything slower collapses into `+Inf` and `histogram_quantile` can only ever say "10s+". Worse,
   two instrumentation versions emit mismatched bounds (`le="10"` and `le="10.0"`), which
   `sum by (le)` silently treats as different buckets. **Do not trust these quantiles above ~1s.**
3. **Nothing charted server-side duration from a source that could represent it.** *Fixed
   2026-09-01: dc-infra → "Slowest requests (server logs)" reads the `CallLogging` `... in NNNms`
   line from Loki, which has no upper bound. Empty is healthy.* The alert half is ENG-46.

**Crash-free rates were overstated.** The formula counted only `previous_exit` in
(`crash`, `anr`) and silently ignored **`oom`**, which outnumbers ANR 8-to-1 in the live
population. *Fixed 2026-09-01: OOM now counts as a crash.* The iOS half is still blind —
`previous_exit=unknown` is ~45% of all launches until MetricKit lands (ENG-25), and those sit in
the denominator but can never reach the numerator, so the number reads **higher than reality**.
Treat crash-free as an Android figure with an optimistic bias.

**Metrics that do not exist.** These are scraped by nothing, so their panels/queries are
permanently dead rather than reporting zero. A `0` from any of them means "no data", not "no
problem":

| Metric | Panel |
|---|---|
| `pgrst_db_pool_waiting`, `pgrst_db_pool_timeouts_total` | dc-infra → DB pool *(repurposed 2026-09-01)* |
| `fly_instance_exit_oom` | dc-infra → Restarts (24h) — *query deleted 2026-09-01* |
| `fly_app_hard_limit_reached_count` | dc-infra → Concurrency vs limits — *query deleted 2026-09-01* |
| `fly_instance_cpu_throttle` | dc-infra → CPU % — *query deleted 2026-09-01* |

**Pulse was trimmed 2026-09-01** from 33 panels to 27. The "Users & installs" row moved to
dc-users, which does it properly; "Median game duration" was a proxy for a `session_duration_sec`
attribute that now actually exists; "Client warnings & errors" duplicated the by-install panel on
dc-users. Pulse is a 30-second glance, and 33 panels is a second dashboard wearing its name.

**Postgres `auth` schema is denied** (`SQLSTATE 42501`), so ban state (`auth.users.banned_until`)
cannot be charted. dc-users documents this in place rather than showing an empty panel.

## Known gaps (deliberate)

- Offline-emitted events drop (at-most-once delivery) — ENG-25 owns the persistence upgrade.
- "Bust → walk-away %" on Economy is an event-level approximation, not sessionized.
- Matchmaking/multiplayer/Tempo panels are empty until real play resumes post-wipe.
