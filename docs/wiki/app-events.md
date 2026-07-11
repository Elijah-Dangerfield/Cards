# Client app events

The registry of structured events the client emits for product analytics. One event = one
`logEvent(name, attrs)` call riding the normal KLog tree system: it lands in logcat, as a Sentry
breadcrumb, and — via `GrafanaLogTree` in `:libraries:telemetry:impl` — as an OTLP log record in
Grafana Cloud Loki. The dashboards these feed are mapped in
[`docs/wiki/observability.md`](observability.md).

Dashboard queries treat this page as the source of truth for names and attributes. Names are
dot-namespaced snake_case; every record automatically carries `session_id` + `install_id` +
`is_offline` (per-record) plus resource attributes (`service.name="cards-client"`, deployment
environment, version, platform). `is_offline` is `AppState.isOffline` captured **at emit time** —
records that ship later from the disk buffer still say what connectivity looked like when the
event happened, so reliability funnels can segment "emitted offline" without span archaeology.

**Query shape (verified against live Loki 2026-07-11):** stream is
`{service_name="cards-client", deployment_environment="dev"|"prod"}`; `event_name`, `session_id`,
and all event attributes are **structured metadata**, so filter with pipes
(`| event_name="hand.completed"`), not line filters. The server's request logs carry the same
`session_id` metadata key — that's the cross-service correlation join.

**Delivery is durable, effectively at-least-once** (ENG-25). The export chain is batch → disk
buffer → OTLP: every batch is written to a file-backed buffer (`<files>/telemetry/…`, via
`durableLogRecordProcessor`) before export and deleted only after the gateway acknowledges it, so
events emitted offline survive process death and ship on a later launch or flush tick. Retention is
the library's defaults — 100 buffered batches, 30-day max age — after which oldest batches are
dropped. A record can rarely ship twice (export acknowledged but the process dies before the
buffer delete), so dashboards counting events should tolerate the odd duplicate rather than assume
exactly-once. Two edges remain lossy by design: records ride in RAM for up to one flush tick (5s)
before reaching disk, and `TelemetryBackgroundFlusher` closes most of that window by force-flushing
the pipe (RAM → disk → export attempt) on every app background — the last reliable moment before
the OS suspends or kills the process.

### Pipeline review (2026-07-11, ENG-25)

Deliberate calls from the considered pass over the setup, so nobody re-litigates them blind:

- **Batch tuning stays at library defaults** (2048-record queue, 5s flush, 512-record export
  batches, 30s export timeout). Our volume is a handful of events per user-minute; the defaults are
  sized far above it and the 5s RAM window is bounded by the background flush.
- **Exports are NOT gated on `AppState.isOffline`.** Tempting (skip doomed POSTs while offline),
  but `isOffline` also trips on *backend* unreachability — and surviving backend outages is the
  whole reason this pipe goes direct to Grafana. A failed export while offline just stays in the
  buffer; the DNS failure is already contained by `FailSafeLogRecordExporter`.
- **`telemetry.appEventsEnabled` + `appEventsSampleRate` stay separate.** Considered consolidating
  (rate 0.0 == off); rejected — the flag is an instant kill switch for library bugs / ingest
  incidents and reads as one in the QA menu, the rate is a gradual volume dial. Collapsing them
  makes the emergency lever a magic number.
- **iOS `previous_exit` is still `unknown`** — the MetricKit `MXAppExitMetric` subscriber remains
  open under ENG-25 in `docs/todo.md`.

**Rules for adding events:** emit through the `logEvent` extension only (never a raw
`EXTRA_APP_EVENT` extra), fire on user actions / state transitions — never per-frame, per-poll, or
per-flow-emission — and add the event here in the same change. Client events answer
intent/funnel/abandonment questions; the backend DB stays source-of-truth for money, achievements,
and anything already in a ledger.

## Engagement & session shape

| Event | Attributes | Fires |
|---|---|---|
| `app.launched` | `cold_start` (always true), `previous_exit` (clean/crash/anr/oom/unknown) | Once per cold start, on the boot foreground (`GrafanaAppEvents.onForeground`) — after the session tracker rolls session #1, so it shares the boot's `session_id` with every other event (ENG-24; it used to fire at DI init and land orphaned on a pre-rollover id). Doubles as the pipeline smoke test. `previous_exit` comes from Android's historical exit reasons (API 30+; older devices report `unknown`); **iOS always reports `unknown`** until MetricKit wiring lands (ENG-25) — segment by platform before reading exit rates |
| `app.foregrounded` | `cold_start` | Every foreground (`LifecycleAppEventLogger`); `cold_start=true` on the boot foreground |
| `app.backgrounded` | `session_duration_sec` | Every background; `session_duration_sec` = whole seconds since the matching foreground (monotonic clock), so session length is a direct query — no span join needed. Omitted in the (shouldn't-happen) case of a background with no prior foreground |
| `game.started` | `mode` (bots/multiplayer), `difficulty` | `PlayPokerViewModel` init |
| `game.ended` | `mode`, `hands_played`, `duration_sec`, `end_reason` (left/bust/match_over/opponent_left/room_closed) | Once per session (latched) at whichever end path fires first |
| `hand.completed` | `mode`, `hand_number`, `won`, `showdown` | Each hand the human actually played |

## Matchmaking funnel

The flagship client-only funnel — the backend can't see a user who browses candidates and leaves.
All in `PublicSearchingViewModel` unless noted; `wait_ms` counts from the search episode's start.

| Event | Attributes | Fires |
|---|---|---|
| `matchmaking.search_started` | `entry` (public/private_code) | Find-a-table start/retry; join-by-code submit (`PrivateJoinViewModel`) |
| `matchmaking.candidates_shown` | `candidate_count` (0 = straight to waiting) | Initial candidates browse resolves |
| `matchmaking.candidate_joined` | `wait_ms` | User picked a chooser table and the join seated them |
| `matchmaking.wait_started` | — | Fell through to a fresh table to genuinely wait |
| `matchmaking.real_player_arrived` | `during` (wait/bot_offer), `wait_ms` | First other connected human, once per episode |
| `matchmaking.bot_offer_shown` | `wait_ms` | The 60s window elapsed alone |
| `matchmaking.bot_offer_accepted` | `wait_ms` | "Play bots" tapped |
| `matchmaking.bot_offer_declined` | `next` (keep_waiting/leave) | Either decline affordance |
| `matchmaking.abandoned` | `phase` (choosing/searching/joined/bot_offer/joining_bots), `wait_ms` | User backed out (cancel / try-again-later) |
| `room.joined` / `room.left` | — | `RoomRepositoryImpl` join/leave success |
| `room.join_failed` | `reason` | `RoomRepositoryImpl` join failure |

## Onboarding funnel

All in `OnboardingViewModel`.

| Event | Attributes | Fires |
|---|---|---|
| `onboarding.step_viewed` | `step` (welcome/pick_identity/how_it_works/starter_grant) | Each step entered (including back-navigation) |
| `onboarding.auth_selected` | `method` (guest/google/apple), `returning` | Guest continue; OAuth/Apple sign-in success |
| `onboarding.completed` | `duration_sec`, `account_ready` (false = degraded will-retry) | "Take a seat" |
| `onboarding.abandoned` | `step` | Best-effort on VM clear without reaching Home; a process kill won't emit it — count step_viewed-without-completed sessions for the full picture |

## Monetization funnel

Backend ledger owns the money truth; these cover the funnel around it.

| Event | Attributes | Fires |
|---|---|---|
| `shop.viewed` | — | Each storefront entry (`ShopFeatureEntryPoint`); the preceding event in the session tells you the entry path |
| `economy.out_of_chips_shown` | `balance`, `context` (home) | Home's out-of-chips sheet opens (once per broke episode) |
| `purchase.initiated` | `product_id` | Top of `PurchaseChipPackUseCase` — shop grid and in-game quick-buy |
| `purchase.completed` / `.failed` / `.cancelled` | `product_id`, `error?` | IAP round-trip outcome |
| `shop.item_redeemed` | `product_id`, `chip_cost` | Chip-funded cosmetic redeem + XP-boost purchase |
| `game.rebuy` | `mode`, `via_quick_buy` | Rebuy accepted by the table |

## Reliability from the client's chair

The events that motivated shipping direct-to-Grafana: what never reaches the backend.

| Event | Attributes | Fires |
|---|---|---|
| `net.backend_unreachable` | `operation`, `error_kind` (timeout / exception class) | Shared `NetworkCall` failure path, non-HTTP failures only — an HTTP status IS reachability |
| `conn.reconnecting` | `attempt` | Each room-socket reconnect attempt (backoff-bounded, `ReconnectingRoomSocket`) |
| `conn.recovered` | `attempts`, `downtime_ms` | First decoded frame after an outage — a half-open handshake doesn't count |
| `conn.reconnect_failed` | `attempts` | Reconnect ceiling reached |
| `room.closed_unexpectedly` | `reason` (rejected/reconnect_failed/room_deleted/incompatible_version) | Terminal socket close, excluding the normal match-over path |
| `net.offline_banner` | `visible`, `os_online`, `backend_reachable` | Each edge of the app-wide offline banner (`AppStateImpl`), carrying which signal drove it — added after ROOM-16, where a reported banner had no explaining event in the trail |
| `game.intent_timeout` / `game.intent_rejected` | `intent_type` | Submit failure branches in `PlayPokerViewModel` |

## Feature usage

| Event | Attributes | Fires |
|---|---|---|
| `settings.changed` | `key` (game_speed/turn_feedback/show_achievement_popups), `value` | Settings screen writes |
| `cosmetic.equipped` | `product_id`, `slot`, `auto` | My Items equip toggle (`auto=false`) + post-purchase auto-equip (`auto=true`) |
| `achievement.celebration_shown` | `achievement_id`, `rarity`, `silenced` | Per unlock at hand end; `silenced=true` when the user muted popups (unlock still banked) |
| `feedback.submitted` | `is_bug`, `has_screenshots` | `FeedbackRepositoryImpl` success — feedback and bug-report flows |
| `emote.sent` | `mode` | Emoji blast passes the cooldown gate |
| `emote.player_muted` | — | Muting a player (unmute is silent) |

## Achievements & progression

Backend-driven — no client events beyond `achievement.celebration_shown` (a UX question). The
progression questions are answerable from Postgres via the `cards-prod-db` datasource.

## Warn+ log forwarding (not events)

Besides events, `GrafanaLogTree` forwards plain KLog lines at Warn and above to Loki as ordinary
OTLP logs — client errors visible without waiting on a Sentry crash. Query them with

```
{service_name="cards-client"} | detected_level=~"warn|error"
```

These records have **no `event_name`** (that's how you tell them apart from events); they carry
`session_id`/`install_id`, the logger `tag`, and `exception_type`/`exception_message` when a
throwable was attached. Gated by `telemetry.klogForwardingEnabled` (remote config, default **on**)
and still behind the `telemetry.appEventsEnabled` kill switch + per-session sampling — flipping
the forwarding flag off never affects events.
