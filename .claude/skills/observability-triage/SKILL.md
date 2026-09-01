---
name: observability-triage
description: Triage proactive telemetry for Cards — unresolved Sentry crashes/errors that have NO user feedback attached, plus Grafana firing alerts and dashboard anomalies — into docs/todo.md items or no-action. The machine-side complement to feedback-triage. Use when asked to "triage sentry", "check crashes", "triage errors", "check the alerts", "check the dashboards", "observability triage", or on a nightly schedule.
---

# Observability triage

Turn what the app's telemetry is saying into either a worker-pickable todo or a closed no-action item, with the root cause already investigated. Where `feedback-triage` starts from a human pressing "send feedback", this starts from the signals themselves: **Sentry** crashes/errors nobody reported, and **Grafana** alerts/dashboards. Designed to run unattended nightly, but works interactively too.

This skill and `feedback-triage` split the same telemetry cleanly: **feedback-triage owns the feedback carrier/twin issues; you own everything else.** Never double-handle a feedback issue here.

## Fixed coordinates

Stable for this repo — don't rediscover them every run, but reconfirm if a call 404s.

- **Sentry:** org `elijah-dangerfield`, project `cards`, region `https://us.sentry.io`.
- **Grafana datasources:** Prometheus `grafanacloud-prom`, Tempo `grafanacloud-traces`, Loki `grafanacloud-logs`, Fly metrics `dfrewa7ebtkhsc`, Sentry-in-Grafana `dfrt33501quwwe`, Postgres prod `ffrewas5byf40d` / dev `dfrex4f7bg7b4b`.
- **Correlation key:** `session_id` (a per-session UUID) ties a session across Sentry, the server Loki stream (`{service_name="cards-server"}`), and the client stream (`{service_name="cards-client"}`). Also `install_id`, `user.id`. Same mechanics `feedback-triage` uses in its steps 3–4 — lean on that skill's queries rather than re-deriving them.
- **Alerts** (folder `downcard-engineering`): A1 ledger drift `ffrtc58ufemf4f` · A2 Fly prod down `dfrtc5hgmsoaod` · A3 Supabase down `afrtc618qul1ce` · A4 clients can't reach backend `ffrtc70x74xz4c` · A5 purchase failures `bfrtc7fm94qgwb` · A6 server OOM `afrtc69uy8mwwe` · A7 server silent `ffrtc7l6fzs3ke` (**paused until launch**).
- **Dashboards:** `dc-pulse` (org home), `dc-infra`, `cards-economy`, `dc-gameplay`, `dc-funnel`, `cards-gameplay`, `dc-revenue`. **The question→dashboard map is `docs/wiki/observability.md` — read it before sweeping; it also lists the alerts, the known-empty panels, and the "Known-benign client signals" (banned-403 `CARDS-BG`, user-cancellations, one-off `net.backend_unreachable`) that must NOT be filed as bugs.**
- **Todo destination:** `docs/todo.md` (worker-pickable; strict format — see step 5). Larger/blurrier work → one-liner in `docs/backlog.md`.
- **Processed ledger:** `docs/agent/observability-log.md` — append-only record of every signal already handled (Sentry issues keyed by short-id, Grafana signals by a stable slug), so reruns skip it. Create it if missing.
- **Case files:** `docs/agent/feedback-cases/<sentry-short-id>.md` — same directory and template `feedback-triage` uses, so a worker reads one bundle regardless of which triage filed the item. Write one for any Sentry issue you turn into a todo.

## Two intake channels

1. **Sentry issues** — unresolved crashes/errors with **no feedback attached**. Crashes (fatal/unhandled) outrank caught errors; a money-touching message outranks a cosmetic one; a noisy warning firing thousands of times matters (real bug or log spam worth silencing) but ranks below anything that ends a session.
2. **Grafana** — a **firing alert** is the strongest machine signal (treat like a crash); then **dashboard anomalies** the alerts don't cover.

## Procedure

### 1. Sentry — list unresolved crashes/errors

```
search_issues(organizationSlug='elijah-dangerfield', projectSlugOrId='cards', query='is:unresolved', sort='freq', period='30d')
```

**Set `period` explicitly** (`30d`, or `90d` when catching up) — the default window is narrow, and a shorter one silently drops issues that are still unresolved but last-seen a few days ago.

Then **drop the ones you don't own:**

- **Feedback issues** — title exactly `User feedback` / `Bug report`, or `issue.category:feedback`. `feedback-triage` owns these; skip them here.
- **Anything already in `docs/agent/observability-log.md`** (by short-id) — already dispositioned. **Re-open only if materially worse** (crash count jumped an order of magnitude, a flapping alert is now sustained): file fresh and note in the ledger why you re-opened. Also **re-validate the pointer target** on a prior no-action: if `feedback-triage` has since filed a closer-owning todo (e.g. a Sentry redeem crash you once pointed at an infra item is now better owned by a billing todo), repoint the ledger entry at it.
- **Anything `feedback-triage` filed tonight** — cross-check `docs/agent/feedback-log.md` and the current `docs/todo.md` so you don't duplicate a root cause it already caught (it runs just before you).

Whatever's left is yours. Rank by the severity order above. If nothing new, write "no new Sentry signals" to the summary and move to Grafana.

### 2. Sentry — investigate each issue

Read the top event (`get_sentry_resource` — pass the full issue `url=`, e.g. `https://elijah-dangerfield.sentry.io/issues/CARDS-9H/`). Extract the stack trace, scope tags (`session_id`, `install_id`, `route`, `room_code`, `build_type`, `release_channel`, `platform`), and breadcrumbs. Then reconstruct the session by `session_id` exactly as `feedback-triage` does:

- **Frontend:** breadcrumbs + `search_events(dataset='errors', query='session_id:<id>')` + the client Loki stream `{service_name="cards-client", deployment_environment="prod"} | session_id="<id>"`.
- **Backend:** Tempo `{ .session_id = "<id>" }` for the trace tree (gameplay spans `submit_intent`→`validate_intent`→`engine.apply_intent`→`state_mutate`→`ws_send` all carry it via baggage); Loki `{service_name="cards-server"} | session_id="<id>"`. **Structured metadata → pipe matchers, never line filters.**

`analyze_issue_with_seer` is fine for a fast code-level hypothesis — treat it as a lead, confirm against the trace/logs. Empty Tempo/Loki for a `session_id` usually means a dev build that never reached the cloud backend or a pre-correlation client — say so and lower confidence; don't fabricate a backend cause.

### 3. Grafana — firing alerts first

```
alerting_manage_rules(operation='list', states=['firing', 'pending'])
```

This returns **`null` (not `[]`) when nothing matches** — `null` means **no alert firing**, not an error; don't misread it. A *firing* alert is the strongest signal you'll get — triage it like a crash. Pull the rule's query and the window around the trip (`get_alert_group` / re-run the underlying PromQL/LogQL/TraceQL), find what tripped it, and file a todo. **A1 (ledger drift) and A5 (purchase failures) touch money — always `[P0]`.** A2/A3/A6 are prod/infra down. A `pending` alert is a warning worth a look but not yet an incident.

### 4. Grafana — sweep the dashboards for anomalies the alerts miss

Read `docs/wiki/observability.md` first, then start at **Pulse** (`dc-pulse`) — if row 1 is all green, the app is broadly healthy; be brief. Then glance at the rest, biased to the last 24h:

- **`dc-infra`** — Fly restarts/OOM, Supabase pool saturation, RED error-rate spikes, memory creeping toward the 512MB ceiling.
- **`cards-economy`** — chip-ledger sanity, an implausible faucet/sink swing, a bust spike.
- **`dc-gameplay` / `cards-gameplay`** — turn-processing latency or a rejection-rate jump.
- **`dc-funnel`** — an onboarding step whose drop-off suddenly worsened.

A signal earns a todo when it's a **plausible regression or defect**, not merely noteworthy — "crash-free % fell below 99% after last night's build" is a bug; "DAU dipped" is not. And a **known-benign signal is never a todo**: a banned-403 (`CARDS-BG`), a user-cancelled purchase, or a single install's `net.backend_unreachable` is the app working as designed (wiki → "Known-benign client signals"). A banned-403 in particular is owned by ENG-35 — don't re-file it.

**Green dashboards are not evidence of health.** ENG-45 ran eight days at 300-500s per request,
returning `200 OK` the whole time, and every panel stayed green. Before you write "nothing found",
run the two checks that would have caught it:

```
# 1. Slow-but-successful. No upper bound, unlike the Prometheus histogram (which tops out at a
#    10s bucket and cannot represent anything worse).
{service_name="cards-server", deployment_environment="prod"} |~ ` in [0-9]{5,}ms` != `101 Switching Protocols`

# 2. Abnormal exits, INCLUDING oom — it outnumbers anr 8-to-1 and used to be excluded entirely.
sum by (previous_exit) (count_over_time({service_name="cards-client", deployment_environment="prod"} | event_name="app.launched" [7d]))
```

The first is now dc-infra → "Slowest requests (server logs)"; empty is the healthy state and
anything in it is a real finding. **Never conclude a route is fast from the RED latency panel
alone** — read `docs/wiki/observability.md` → "What the suite cannot see" for why those quantiles
are unreliable above about a second.

**Also scan the server logs directly** — a failing path can be live before any dashboard panel or alert reflects it. Over the last 24h:
`query_loki_logs(datasourceUid='grafanacloud-logs', logql='{service_name="cards-server", deployment_environment="prod"} | detected_level=~"warn|error|fatal"', ...)`. **Include `warn`** — money-touching failures like `receipt_rejected` / redeem-400s are logged at WARN, so an `error|fatal`-only filter misses them entirely. Recurring warnings/errors not already owned by a Sentry issue or todo are their own signal. And **`totalLinesScanned: 0` on a filtered query does not mean the server is silent** — the filter just matched nothing; confirm the base stream is live (`query_loki_stats`, or an unfiltered `{service_name="cards-server"}` count) before concluding there are no server logs.

**Loki cannot answer past 31 days.** A wider query fails with
`max_query_lookback (31d)`, so never conclude "this has never happened" from an event query — it
only means "not in the last month". Postgres (`profiles`, `wallet_events`, `xp_events`,
`player_stat_events`, `table_sessions`, `billing_transactions`) is the durable record; reach for it
whenever the question is historical. Same rule applies when you *build* a panel: only Postgres-backed
panels may claim "all time" (`docs/wiki/observability.md` → "Durable vs ephemeral panels").

**Know the expected-empty panels so you don't file phantom bugs** (all documented in the wiki's "Known gaps"): matchmaking/MP/Tempo/RED panels read empty (or health-check-only) until real play resumes post-wipe; iOS crash-free shows `previous_exit=unknown` until MetricKit (ENG-25); offline-emitted events drop by design. **Query gotchas:** Loki stream labels are only `service_name` + `deployment_environment` (everything else is structured metadata — pipe filters); gameplay spans are INTERNAL-kind so they're not in `traces_spanmetrics_*` Prom metrics (use TraceQL metrics on Tempo); count users/sessions from `app.foregrounded`, never `app.launched`; prod is the default env.

### 5. Determine root cause and decide

Synthesize the evidence into a concrete root cause. For any **Sentry issue** you turn into a todo — and any **Grafana signal** substantial enough to warrant investigation notes — write a **case file** at `docs/agent/feedback-cases/<id>.md` (`<id>` = Sentry short-id, or your Grafana signal slug). `mkdir -p docs/agent/feedback-cases` is cheap; skip the existence check. Use `feedback-triage`'s case template, trimmed to what applies (a Grafana signal has no user comment — lead with the dashboard/alert, the query, and the window). The point is one bundle a worker can pick up cold.

Then pick a disposition:

**(a) Actionable → file a todo.** Append to `docs/todo.md` in the house format (the `curate-todos` skill — one bold title + ≤3 lines, a priority tag, no status archaeology). Put the case-file path in `Hints:`:

```
- `[P0]` **Short imperative title of the fix.** One sentence: what's wrong (from the signal + telemetry).
  **Acceptance:** one sentence: how we know it's fixed.
  **Hints:** the file/route/span/dashboard that points at the cause; case `docs/agent/feedback-cases/<id>.md`; Sentry issue URL or dashboard/alert link.
```

Priority: money / crash-loop / data-corruption / prod-down → `[P0]`; a real defect degrading a real flow → `[P1]`; cosmetic / rare / low-blast-radius → `[P2]`. A firing money alert (A1/A5) is always `[P0]`. Large or needs-a-product-call work → one-liner in `docs/backlog.md` instead, referencing the case file and the signal.

**Assigning the item ID:** `docs/todo.md` is often reset to empty by the nightly todo-maintainer, and IDs are **never reused** — don't infer the next number from the open list. Pick the right existing prefix from the **canonical list in `docs/todo.md`'s preamble** (the "**ID prefixes:**" line — e.g. `ENG` engineering/infra, `BILL` billing, `ECON` chip economy, `ROOM` rooms UI; onboarding falls under `AUTH`). Then find that prefix's current max **numerically** and take the next integer:

```
PFX=ENG; grep -rhoE "\b${PFX}-[0-9]+" docs/ | grep -oE '[0-9]+' | sort -n | tail -1
```

Query the specific prefix you're filing under — a blanket all-prefix grep sorts lexically (`BILL-10` < `BILL-9`) and sweeps in `CARDS-*` Sentry short-ids and `AES-`/`SHA-` crypto constants, which aren't work items. If a maintainer reset left `docs/todo.md` with no section headers, add the section for your prefix before appending.

**(b) No action → resolve.** Dev-only noise (only reachable from a debug build or dev backend), already fixed on `develop` but not yet resolved in Sentry, a known expected-empty panel, or genuinely benign. Still write the case file for a Sentry issue so a rerun has the reasoning; record the *why* in its "Working theory" section.

### 6. Close the loop

For **every** signal handled, append to `docs/agent/observability-log.md`:

```
- <date> · <short-id | signal-slug> · <"todo: <title>" | "no-action: <reason>" | "backlog"> · <Sentry URL | dashboard/alert link> · case docs/agent/feedback-cases/<id>.md
```

Then close the loop in Sentry (Grafana is read-only — see guardrails). **Do not resolve an issue you filed a todo for** — it isn't fixed yet; leave it unresolved and only comment that it's triaged, and the worker that ships the fix resolves it then (the `work-item` skill). Only **no-action** dispositions resolve here. Record the Sentry issue id in the todo's Hints so the worker can find it. Resolve the token from env, falling back to the macOS Keychain so unattended runs work without a plaintext token on disk:

```
TOKEN="${SENTRY_AUTH_TOKEN:-$(security find-generic-password -s cards-sentry-auth-token -w 2>/dev/null)}"
```

- **`$TOKEN` non-empty** (never echo the token):
  - **Todo filed → comment only, leave unresolved:** `curl -sS -X POST ".../issues/<issueId>/comments/" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"text":"Triaged → <TODO-ID>, fix pending"}'`
  - **No-action → resolve:** `curl -sS -X PUT "https://us.sentry.io/api/0/organizations/elijah-dangerfield/issues/<issueId>/" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"status":"resolved"}'`
- **`$TOKEN` empty** → the ledger is the source of truth; note in the summary that Sentry status wasn't flipped (store the token in keychain item `cards-sentry-auth-token` to enable it).

### 7. Run summary

Short summary: N Sentry issues + M Grafana signals reviewed, K todos filed (with titles + priorities), L resolved no-action, anything that needs a human (ambiguous, product decision, a threshold that looks miscalibrated).

## Guardrails

- **Idempotent:** the ledger check in step 1 is mandatory — never double-file a signal.
- **Never double-handle feedback:** feedback carrier/twin issues belong to `feedback-triage`. Skip them here.
- **Dedup across the intake:** `feedback-triage` runs just before you — check `docs/agent/feedback-log.md` + `docs/todo.md` so one root cause becomes one todo, not two.
- **Read-only on Grafana:** never create/modify/delete alert rules, dashboards, or annotations. If a threshold looks wrong, file a todo/backlog note for the human — don't touch it.
- **Read-mostly on Sentry:** the only Sentry write is issue status (with a token). No code changes — fixing the bug is a worker's job off the todo you file.
- **Don't invent telemetry:** empty Tempo/Loki for a `session_id` → say so, don't guess a backend cause.
- **Expected-empty ≠ incident:** check the wiki's "Known gaps" before filing a phantom bug off a deliberately-empty panel.
- **One signal → at most one todo.** Several issues sharing a root cause → one todo, the rest resolved as duplicate pointing at it.
