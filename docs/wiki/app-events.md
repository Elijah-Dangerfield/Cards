# Client app events

The registry of structured events the client emits for product analytics. One event = one
`logEvent(name, attrs)` call riding the normal KLog tree system: it lands in logcat, as a Sentry
breadcrumb, and — via `GrafanaLogTree` in `:libraries:telemetry:impl` — as an OTLP log record in
Grafana Cloud Loki. The dashboards these feed are mapped in
[`docs/wiki/observability.md`](observability.md).

Dashboard queries treat this page as the source of truth for names and attributes. Names are
dot-namespaced snake_case; every record automatically carries `session_id` + `install_id`
(per-record) plus resource attributes (`service.name="cards-client"`, deployment environment,
version, platform).

**Query shape (verified against live Loki 2026-07-11):** stream is
`{service_name="cards-client", deployment_environment="dev"|"prod"}`; `event_name`, `session_id`,
and all event attributes are **structured metadata**, so filter with pipes
(`| event_name="hand.completed"`), not line filters. The server's request logs carry the same
`session_id` metadata key — that's the cross-service correlation join.

**Delivery is at-most-once.** A batch that fails to export is dropped, not retried — events emitted
while the device is offline are lost (accepted for behavioral analytics; a persistence exporter is
the upgrade path if this ever matters). `app.launched` also predates the first-foreground session
rollover, so it carries its own one-off `session_id` (ENG-24).

**Rules for adding events:** emit through the `logEvent` extension only (never a raw
`EXTRA_APP_EVENT` extra), fire on user actions / state transitions — never per-frame, per-poll, or
per-flow-emission — and add the event here in the same change. Client events answer
intent/funnel/abandonment questions; the backend DB stays source-of-truth for money, achievements,
and anything already in a ledger.

## Engagement & session shape

| Event | Attributes | Fires |
|---|---|---|
| `app.launched` | `cold_start` (always true), `previous_exit` (clean/crash/anr/oom/unknown) | Boot, from `GrafanaAppEvents` init — doubles as the pipeline smoke test. `previous_exit` comes from Android's historical exit reasons (API 30+; older devices report `unknown`); **iOS always reports `unknown`** until MetricKit wiring lands (ENG-25) — segment by platform before reading exit rates |
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
