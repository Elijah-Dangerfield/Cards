# Standing tables health

Reusable templates for the [Standing Tables Health dashboard](https://cards.grafana.net/d/dc-standing-tables): 25 panels (with an `env` switcher) and five warning rules per environment. Rendering writes local JSON only. Generated rules are paused; live activation, evidence and rollback history belong in the rollout ledger, separate from these templates.

## Render without contacting Grafana

From the repository root, run this command as written. It prompts for every binding and writes JSON under `/tmp/downcard-standing-tables`; it refuses to overwrite existing output.

```sh
python3 config/observability/standing-tables/render.py
```

Find the Loki and Prometheus datasource UIDs in [Grafana data sources](https://cards.grafana.net/connections/datasources), opening each datasource's settings. For the server selector, use [Explore](https://cards.grafana.net/explore) to inspect the labels on one of the metrics below, and supply matchers that isolate the chosen environment, without braces. Do this verification only when live access is authorized. `Telemetry.kt` exports `service.name` and `deployment.environment` as OTLP resource attributes; live `target_info` and `http_server_request_duration_seconds_count` reads confirmed `job="cards-server"` and `deployment_environment="dev"|"prod"`. Render with both matchers for the selected environment. Do not use an unscoped selector across dev and prod.

The generated `dashboard.json` is Grafana dashboard import JSON. `alerts.json` is Grafana Alerting file-provisioning JSON (`apiVersion: 1`), with all rules paused, org ID 1 and the existing Engineering folder (`downcard-engineering`). Alert UIDs, titles and groups include the selected environment, so dev and prod imports remain distinct. Verify the destination org and folder before provisioning. `queries.json` provides every panel's source and expression for review. No script in this bundle uploads files or changes alert state.

`dashboard.json` always uses UID `dc-standing-tables` and carries the `env` variable (prod default, dev) that the live dashboard has. Its queries use `$env`, so one import serves both environments; the environment and server selector you render with only affect `alerts.json`. Importing replaces the live dashboard, so export the live one first.

## What the queries count

Client LogQL uses stream labels `service_name="cards-client"` and `deployment_environment`, then filters structured metadata with pipes. [`GrafanaLogTree.forward()`](../../../libraries/telemetry/impl/src/commonMain/kotlin/com/cards/libraries/telemetry/impl/GrafanaLogTree.kt) exports extras as attributes, not JSON in the log body. Queries therefore use `| event_name="..." | operation="..."`, without `| json`. See the [event registry](../../../docs/wiki/app-events.md) and [Loki conventions](../../../docs/wiki/observability.md).

`standing_table.operation_completed` is the repository denominator. It records mine/get/create/update/archive/leave separately, including decode failures and mine refreshes after successful writes. Creation entry intent is a separate count, not a request or a cohort. Client events can be delayed, sampled or duplicated by the existing telemetry pipeline.

Client `room.joined` with `is_standing_table=true` confirms a returned standing-room summary. Failure attribution is partial: unknown codes omit that attribute. Server join success percentage uses all attributable standing join requests, including already-joined successes. It measures request conversion, not whether an invite recipient became a player. There is no identifier linking web page requests to app joins.

Four weekly panels use existing `install_id` metadata: unique creator devices, successful standing-join devices, creator devices that also shared, and devices taking a standing-table action. All use rolling seven-day windows and return aggregate counts or percentages, never identifiers. The creator/share percentage intersects the two device sets and requires a positive creator denominator. A device can create one table and share another; this is not per-table conversion. Devices are not people or accounts, joins do not prove hands played, and session-based telemetry sampling can miss activity. These panels do not establish D7 return cohorts or web-to-app attribution.

Calendar outcomes stay split by platform and result: iOS `added` confirms a save, Android `opened` only confirms that its editor launched. `cancelled` and `unavailable` are not failed saves; unavailable opens the `.ics` fallback.

Server metrics use monotonic OTel counters from [`ServerMetrics`](../../../apps/server/src/main/kotlin/com/cards/server/plugins/Metrics.kt), with bounded [outcome enums](../../../apps/server/src/main/kotlin/com/cards/server/plugins/StandingTableMetrics.kt):

| OTel instrument | Prometheus name | Labels |
| --- | --- | --- |
| `cards.standing_tables.share.requests` | `cards_standing_tables_share_requests_total` | `surface`: page/calendar/preview; `outcome`: standing/ordinary/unknown/archived/invalid/error |
| `cards.standing_tables.join.requests` | `cards_standing_tables_join_requests_total` | `outcome`: joined/already_joined/archived/insufficient_balance/full/not_joinable/not_found/error |
| `cards.standing_tables.materializations` | `cards_standing_tables_materializations_total` | `outcome`: created/reused/error |

All three names and their bounded labels were confirmed in live dev ingestion. A controlled repeat verified counter deltas for joins, materializations and share outcomes; production unknown-code probes verified page, calendar and preview counters. Resource-label promotion was also confirmed on server HTTP metrics. Share counters distinguish unknown and archived pages even when the handler returns HTTP 200. Preview crawlers, bots and repeated requests count; these are not distinct users. Rate-limit rejections before handlers, static assets, ordinary coroutine cancellation and lookup errors before standing-code attribution remain outside these counters. [`Tracing.kt`](../../../apps/server/src/main/kotlin/com/cards/server/plugins/Tracing.kt) and [`Observability.kt`](../../../apps/server/src/main/kotlin/com/cards/server/plugins/Observability.kt) install generic HTTP tracing and request logs. Inspect those logs and traces for failures that happen before standing-code attribution; the feature counters do not cover them. Timeout cancellation is counted as an error by the shared `Catching` policy.

## Rules and readiness

Traffic is a handful of operations a week, so ratio rules with a minimum-request gate could never fire. Every rule is a count over a rolling 6 hours: it fires when the number of failures reaches the threshold and persists five minutes. Client rules count `outcome="failure"` with reason `network`, `server`, `decode` or `unknown` (the client reports unexpected exceptions as `unknown`). Expected invalid, cap/conflict, offline, auth, rate-limited, other HTTP and not-found outcomes stay visible on charts but never alert. Server rules count `outcome="error"` only, excluding full tables, insufficient balance, archived tables and unknown links.

| Rule | Fires at (rolling 6h) | Dashboard panel |
| --- | --- | --- |
| Client operations | 3 or more outage failures | 24, Client outage failures, 6h |
| Creation | 2 or more outage failures on `create` | 24 |
| Share handlers | 3 or more `error` outcomes | 25, Standing server errors, 6h |
| Standing joins | 2 or more `error` outcomes | 25 |
| Materialization | 2 or more `error` outcomes | 25 |

Panels 24 and 25 use the same filters and window as the rules, so what a rule counted is what its linked panel shows. Server counts use `round(increase(...))` because `increase` extrapolates and the first increment of a new counter series is not counted; one error right after a deploy can therefore be missed.

**Tuning as traffic grows.** Thresholds are the number after `>= bool` in each rule's expression in `alerts.template.json`, and the window is `[6h]` plus the query's `relativeTimeRange.from` (21600 seconds); keep the two in step. At roughly 12 operations a week, 3 failures in 6h is already a strong signal. Once volume is in the hundreds a week, raise the thresholds, shorten the window (for example 1h, `from: 3600`) and consider returning to a ratio rule with a minimum-request gate, since a fixed count would then fire on ordinary background failure. Update `render.py` validation and `test_render.py` if you change the rule shape.

Absent series count as zero failures (`or vector(0)`), and rules use `noDataState=OK` because quiet or dark-feature traffic is legitimate; `execErrState=Error` preserves query execution failures. This avoids false DatasourceNoData notifications without changing notification policies. Dashboard percentage panels still return no data when traffic is absent, so read traffic beside them, and verify ingestion separately. The existing A7 heartbeat only checks for globally absent server HTTP metrics; it does not prove per-environment standing-table counters or client Loki delivery.

Before activation, verify the metric names and environment selector in Explore, compare controlled dev actions with their emitted outcomes, and confirm the rendered Grafana queries and alert previews. Import rules paused, check notification routing, then explicitly enable only after ingestion is verified. No-data, alert delivery, live query execution and feature rollout remain unverified by local rendering.

Local review checks: the renderer validates JSON structure, required bindings, unique panel IDs, paused rules and quiet-traffic handling. Eleven server panel/rule expressions parsed with cached Prometheus v0.311.3; standard metric-name translation was checked offline. Live Loki and Prometheus query syntax checks subsequently passed. Grafana import succeeded for the new dashboard. Rule creation and ingestion state are tracked separately in the rollout ledger; template validation is not alert activation.
