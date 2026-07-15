# Observability-triage ledger

Processed-signal ledger for the intake-phase observability triage (see
[`observability-triage.md`](./observability-triage.md)). One entry per signal already
dispositioned so reruns skip it. Sentry issues are keyed by short-id; Grafana signals by a
stable slug. Machine-side complement to [`feedback-log.md`](./feedback-log.md).

**Disposition legend:** `→ todo <ID>` · `→ backlog` · `no-action: <reason>`. Re-open (don't
duplicate) if a resolved signal gets materially worse.

---

## Sentry issues

| Issue | Title (short) | Disposition |
|---|---|---|
| [CARDS-9R](https://elijah-dangerfield.sentry.io/issues/CARDS-9R) | `JsonConvertException: unknown key 'walletBalance'` (iOS, debug) | no-action: already fixed on develop |
| [CARDS-9Q](https://elijah-dangerfield.sentry.io/issues/CARDS-9Q) | `SingleWriterGuard` refusing to boot (dev) | no-action: guard working as designed, dev-only, self-resolved |
| [CARDS-97](https://elijah-dangerfield.sentry.io/issues/CARDS-97) | `SavedStateHandle` can't put `AccountActionsState` (twin CARDS-93) | no-action: already fixed on develop (ENG-27) |
| [CARDS-9V](https://elijah-dangerfield.sentry.io/issues/CARDS-9V) | `ReceiptRejectedException: apple_account_mismatch` (server, prod) | no-action: dup of BILL-11 (server twin) + BILL-12 |
| [CARDS-9H](https://elijah-dangerfield.sentry.io/issues/CARDS-9H) | Redeem unreachable — order left uncredited | no-action: dup of BILL-12 (client misclassifies the 400) + BILL-11 root; supersedes prior BILL-7 pointer |
| [CARDS-96](https://elijah-dangerfield.sentry.io/issues/CARDS-96) | Store did not recognize 3/3 chip-pack SKUs | no-action: dev store-listing noise |
| [CARDS-94](https://elijah-dangerfield.sentry.io/issues/CARDS-94) | `TLS sessions are not supported on Native platform` (iOS fatal) | → todo ENG-28 |
| [CARDS-9C](https://elijah-dangerfield.sentry.io/issues/CARDS-9C) | `AuthUnready: FinishingSetup` captured to Sentry as error | → todo ENG-29 |
| [CARDS-9M](https://elijah-dangerfield.sentry.io/issues/CARDS-9M) | `AuthUnready: NeedAccount` captured to Sentry as error | → todo ENG-29 (collapsed with CARDS-9C) |
| [CARDS-93](https://elijah-dangerfield.sentry.io/issues/CARDS-93) | `SavedStateHandle` can't put `OnboardingState` | no-action: same root cause as CARDS-97, fixed on develop (ENG-27) |
| [CARDS-95](https://elijah-dangerfield.sentry.io/issues/CARDS-95) | `CrashedByAdbException: shell-induced crash` (dev emulator) | no-action: adb/emulator-induced kill, not a code defect |

## Grafana signals

| Slug | What | Disposition |
|---|---|---|
| `alerts:A1-A7-2026-07-12` | Firing-alert sweep (rules A1–A7) | no-action: none firing |
| `health:writers-2026-07-12` | Single-writer health, both envs | no-action: one healthy writer per env; no server error/fatal logs in 24h |
| `alerts:A1-A7-2026-07-13` | Firing-alert sweep (rules A1–A7) + Loki server error sweep | no-action: none firing, no OnCall groups, zero cards-server error/fatal logs in 24h |
| `alerts:A1-A7-2026-07-13-nightly` | Firing-alert sweep (rules A1–A7) + Loki server error re-sweep (nightly) | no-action: none firing/pending, no OnCall groups, zero cards-server error/fatal logs in 24h |
| `alerts:A1-A7-2026-07-15-nightly` | Firing-alert sweep (A1–A7) + OnCall + Loki server error/warn sweep + Pulse skim | no-action: none firing/pending, no OnCall groups; billing rejections present but only at WARN (covered by BILL-11/12 via CARDS-9V) |

---

## Run log

<!-- 2026-07-12 intake-phase observability triage (first run — ledger created).
Reviewed 6 signals: 5 unresolved Sentry issues (24h window) + a Grafana alert/health sweep.
Filed 0 todos; all no-action. Details:

- CARDS-9R (io.ktor JsonConvertException: unknown key 'walletBalance', 2 events, 1 user,
  iOS **dev-ios-debug** / simulator, route PlayStyleUnlockedRoute, handled=yes, commit
  0c03aa5c on develop). Root cause: contract drift caught by design. `NetworkJson`
  (libraries/networking/.../NetworkJson.kt:23) sets `ignoreUnknownKeys = !BuildInfo.isDebug`,
  so debug builds parse strictly to surface drift loudly. The server started sending
  `walletBalance` on the progression-sync response (a chip-mint signal, ENG-9/PROG-12); the
  crashing client at 0c03aa5c predated the matching DTO field. That field was added to
  `ProgressionSyncResponseDto.walletBalance` by c3d38a33 (fix(progression) PROG-12), which IS
  on develop and post-dates 0c03aa5c. So develop already models the payload; release builds
  ignore unknown keys anyway. No-action (already fixed on develop). Re-open only if it recurs
  on a build that contains c3d38a33.

- CARDS-9Q (IllegalStateException 'Refusing to boot: another cards-server instance already
  holds the single-writer lock', 11 events, level fatal, substatus escalating, **environment
  dev**). Loki (grafanacloud-logs) shows the full story: 15:58:31Z one instance logged
  "Single-writer lock acquired — this instance is the writer", while a second concurrent
  instance ran the retry budget (attempt 1..15) and threw, restarted, and repeated for ~13m
  (15:58–16:13Z) — i.e. two machines ran at once on the dev app (stray `fly scale count 2` /
  deploy overlap). The guard is the intentional "loud and safe" mechanism (SingleWriterGuard.kt
  + fly.toml already pins strategy=rolling, no blue-green): the loser refuses to boot rather
  than split-brain; the healthy writer never lost the lock, no data corruption. Self-resolved —
  after 16:13Z the only events are clean single acquisitions on dev (16:18Z) and prod (16:26Z);
  both envs now show exactly one writer, no "Refusing to boot" since, no ERROR/FATAL server
  logs in 24h. No prod occurrence. No-action (working as designed, dev-only, recovered).
  For a human: worth a glance only if it recurs — check the dev app isn't left at scale count 2.

- CARDS-97 (IllegalArgumentException: Can't put value with type AccountActionsState into saved
  state, 14 events, escalating, dev-android-debug, route ProfileRoute; twin CARDS-93 for
  FeedbackState — same root cause). Crash build 5ce8e0b3 (branch main). Root cause:
  `SEAViewModel.onCleared` wrote `savedStateHandle[STATE_KEY] = state`, and non-Bundle-able
  state types (AccountActionsState / FeedbackState) throw on persist. That write was deleted on
  develop by 678177f3 (fix(flowroutines): delete SEAViewModel's dead saved-state write, ENG-27);
  5ce8e0b3 predates it. No-action (already fixed on develop, pending a build that includes 678177f3).

- CARDS-9H (Redeem unreachable for order 2000001203481803 — left uncredited, 1 event, iOS).
  Same order id appears in feedback case b198b3ec's log; it's the iOS TestFlight redeem 400
  (appAppleId missing) already filed as **BILL-7**. No-action (dup → BILL-7).

- CARDS-96 (Store did not recognize 3/3 chip-pack SKUs [chips_small/medium/large], 1 event).
  Dev store listing without approved IAP SKUs; pre-existing (cf. CARDS-8V), previously flagged
  2026-07-10 by feedback-triage. No-action (dev/pre-launch store-listing noise).

Grafana: no OnCall alert groups in 'new' state (A1–A7 clear). Both cards-server writers healthy
(one acquisition per env post-16:13Z). No ERROR/FATAL cards-server logs 2026-07-11 18:00Z→now.
Nothing filed. -->

<!-- 2026-07-13 intake-phase observability triage (phase 1b, stacked on feedback-triage 32960aeb).
Widened the Sentry sweep to 7d (the first run only used a 24h window and missed 5 issues that
first-seen ~24h ago). Reviewed 10 unresolved Sentry issues + a Grafana alert/log re-sweep.
Filed 2 todos (ENG-28 P1, ENG-29 P2); no P0. Details:

- CARDS-94 (kotlin.IllegalStateException 'TLS sessions are not supported on Native platform',
  6 events, 1 user, **fatal / unhandled**, iOS dev-ios-debug simulator, commit aeb0ff5a on
  develop, route HomeRoute). A Ktor CIO/native engine is being used for a TLS (HTTPS/WSS) call
  on Kotlin/Native — that engine has no native TLS, so the request aborts the process on a
  background worker. Not simulator- or build-type-specific. Static inspection: every client
  module wires darwin(iOS)/okhttp(android) correctly and ktor-client-cio is declared only in
  apps/server (JVM), so the CIO engine should not be on the iOS classpath — yet the runtime
  error is CIO's. Leading hypothesis (confidence medium; native stack is stripped): an
  engine-less `HttpClient { }` resolves to a TLS-incapable engine on Native, prime suspect the
  telemetry OTLP exporter's grafanaHttpClient() (GrafanaAppEvents.kt:157, background HTTPS POST);
  also NetworkClientImpl.kt:56/62. → **todo ENG-28 [P1]**, case CARDS-94.md. Fix direction:
  pass the Darwin engine explicitly on iOS + audit the iOS dep graph for a stray ktor-client-cio.

- CARDS-9C + CARDS-9M (AuthUnready: FinishingSetup / NeedAccount, 7 + 6 events, handled=yes,
  level error, **beta-ios-release** real TestFlight build cards@1.0+740, commit 5ce8e0b3,
  route HomeRoute). AuthUnready is a deliberate typed control-flow short-circuit (auth not
  ready — the network layer returns it without hitting the wire; AuthGate.kt:90). A HomeRoute
  startup sync fires an authed call before auth is ready, gets the expected AuthUnready, and
  logs it at error — and SentryLogTree.captureEvent (SentryLogTree.kt:101) forwards any
  error-level throwable to Sentry.captureException with no filter, so expected control flow
  becomes false error events. No user impact; log spam that masks real errors. Collapsed both
  into one → **todo ENG-29 [P2]**, case CARDS-9C.md. Fix: filter AuthUnready out of the Sentry
  event path centrally, or route the call site through onAuthFailure.

- CARDS-93 (SavedStateHandle can't put OnboardingState, 2 events, dev-android-debug, commit
  5ce8e0b3). Same root cause as CARDS-97 — SEAViewModel.onCleared's dead saved-state write,
  deleted on develop by 678177f3 (ENG-27, confirmed ancestor of HEAD; SEAViewModel.onCleared no
  longer writes saved state). Crash build predates the fix. No-action (collapse → CARDS-97).

- CARDS-95 (RemoteServiceException$CrashedByAdbException 'shell-induced crash', 1 event,
  dev-android-debug emulator, in_foreground=false). The process was killed via adb/emulator
  shell — a developer/CI artifact, not an app defect. No-action.

Already-ledgered, re-checked, not materially worse (skipped per idempotency): CARDS-9Q (still
11 events, same dev self-resolved single-writer window; no "Refusing to boot" in Loki last 24h),
CARDS-9R (still 2 events), CARDS-96 (4 events, dev store noise), CARDS-97 (14 events, fixed on
develop). CARDS-9H ticked 1→3 events / 1→2 users but same order id 2000001203481803 and already
tracked by BILL-7 — no re-open (not order-of-magnitude worse, defect already has an open todo).

Grafana: alerting_manage_rules(states=firing,pending) → none; list_alert_groups(state=new) → [];
Loki {service_name="cards-server"} | detected_level=~"error|fatal" over 24h → 0 lines
(1729 scanned). A1–A7 clear, no server errors. Nothing filed from Grafana. -->

<!-- 2026-07-13 (nightly, stacked on eb53b9c0). Feedback-triage before us committed nothing
(no new feedback). Reviewed 10 unresolved Sentry issues + Grafana alert/log re-sweep.
Filed 0 todos; all already-dispositioned or no-action. No P0.

Sentry: the exact same 10 unresolved issues as the 2026-07-13 phase-1b run, with identical
event counts — no new issue and none materially worse, so nothing re-opened:
- CARDS-9Q (single-writer refuse-to-boot): still 11 events, last seen 12h ago, dev env; no new
  occurrences since the self-resolved 15:58–16:13Z window. Prompt flagged it as "live and
  unowned" — it is already ledgered no-action (guard working as designed, dev-only, recovered).
  Not a duplicate; no todo.
- CARDS-9R (JsonConvertException unknown key 'walletBalance'): still 2 events, iOS dev-debug.
  Prompt flagged it too — already ledgered no-action (develop models the payload via c3d38a33;
  release builds ignore unknown keys). Event count unchanged, so no re-open.
- CARDS-94 → ENG-28 and CARDS-9C/CARDS-9M → ENG-29 were both FIXED on develop since the last
  run (commits 8179bdba, 1d70488d). Todos already filed and worked; issues will clear from
  Sentry once a build containing the fixes ships. No new action.
- CARDS-97/93 (fixed on develop, ENG-27), CARDS-96 (dev store noise), CARDS-9H (dup BILL-7),
  CARDS-95 (adb kill): all unchanged, all previously dispositioned no-action. Skipped per idempotency.

Grafana: alerting_manage_rules(states=firing,pending) → null; list_alert_groups(state=new) → [];
Loki {service_name="cards-server"} | detected_level=~"error|fatal" over 24h → 0 lines
(2433 scanned). A1–A7 clear, no server errors. Nothing filed from Grafana. -->

<!-- 2026-07-15 nightly (DRY RUN — no Sentry writes performed; issues left unresolved). Stacked on
feedback-triage, which filed BILL-11 (CARDS-9Y), BILL-12 (CARDS-A0), ROOM-18 (CARDS-9W) tonight.
Reviewed 14 unresolved Sentry issues (3 feedback → feedback-triage's, skipped; 10 already ledgered
& not materially worse; 1 new: CARDS-9V) + Grafana alert/OnCall/Loki sweep + Pulse skim.
Filed 0 todos — the one new signal deduped into feedback-triage's billing todos. No P0 unowned.

- CARDS-9V (ReceiptRejectedException: apple_account_mismatch, chip_pack_small; server, prod,
  escalating, 15 events / 1 user; BillingRoutes.kt:125). NEW and unowned by the ledger, but it is
  the **server-side twin of BILL-11**: same install cb87c0e4, same post-upgrade caller 6f0a900c,
  same stale appAccountToken 52f3f9c1 (pre-AUTH-19 guest id), same "…803" small pack the BILL-11
  user reported. Loki trace 9371f0a8… confirms AppStoreReceiptValidator.validate (l.118) →
  BillingRoutes (l.120) → 400 /v1/billing/redeem, all logged at WARN. The 15 events are the
  client's cross-launch retry loop = BILL-12. No second todo → **no-action, dup of BILL-11 + BILL-12**,
  case CARDS-9V.md. (Would resolve in Sentry on a real run; dry-run leaves it unresolved.)

- CARDS-9H (Redeem unreachable — order 2000001203481803 left uncredited; iOS beta-release
  cards@1.0+840, ShopRoute). Already ledgered as "dup of BILL-7". Re-checked: 3→7 events, last seen
  17h ago — not order-of-magnitude worse, not re-opened. But it's the SAME install cb87c0e4 / caller
  6f0a900c / order …803 as CARDS-9V/BILL-11, so its true owner is now **BILL-12** (client turns the
  400 receipt_rejected into "unreachable/uncredited" + retries) with BILL-11 as the root — pointer
  corrected in the table above (supersedes the stale BILL-7 tie).

- Other 9 ledgered issues (CARDS-97/9Q/9C/9M/94/96/9R/93/95): event counts unchanged from the
  2026-07-13 runs, none materially worse, all previously dispositioned. Skipped per idempotency.

Grafana: alerting_manage_rules(states=firing,pending) → null; list_alert_groups(state=new) → [];
Pulse (dc-pulse) row-1 alerts all covered by A1–A7 (none firing). Loki server error/fatal sweep → 0
lines, BUT base {service_name="cards-server",deployment_environment="prod"} has 65 lines/24h — the
billing rejections are logged at WARN, invisible to an error|fatal-only sweep. Nothing filed from
Grafana. NOTE for human: A5 (≥2 purchase.failed/1h) did NOT fire despite 15 stranded redeems —
because BILL-12 misclassifies these 400s as "pending/unavailable", the client emits no
purchase.failed, so the money-loss alert is blind to this exact failure mode. -->

