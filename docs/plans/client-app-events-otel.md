# Client App Events: Taxonomy, Dashboards, and KMP OTel Implementation

Approved plan for ENG-18 (see [todo.md](../todo.md)). Planned 2026-07-10.

## Context

Backend telemetry (Tempo/Loki/Postgres → Grafana) already powers dashboards like the chip-economy one, but whole classes of product questions are invisible to the backend: funnels that abandon before a request is made, matchmaking back-outs, onboarding drop-off, and — critically — "the client couldn't reach the backend at all." The plan: emit structured **app events** from the client via the [opentelemetry-kotlin](https://github.com/open-telemetry/opentelemetry-kotlin) KMP library (v0.5.0), shipped **directly to Grafana Cloud's OTLP gateway** (logs → Loki), deliberately not through our own backend, so events still flow when it's down. The library gives us batching/retry for free; Grafana Cloud gives us dashboards/alerting on a separately-hosted stack.

One event = one OTel log record with a first-class `eventName` (the library's `Logger.emit(eventName = ...)` — no manual `event.name` attribute needed). Every event carries `session_id` + `install_id` so it joins the existing Sentry/Tempo/Loki correlation story.

## Decisions made (documented per convention)

- **Events ride the existing KLog tree system — no new injectable.** Call sites use the logger they already have via a `logEvent` extension; a `GrafanaLogTree` filters for the event attribute and forwards only those. Same entry reaches logcat + Sentry breadcrumbs through the existing trees for free. "Ship all client logs to Grafana" later = widen this tree's filter behind a flag.
- **Direct-to-Grafana with a hard-coded write-only token** (Sentry-DSN precedent). This *supersedes* ENG-18's earlier "server relay, never ship credentials in the binary" hint: the whole point is that these events survive backend outages, and a logs:write-only token can't read or modify anything.
- **Skip `exporters-persistence` in V1** — events lost on app-kill-while-offline are accepted loss for behavioral analytics; the processor-factory seam makes adding it later a one-line swap.
- **Per-session sampling** (stable session_id hash), not per-event — a session's events are all-or-nothing so funnels stay joinable.
- **Achievements/progression dashboards are backend-only** (Postgres via `cards-prod-db`); no client events needed there.

---

## Part A — Event taxonomy: PM questions → events → dashboards

**Conventions:** dot-namespaced snake_case names (`matchmaking.search_started`). Auto-attached to every event: `session_id`, `install_id` (per-record, since sessions roll over mid-process), plus resource attrs `service.name="cards-client"`, `deployment.environment` (dev/prod), version, platform, release channel. Rule of thumb: **client events answer intent/funnel/abandonment questions; the backend DB stays source-of-truth for money, achievements, and anything already in the ledger.** Where both exist, backend wins for counts, client wins for "why didn't it happen."

### 1. Engagement & session shape — "How much do people actually play, and against whom?"
| Event | Key attributes | Notes |
|---|---|---|
| `app.launched` | `cold_start` | Also serves as the pipeline smoke test |
| `app.foregrounded` / `app.backgrounded` | — | Session length = span between these per session_id |
| `game.started` | `mode` (bots/multiplayer), `difficulty`, `entry_point` | The "games per user, how many vs bots" question |
| `game.ended` | `mode`, `hands_played`, `duration_sec`, `end_reason` (left/bust/match_over/opponent_left) | |
| `hand.completed` | `mode`, `hand_number`, `won`, `showdown` | Hands-per-session distribution |

### 2. Matchmaking funnel — "How many people try to find a game and back out?"
This is the flagship client-only funnel; the backend can't see a user who browses candidates and leaves. Maps to `PublicSearchingViewModel` phases (Choosing → Searching → BotFallbackOffer → Joined).

| Event | Key attributes |
|---|---|
| `matchmaking.search_started` | `entry` (public/private_code/deep_link) |
| `matchmaking.candidates_shown` | `candidate_count` (0 = straight to waiting) |
| `matchmaking.candidate_joined` | `wait_ms` |
| `matchmaking.wait_started` | — |
| `matchmaking.bot_offer_shown` | `wait_ms` (fired at the 60s timeout) |
| `matchmaking.bot_offer_accepted` / `.bot_offer_declined` | — |
| `matchmaking.real_player_arrived` | `during` (wait/bot_offer) |
| `matchmaking.abandoned` | `phase`, `wait_ms` — **the back-out event** |
| `room.join_failed` | `reason` (not_found/full/over_balance/network/...), `entry` |
| `room.joined` / `room.left` | `mode`, `reason` |

### 3. Onboarding funnel — "Where do new users drop off?"
| Event | Key attributes |
|---|---|
| `onboarding.step_viewed` | `step` (welcome/pick_identity/how_it_works/starter_grant) |
| `onboarding.auth_selected` | `method` (guest/google/apple), `returning` |
| `onboarding.completed` | `duration_sec`, `account_ready` (or degraded will-retry) |
| `onboarding.abandoned` | `step` (best-effort on background/kill) |

### 4. Monetization funnel — "Do people who hit zero convert?"
Backend ledger owns the money truth; client owns the funnel *before* the purchase.
| Event | Key attributes |
|---|---|
| `shop.viewed` | `entry_point` (tab/out_of_chips_sheet/bust_dialog) |
| `economy.out_of_chips_shown` | `balance`, `context` |
| `purchase.initiated` | `product_id`, `entry_point` |
| `purchase.completed` / `.failed` / `.cancelled` | `product_id`, `error?` |
| `shop.item_redeemed` | `product_id`, `chip_cost` |
| `game.rebuy` | `mode`, `via_quick_buy` |

### 5. Reliability from the client's chair — "What never makes it to the backend?"
The class of events that motivated direct-to-Grafana in the first place.
| Event | Key attributes |
|---|---|
| `net.backend_unreachable` | `operation`, `error_kind` (timeout/dns/refused) |
| `conn.reconnecting` | `attempt` |
| `conn.reconnect_failed` / `conn.recovered` | `attempts`, `downtime_ms` |
| `room.closed_unexpectedly` | `reason` (RoomDeleted/Rejected/IncompatibleVersion/ReconnectFailed) |
| `game.intent_timeout` / `game.intent_rejected` | `intent_type` |

### 6. Feature usage & feedback — "Is anyone using this?"
`settings.changed` (`key`, `value`), `cosmetic.equipped` (`slot`, `product_id`), `achievement.celebration_shown` (`achievement_id`, `rarity`, `silenced`), `feedback.submitted` (`is_bug`, `has_screenshots`), `emote.sent` / `emote.player_muted`.

### 7. Achievements & progression — **backend-driven, no client events needed**
The questions (avg achievements per user, which are never hit, % completed all, XP/level distribution) are all answerable from the existing Postgres tables via the `cards-prod-db` datasource — same construction as the chip-economy dashboard. Client only contributes `achievement.celebration_shown` (a UX question, not a progression one).

### Dashboards (all in the existing Grafana stack)
1. **Client engagement** (Loki) — DAU-ish actives by install_id, sessions/day, session length, games by mode (bots vs MP), hands per session. LogQL over `{service_name="cards-client"}` with `sum by` on event name.
2. **Matchmaking funnel** (Loki) — search→joined conversion, back-out rate by phase, wait-time histogram, bot-offer acceptance, join-failure reasons. *Answers "people who try to find a game and can't."*
3. **Onboarding funnel** (Loki) — step conversion, guest vs OAuth split, time-to-complete.
4. **Monetization funnel** (Loki + Postgres) — out-of-chips → shop → purchase conversion from client events, cross-checked against ledger `iap.*` rows.
5. **Achievements & progression** (Postgres only) — per-achievement unlock %, avg per user, never-hit list, completed-all count, level distribution.
6. **Client health** (Loki, + alert) — backend-unreachable rate, reconnect failures, intent timeouts, room-closed reasons. Alert: `net.backend_unreachable` spike = the "backend is down and only Grafana can tell us" alarm.

---

## Part B — Implementation plan (opentelemetry-kotlin)

**Design: events ride the existing KLog tree system — no new injectable.** Call sites use the logger they already have via an extension function; a new `GrafanaLogTree` (planted alongside KermitLogTree/SentryLogTree) filters for entries carrying the event attribute and forwards *only those* to Grafana Cloud as OTLP logs. The same log entry flows to logcat/OSLog and Sentry breadcrumbs through the existing trees for free. Later, shipping *all* logs to Grafana (so we don't wait on a Sentry crash to see things) is just widening this tree's filter behind a flag — same pipe.

Library facts (verified against library source during planning): group `io.opentelemetry.kotlin` v0.5.0; artifacts `api`, `implementation`, `exporters-otlp`, `exporters-in-memory`; experimental — requires `@OptIn(io.opentelemetry.kotlin.ExperimentalApi::class)`; Kotlin 2.0+, Android minSdk 21, iOS 16+. `Logger.emit()` takes `eventName` as a first-class field. `otlpHttpLogRecordExporter` has **no headers param** — Grafana basic auth requires passing our own Ktor `HttpClient` (must install `HttpTimeout`, `ContentNegotiation`, `ContentEncoding { gzip() }` to match the library's internal default). Exporter posts to `$baseUrl/v1/logs`.

### Prerequisite (owner, human-only): Grafana Cloud write token
Mint a Cloud Access Policy token scoped to **logs:write only** (do NOT reuse the server's token) in the Grafana Cloud console, and grab the OTLP gateway base URL (`https://otlp-gateway-<region>.grafana.net/otlp`) + instance ID. These get hard-coded like `CARDS_SENTRY_DSN` (AppTelemetry.kt precedent — write-only, acceptable in the binary). Build proceeds with placeholder constants; paste the three values when ready.

### PR 1 — `logEvent` extension, GrafanaLogTree, SDK wiring, first events
1. **Version catalog** ([libs.versions.toml](../../gradle/libs.versions.toml)): `opentelemetryKotlin = "0.5.0"` + `otel-kotlin-api/-implementation/-exporters-otlp/-exporters-inmemory` entries (prefix avoids collision with the server's `opentelemetry-*` JVM entries).
2. **Event convention in `libraries/core`** (dependency-free, next to `KLog.kt` in `libraries/core/src/commonMain/kotlin/com/cards/libraries/core/logging/`) — `AppEvents.kt`:
   ```kotlin
   /** Extra key marking a log entry as an app event; GrafanaLogTree forwards these. */
   const val EXTRA_APP_EVENT = "app_event"

   /** The single blessed way to emit an app event. Names: dot-namespaced snake_case
    *  ("matchmaking.abandoned"). Rides the normal tree system: logcat + Sentry
    *  breadcrumb + Grafana. */
   fun Logger.logEvent(name: String, vararg attributes: Pair<String, Any?>) =
       // Info-level entry; EXTRA_APP_EVENT=name plus each attribute as a scope extra
   ```
   Usable as `KLog.logEvent(...)` or on any tagged logger. The extension is the contract — call sites never touch the raw key.
3. **New single module `libraries/telemetry/impl`** (no api sibling — the public surface is the core extension), registered in settings.gradle.kts inside the `!serverOnly` block; only `apps/compose` depends on it (satisfies the ModuleBoundaries ":impl only from :apps" rule). Rationale: the 0.5.0 experimental dep gets its own blast radius instead of joining the `cards/impl` kitchen sink. Build file mirrors `libraries/config/impl/build.gradle.kts` + per-platform Ktor engines like `cards/impl`; `optIn("io.opentelemetry.kotlin.ExperimentalApi")`.
4. **`GrafanaLogTree`** (subclasses `libraries/core/.../logging/LogTree.kt`; `@SingleIn(AppScope)`, contributed as `AutoInit` multibinding which constructs it and calls `KLog.plant(this)` at boot — same lifecycle SentryLogTree gets, but owned here since `cards/impl` can't depend on `telemetry/impl`):
   - **Filter**: `isLoggable(entry)` → entry has `EXTRA_APP_EVENT` (any level). A flagged second mode later widens to `|| level >= Warn` for the ship-all-logs future.
   - Lazy `createOpenTelemetry { loggerProvider { serviceName = "cards-client"; resource(version, deployment.environment = if (BuildInfo.isDebug) "dev" else "prod" — matches the server's derivation, platform, build number, commit); export { batchLogRecordProcessor(otlpHttpLogRecordExporter(baseUrl, grafanaHttpClient(basicAuth))) } } }`.
   - Forwarding: `emit(eventName = extras[EXTRA_APP_EVENT], severity from entry.level)`, stamps `session_id` (from `SessionIdProvider.current()` — **per-record, never a resource attr**, because of the 5-min background rollover) + `install_id`, remaining extras/tags as attributes, all wrapped in `Catching {}` (repo convention) — telemetry never crashes the app, and a tree failure never affects the other trees.
   - `processorFactory` constructor seam so tests inject `simpleLogRecordProcessor(inMemoryExporter)`.
   - AutoInit path emits `app.launched` via the extension — warms the SDK and proves the whole pipe (extension → KLog → tree → exporter).
5. **Remote-config controls** (ConfiguredValue pattern, auto-appear in QA menu): `telemetry.appEventsEnabled` (default true — the kill switch for library bugs or ingest-cost incidents) and `telemetry.appEventsSampleRate` (default 1.0; sampled **per-session** via stable session_id hash so sessions stay all-or-nothing). Both evaluated per-forward so a config flip takes effect without restart; when off/sampled-out the entry still reaches logcat + Sentry via the other trees.
6. **Offline posture**: batch processor gives bounded in-memory queue + retry/backoff; events lost on app-kill-while-offline are accepted for behavioral analytics. Skip `exporters-persistence` for now; the `processorFactory` seam makes it a later one-line swap.
7. **Tests** (commonTest, in-memory exporter + `KLog.plant`/`clearTrees` seam): `logEvent` entry forwarded with eventName + session/install attrs; plain Info log NOT forwarded (filter works); kill-switch off → no export; rate 0.0 → no export; session rollover → new session_id on next event; attribute stringification/null-dropping.
8. **First events**: `app.launched`, `room.joined`/`room.left`, `hand.completed`, `purchase.completed`/`.failed` — one-line `logEvent` calls in the existing VMs/repos, no constructor changes.

Key files: `libraries/cards/impl/.../AppTelemetry.kt` (config/env/init precedent), `libraries/cards/impl/.../logging/SentryLogTree.kt` (tree pattern to mirror), `libraries/cards/impl/.../SessionTrackerImpl.kt`, `apps/compose/.../AppComponent.kt`.

Accepted trade-offs of the tree-based design: (a) the event contract is a convention (well-known extra key), not a compiler-enforced type — mitigated by the extension being the only blessed entry point; (b) attributes ride as stringly LogEntry extras, so numeric aggregations in LogQL need `unwrap`-style casts — acceptable.

### PR 2 — Instrument the Part A taxonomy
Sweep the feature modules: `PublicSearchingViewModel` (matchmaking funnel), `PrivateJoinViewModel`, `OnboardingViewModel`, `ShopViewModel` + `PurchaseChipPackUseCase`, `PlayPokerViewModel` (game/hand/rebuy/connection events), `FeedbackViewModel`, settings paths. Event-name registry documented in `docs/wiki/app-events.md` (per the docs/wiki convention) so dashboard queries have one source of truth. Rule: events fire on user actions / state transitions, never per-frame or per-poll.

### PR 3 — Widen the tree filter to Warn+ logs (optional, flagged off by default)
Flip `GrafanaLogTree` into its second mode behind `telemetry.klogForwardingEnabled` (default false): forward Warn+ entries (no event attribute required) as ordinary OTel logs — ops mirror of client errors into Loki without waiting for a Sentry crash. Same tree, same pipe; log volume becomes a deliberate second decision.

### PR 4 — Dashboards + alert
Build the six Part A dashboards (same stack as `cards-gameplay`): Loki panels use label-matcher pipes on structured metadata (e.g. `{service_name="cards-client", deployment_environment="prod"} | event_name != ""` — metadata pipes, not line filters, per the feedback-triage skill's hard-won note). Achievements dashboard is pure Postgres against `cards-prod-db`. One alert rule on `net.backend_unreachable` rate.

### Verification
1. `./gradlew :libraries:telemetry:impl:allTests` green.
2. Debug run on Android emulator: `app.launched` + a gameplay event → query dev Loki: `{service_name="cards-client", deployment_environment="dev"}`.
3. **Correlation acceptance test**: take the session UUID from logcat, query it against both `cards-client` and `cards-server` Loki streams — one session_id, both sides of the wire. Confirm in Explore the exact structured-metadata key Grafana derives for `eventName` and adjust dashboard queries to match.
4. QA-menu kill-switch drill: flip `telemetry.appEventsEnabled` off → export stops, logcat lines continue.
5. Offline drill: airplane mode → restore → batched events arrive.

### Risks / open items
- **Library is 0.5.0 experimental**: pinned exactly; all OTel types confined to `GrafanaLogTree` inside `telemetry/impl` — re-backing the tree with raw Ktor OTLP-JSON is a swap, not a rewrite; call sites only know the `logEvent` extension.
- **Ktor skew**: repo forces 3.5.0 over whatever 0.5.0 built against — smoke-test both platforms in PR 1.
- **iOS background flush**: events emitted in the last ~batch-interval before suspend may be lost; acceptable now, and we hold the processor reference as the future flush-on-background hook.
- **Unverified until implementation**: exact `AttributesMutator` setter names, and the Loki metadata key for `eventName` — both confirmed in the first debug run.
