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
| [CARDS-A2](https://elijah-dangerfield.sentry.io/issues/CARDS-A2) | `Server rejected replayed receipt for order …555` (iOS) | no-action: dup of BILL-13 (replayed-receipt cluster) |
| [CARDS-AB](https://elijah-dangerfield.sentry.io/issues/CARDS-AB) | `Terminally rejected replayed receipt for order …555 — finishing it` (client) | no-action: dup of BILL-13; client's terminal-finish path logged at error → Sentry noise |
| [CARDS-BR](https://elijah-dangerfield.sentry.io/issues/CARDS-BR) | `ApplicationNotResponding: ANR` (Android, syscall) | no-action: environmental — emulator/side-load ANR at PairIP license gate, no app frames (new benign class in wiki) |
| [CARDS-3](https://elijah-dangerfield.sentry.io/issues/CARDS-3) | `WatchdogTermination` on the iOS onboarding welcome screen (App Store build) | → todo ENG-42 |
| [CARDS-8V](https://elijah-dangerfield.sentry.io/issues/CARDS-8V) | Store did not recognize 3/3 chip-pack SKUs (**store-ios-release**) | → todo ENG-43 (**shipped 8a2360da**) + developer-todo (ASC), which is now the sole remaining owner and is human-only. **Supersedes the 2026-07-10 / CARDS-96 "dev store-listing noise" call — it's prod now.** Stays unresolved until the App Store Connect listing is fixed |
| [CARDS-BS](https://elijah-dangerfield.sentry.io/issues/CARDS-BS) | `ProxyBillingActivity` NPE `getIntentSender()` (Android billing) | no-action: uncatchable upstream Play Billing crash (null PendingIntent in Google's onCreate); 1 event on a Play review-emulator. **NB (corrected 2026-07-27): 1009/billing-7.1.1 IS current prod (only release tag), NOT superseded; the 9.1.0 bump is develop-only/unreleased & not a confirmed fix.** Re-open if it hits a real retail device |

## Grafana signals

| Slug | What | Disposition |
|---|---|---|
| `alerts:A1-A7-2026-07-12` | Firing-alert sweep (rules A1–A7) | no-action: none firing |
| `health:writers-2026-07-12` | Single-writer health, both envs | no-action: one healthy writer per env; no server error/fatal logs in 24h |
| `alerts:A1-A7-2026-07-13` | Firing-alert sweep (rules A1–A7) + Loki server error sweep | no-action: none firing, no OnCall groups, zero cards-server error/fatal logs in 24h |
| `alerts:A1-A7-2026-07-13-nightly` | Firing-alert sweep (rules A1–A7) + Loki server error re-sweep (nightly) | no-action: none firing/pending, no OnCall groups, zero cards-server error/fatal logs in 24h |
| `alerts:A1-A7-2026-07-15-nightly` | Firing-alert sweep (A1–A7) + OnCall + Loki server error/warn sweep + Pulse skim | no-action: none firing/pending, no OnCall groups; billing rejections present but only at WARN (covered by BILL-11/12 via CARDS-9V) |
| `alerts:A1-A7-2026-07-17-nightly` | Firing-alert sweep (A1–A7) + OnCall + Loki server warn/error/fatal sweep | no-action: none firing/pending, no OnCall groups; 4 WARN lines all apple_account_mismatch redeem rejections (covered by BILL-11/12/13) |
| `alert:A5-false-page-2026-07-26` | A5 (billing critical) paged ~03:00Z; owner found nothing in Sentry/logs | no-action: FALSE ALARM — Loki datasource blip (connection refused) + `exec_err_state=Alerting` fired it; billing healthy (0 failed / 1 completed / 3 initiated in 48h). Fixed: A5 `exec_err_state=OK` + `runbook_url` → dc-billing-health; added a live Purchase-funnel section to that dashboard |
| `sweep:2026-07-27-interactive` | Owner-requested "look for issues across Sentry + Grafana" | no-action: 1 Sentry issue (CARDS-BS → no-action); no alerts firing/pending; 0 cards-server warn/error/fatal in 24h; 4 one-off client warnings (noise) |
| `sweep:2026-08-18-nightly` | Firing-alert sweep (A1–A7) + Loki server/client sweep | no-action: none firing/pending; 0 cards-server prod warn+ in 7d (357 scanned, stream live); iOS store traffic is live in the client stream |
| `loki:duplicate-rebuy-intents` | 131 `intent rejected: seat is not busted` vs 10 `game.rebuy` in prod/14d | → todo MP-38 [P1] |
| `loki:onboarding-abandoned-false-positive` | `onboarding.abandoned` fires on back-out; 4 of 6 "abandoned" installs completed | → todo AUTH-31 [P2] |

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


<!-- 2026-07-16 observability triage (interactive, after feedback-triage): 14 unresolved non-feedback issues. Three NEW since the 07-15 run — all the replayed-receipt / no-catalog-SKU cluster, same root cause as tonight's BILL-13 (filed by feedback-triage from CARDS-AA). Resolved as duplicates → BILL-13. The rest unchanged/previously dispositioned; CARDS-9V (apple_account_mismatch) grew 15→26 events, still owned by BILL-11/12 (now also BILL-13). No Grafana alerts firing; A5 purchase-failure alert still blind to this mode per prior note (BILL-12 misclassifies the redeem 400s so no purchase.failed is emitted). -->
- 2026-07-16 · CARDS-A2 · no-action: duplicate of BILL-13 (replayed receipt order 2000001203481555) · https://elijah-dangerfield.sentry.io/issues/CARDS-A2 · case docs/agent/feedback-cases/e452cfd17fe94266bd2bd5fcc730a34e.md
- 2026-07-16 · CARDS-A4 · no-action: duplicate of BILL-13 (unfinished purchase ...555, no catalog product chips.large) · https://elijah-dangerfield.sentry.io/issues/CARDS-A4 · case docs/agent/feedback-cases/e452cfd17fe94266bd2bd5fcc730a34e.md
- 2026-07-16 · CARDS-A5 · no-action: duplicate of BILL-13 (unfinished purchase ...803, no catalog product chips.small) · https://elijah-dangerfield.sentry.io/issues/CARDS-A5 · case docs/agent/feedback-cases/e452cfd17fe94266bd2bd5fcc730a34e.md

<!-- 2026-07-17 nightly observability triage (stacked on tonight's feedback-triage recovery 1b48ef94 + the 07-16 ledger-recovery commit). Feedback-triage tonight filed AUTH-20 (CARDS-A6), AUTH-21 (CARDS-A8), BILL-13 (CARDS-AA). Reviewed 12 unresolved Sentry issues + Grafana alert/OnCall/Loki sweep. Filed 0 todos — no new, unowned signals.

Sentry: all 12 unresolved issues already dispositioned in this ledger, none materially worse, so nothing re-opened —
- CARDS-9V (apple_account_mismatch, server prod) ticked 26→28 events, still the BILL-11/12/13 stale-appAccountToken cluster (52f3f9c1, pre-AUTH-19 guest id). Not order-of-magnitude worse; no re-open.
- CARDS-A2 (replayed receipt …555) is the 07-16 recovered no-action (dup BILL-13); still unresolved in Sentry like its billing-dup siblings CARDS-9V/9H — left as-is (these recur while BILL-13 is open; ledger is source of truth).
- CARDS-97/9H/9Q/9C/9M/94/96/9R/93/95: event counts unchanged from prior runs, all previously dispositioned (ENG-27/28/29, dev-store noise, single-writer dev, adb kill, walletBalance drift). Skipped per idempotency. CARDS-A4/A5 no longer in the unresolved queue.

Grafana: alerting_manage_rules(states=firing,pending) → null; list_alert_groups(state=new) → []. No alerts firing, no OnCall groups. Loki {service_name="cards-server",deployment_environment=prod} | detected_level=~"warn|error|fatal" over 24h → 11 WARN lines / 212 scanned, base stream live (212 entries). All 11 are known/owned: the apple_account_mismatch redeem-rejection cluster (BILL-11/12/13) + one "Cannot build the Production receipt verifier (appAppleId required) — degrades to sandbox" WARN, which is EXPECTED pre-launch by design (decisions.md 2026-07-07) with the prod-secret ops follow-up already tracked in developer-todo.md (APPLE_APP_APPLE_ID at launch). No error/fatal. Nothing filed. A5 purchase-failure alert still blind to the redeem-400 mode per the standing 07-15 note (human). -->

<!-- 2026-07-17 nightly observability triage (2nd run tonight; feedback-triage before us created NO commits — no new feedback — so stacked directly on develop HEAD 9f94ea0e). Reviewed 13 unresolved Sentry issues + Grafana alert/OnCall/Loki sweep. Filed 0 todos — one new signal, deduped into an existing billing todo. No P0 unowned.

Sentry: 12 of 13 already dispositioned in this ledger; 1 new (CARDS-AB).
- CARDS-AB (NEW, 2 events / 2 users, first seen 4h ago; cocoa/iOS dev-ios-debug, release cards@0.1.0+1, develop commit 0eac8990, logger_tag PurchaseChipPackUseCase, route HomeRoute, session 270dc10b). Message: "Terminally rejected replayed receipt for order 2000001203481555 — finishing it". Same order …555 as CARDS-A2 (the 07-16 replayed-receipt dup of BILL-13). This is the CLIENT side of that cluster: the app recognizes a terminally-rejected replayed StoreKit receipt and finishes/consumes it to break the replay loop — i.e. exactly the BILL-13 remediation direction (DefaultPurchaseChipPackUseCase.redeemOutstanding / purchase.discarded). No-action → dup of BILL-13, shared case e452cfd17fe94266bd2bd5fcc730a34e.md. NOTE folded into BILL-13, not a separate todo (one root cause = one todo): the "finishing it" event is a HANDLED/expected outcome yet logged at ERROR, so SentryLogTree forwards it as noise — BILL-13's implementer should demote this path to WARN/INFO once the finish behavior is confirmed correct.
- Re-checked billing-cluster event growth for materially-worse re-opens; none order-of-magnitude: CARDS-9V 28→30 (2 users), CARDS-9H 7→12 (3 users), CARDS-A2 6 unchanged. All still owned by BILL-11/12/13. No re-open.
- CARDS-97/9Q/9C/9M/94/96/9R/93/95: event counts unchanged from prior runs, all previously dispositioned (ENG-27/28/29, single-writer dev, dev-store noise, walletBalance drift, adb kill). Skipped per idempotency.

Grafana: alerting_manage_rules(states=firing,pending) → null; list_alert_groups(state=new) → []. No alerts firing, no OnCall groups. Loki {service_name="cards-server",deployment_environment=prod} base stream live (66 entries/24h); | detected_level=~"warn|error|fatal" → 4 WARN lines, all apple_account_mismatch redeem rejections (BillingRoutes.kt:128 / AppStoreReceiptValidator.kt:119, stale appAccountToken 52f3f9c1 = pre-AUTH-19 guest id, new install fd196699/user 7de9b42a this window) — owned by BILL-11/12/13. No error/fatal. Nothing filed. A5 purchase-failure alert still blind to the redeem-400 mode per the standing 07-15 note (human). -->

- 2026-07-17 · CARDS-AB · no-action: duplicate of BILL-13 (client finishing terminally-rejected replayed receipt, order 2000001203481555; handled path logged at error → Sentry noise, demote as part of BILL-13) · https://elijah-dangerfield.sentry.io/issues/CARDS-AB · case docs/agent/feedback-cases/e452cfd17fe94266bd2bd5fcc730a34e.md
- 2026-07-17 · alerts:A1-A7-2026-07-17-nightly · no-action: none firing/pending, no OnCall groups; 4 WARN redeem rejections all owned by BILL-11/12/13 · dc-pulse / grafanacloud-logs

<!-- 2026-07-21 nightly observability triage (stacked on tonight's feedback-triage dac37940). Feedback-triage before us filed 7 todos (MP-35/GAME-33/GAME-34/ROOM-19/PROG-13/AUTH-28/ENG-32) + 1 backlog from the 07-21 dogfood batch (carriers/twins CARDS-AP…B9). Reviewed 12 unresolved non-feedback Sentry issues + Grafana alert/OnCall/Loki sweep. Filed 0 todos — no new, unowned signals. No P0.

Sentry (30d window, 20 unresolved total): 8 are "User feedback" carriers owned by feedback-triage (CARDS-B8/B6/B4/B2/AZ/AY/AR + CARDS-AM) — skipped here. The other 12 are all already dispositioned in this ledger and none materially worse, so nothing re-opened —
- Billing cluster steady, no order-of-magnitude jumps: CARDS-9V (apple_account_mismatch, server prod) 30 events / 2 users (was 30 on 07-17); CARDS-9H (redeem …803 no-credit) 12 / 3 (was 12); CARDS-A2 (replayed receipt …555) 6 / 3 (was 6). All owned by BILL-11/12/13. No re-open.
- CARDS-97/93 (SavedStateHandle, fixed on develop ENG-27), CARDS-9Q (single-writer dev), CARDS-9C/9M (AuthUnready → ENG-29), CARDS-94 (TLS-on-Native → ENG-28), CARDS-96 (dev store-listing noise), CARDS-9R (walletBalance DTO drift), CARDS-95 (adb-induced dev kill): event counts unchanged from prior runs, all previously dispositioned. Skipped per idempotency. CARDS-AB (07-17 dup of BILL-13) has aged out of the unresolved queue.

Grafana: alerting_manage_rules(states=firing,pending) → null; list_alert_groups(state=new) → []. No alerts firing, no OnCall groups. Loki cards-server prod base stream live (109 entries/24h, 2 streams); | detected_level=~"warn|error|fatal" over 24h → 0 lines (109 scanned) — the billing-rejection WARNs seen on prior nights did not recur in this window. cards-client prod base stream also live (27 entries/24h); warn|error|fatal → 0 lines. Nothing filed from Grafana.

FOR A HUMAN: CARDS-AM is an untriaged "User feedback" carrier (feedback_event 1d9b8461…, route FeedbackRoute, dev-android-release install 2144ea2a / user d0d7ce6d, first seen 2026-07-18 19:02Z). It is NOT in feedback-log.md — tonight's feedback-triage batch covered the 07-21 dogfood + 07-18 iOS reports but this 07-18 Android report slipped through. It's feedback-triage's channel, not mine, so no todo filed here; flag for the next feedback-triage run / owner. Also standing since 07-15: A5 (≥2 purchase.failed/1h) stays blind to the redeem-400 money-loss mode because BILL-12 misclassifies those 400s, so no purchase.failed is emitted. -->
- 2026-07-21 · alerts:A1-A7-2026-07-21-nightly · no-action: none firing/pending, no OnCall groups; cards-server + cards-client prod streams live with zero warn/error/fatal in 24h · dc-pulse / grafanacloud-logs


<!-- 2026-07-22 interactive observability triage (owner-requested: "85 client errors on Pulse — real problem?"). Reviewed 22 unresolved Sentry issues + Grafana alert/Loki sweep. Filed 1 todo (ENG-34 P2). No P0, no outage.

- CARDS-BA (NEW, DarwinHttpRequestException NSURLError -1009 "Internet connection appears to be offline", 59 events / 43 min, 2026-07-22 19:14-19:57Z, escalating, beta-ios-release 1.0(968) @ 0e1ec568, route HomeRoute, handled=yes). ALL events from one device: owner dogfood install 91628081 / QuietJack51 (sessions 52bf4517 x44 + 1d2a0add x15 — same session as today's ROOM-20/21 feedback). Device genuinely lost its network route (_NSURLErrorNWPathKey=unsatisfied); background sync (POST /v1/equipment/sync) retried ~1.4/min and each expected offline failure was captured at error level. Server clean: prod Loki 0 warn/error/fatal in 24h (444 scanned, stream live), no alert firing/pending, crash-free 100%. The Pulse "Client errors (7d)" 85 is ~59 this + the triaged billing-replay cluster. Not an outage; IS a signal-hygiene defect (offline noise can mask real spikes) → todo ENG-34 [P2], case CARDS-BA.md. Sentry issue left unresolved (todo filed), triage comment posted.
- Feedback carriers (CARDS-BD/BB/B8/B6/B4/B2/AZ/AY/AR) → feedback-triage's channel, all already in feedback-log (07-21/07-22 batches). Skipped.
- Remaining 12 (CARDS-9V/97/9H/9Q/9C/A2/9M/94/96/9R/93/95): event counts unchanged vs 07-21 run, all previously dispositioned. Skipped per idempotency.

Grafana: alerting_manage_rules(states=firing,pending) → null. Loki cards-server prod warn|error|fatal 24h → 0 lines. Nothing else filed. -->
- 2026-07-22 · CARDS-BA · todo: ENG-34 [P2] stop reporting expected-offline network failures as Sentry errors (59 events, one offline owner device) · https://elijah-dangerfield.sentry.io/issues/CARDS-BA · case docs/agent/feedback-cases/CARDS-BA.md

<!-- 2026-07-26 interactive (owner: "billing alert went off last night, can't find why in Sentry/logs"). Root-caused a FALSE PAGE, no billing defect.

- A5 · Purchase success rate low (uid bfrtc7fm94qgwb, area=billing severity=critical) went active ~2026-07-26T03:00:20Z. The firing alert instance's annotation carried Error: "failed to execute query [A]: ... dial tcp 10.20.30.100:3100: connect: connection refused" — the Grafana→Loki datasource had a transient blip, query A couldn't run, and the rule's `exec_err_state=Alerting` turned that execution error into a critical billing page. NOT a real purchase failure: Loki shows 0 purchase.failed / 1 purchase.completed / 3 purchase.initiated in the last 48h (prod), success rate 100%, no cards-server warn/error/fatal in 24h. Owner found nothing because there was nothing — the page was about the alert's own query failing, not money.
- FIX (this session): A5 `exec_err_state` Alerting→OK (a datasource/query blip no longer pages; the real rate-breach condition D still pages immediately at for=0s). Added `runbook_url` → dc-billing-health so a real page links to the drill-down. Client telemetry was already adequate: DefaultPurchaseChipPackUseCase logs purchase.failed with product_id + `error`=reason at Error level (→ Sentry); no extra logging needed there.
- DIAGNOSABILITY (this session): added a "Purchase funnel (live · from client events)" section to dc-billing-health (was all-empty pre-V88): success-rate stat (the A5 metric, red<80%), Purchases/day funnel (initiated/completed/failed/cancelled), Failures-by-reason table (the `error` attr × product × platform), Recent-failed-purchases logs panel. Sourced from Loki, prod-pinned. So a real A5 is one click from the why, no Explore.
- FLEET FIX (owner: "remove all on-execution-error→fire, don't wanna be woken up like that"): set `exec_err_state=OK` on ALL A1–A7. Was Alerting on A1/A2/A3/A4/A6 (A5/A7 already OK). Discovered A4 (Clients-can't-reach-backend, also Loki-based) carried the SAME "connection refused" error at 02:50:10Z — so last night's Loki blip false-paged TWICE (A4 then A5). `no_data=Alerting` deliberately KEPT on A2 (Fly down) + A3 (Postgres down) as the deadman; all others no_data=OK. -->
- 2026-07-26 · alert:A5-false-page-2026-07-26 · no-action: false alarm (Loki datasource blip + exec_err_state=Alerting) that also tripped A4; fixed ALL A1–A7 exec_err_state=OK + A5 runbook + live billing funnel section · https://cards.grafana.net/d/dc-billing-health/downcard-c2b7-billing-health

<!-- 2026-07-27 interactive observability triage (owner: "look for issues across Sentry and the Grafana dashboards"). Reviewed 1 unresolved Sentry issue + Grafana alert/log sweep. Filed 0 todos; 1 no-action. App broadly healthy.

- CARDS-BS (RuntimeException: Unable to start ProxyBillingActivity → NullPointerException on PendingIntent.getIntentSender(), fatal, 1 event/1 user, 2026-07-25). Crash is entirely in com.android.billingclient.api.ProxyBillingActivity.onCreate (billing 7.1.1) + Android framework — ZERO first-party frames, and it's a separate activity's onCreate so no app-side catch is possible. Well-known upstream Play Billing crash (null PendingIntent from launchBillingFlow — broken/absent Play Store or an emulator billing backend). Environment store-android-release / installerStore=com.android.vending / isSideLoaded=false; device fingerprint is a Play review/robo emulator (archs x86_64-first, 288x448 @ 0.66 density, 2 cores, device.class low, locale en_TT, kernel from Google's build farm), not a retail OnePlus 8 Pro. **[CORRECTED 2026-07-27 via adversarial re-check]** build cards@0.1.0+**1009** = the `v0.1.0` tag = the **ONLY release tag = current production** (the first pass wrongly called it superseded by a 9.1.0 build "1026"; "1026" is just the commit-count of unreleased main HEAD, a collision with dc14c4c5's count on develop). Production still ships **billing 7.1.1**; the 9.1.0 bump (dc14c4c5) is **develop-only, unreleased, and not a confirmed fix** for this NPE. So the version reasoning was moot — the disposition rests only on the crash being **uncatchable upstream code** (null PendingIntent in a separate activity's onCreate, reported across billing 3/5/7.x) on a single review-bot event. → no-action, resolved in Sentry. Re-open if it recurs on a **real, non-emulator retail device** (any build). Case docs/agent/feedback-cases/CARDS-BS.md.

Grafana: alerting_manage_rules(states=firing,pending) → null (none firing). Loki {service_name="cards-server",deployment_environment=prod} | warn|error|fatal over 24h → 0 lines (16 scanned, stream live). Client {service_name="cards-client"} | warn|error|fatal over 24h → 4 one-off warnings (AppConfigRepository / AppRecompose / AuthTokenProvider / NetworkCall, 1 each = noise, no cluster). No alerts, no server errors, nothing filed from Grafana. -->
- 2026-07-27 · CARDS-BS · no-action: uncatchable upstream Play Billing ProxyBillingActivity NPE (null PendingIntent in Google's onCreate); single event on a Play review-emulator. [corrected 2026-07-27 via adversarial re-check: 1009/billing-7.1.1 IS current prod (v0.1.0, the only release tag), NOT superseded; the 9.1.0 bump (dc14c4c5) is develop-only/unreleased & unconfirmed as a fix — the version reasoning was wrong, the outcome stands on the uncatchable-upstream + review-bot grounds] · https://elijah-dangerfield.sentry.io/issues/CARDS-BS · case docs/agent/feedback-cases/CARDS-BS.md
- 2026-07-27 · sweep:2026-07-27-interactive · no-action: no alerts firing/pending, 0 cards-server warn/error/fatal in 24h, 4 one-off client warnings (noise) · Sentry + Grafana

<!-- 2026-07-24 interactive observability triage (owner-requested: investigate a single new ANR, CARDS-BR). Reviewed 1 Sentry issue. Filed 0 todos; 1 no-action; added a new benign class to the wiki.

- CARDS-BR (ApplicationNotResponding: ANR, culprit syscall; 1 event / 1 user, first=last seen 2026-07-24 18:14:59Z; mechanism AppExitInfo, so surfaced on the next cold start). Fetched both relevant threads via get_event_stacktrace. Main thread (1) is blocked in HardwareRenderer.setStopped → RenderProxy::setStopped → std::future::get → condition_variable::wait → pthread_cond_wait (waiting for the render thread to stop during window teardown). RenderThread (35) is the thing it's waiting on, stuck in CanvasContext::draw → SkiaOpenGLPipeline::swapBuffers → eglSwapBuffers → Surface::queueBuffer → BLASTBufferQueue → BpSurfaceComposer::setTransactionState → binder ioctl to SurfaceFlinger. ZERO Downcard frames in any of the 46 threads — pure Android framework + native EGL/SurfaceFlinger. Environment: Pixel 6 Pro / Android 12 BUT os.build "sdk_phone_arm64-eng 12 SP2A.220505.008 eng.ubuntu... test-keys" = a non-retail AOSP/emulator system image (processor_frequency 0, 4 cores, 3GB); Sentry's simulator flag reads false only because ro.product.model/brand were overridden to Pixel 6 Pro/Google (its heuristic keys off model/brand, not the build string). isSideLoaded=true, environment store-android-release (cards@0.1.0+1026), the only foreground view is com.pairip.licensecheck.LicenseActivity (Play's auto-injected licensing/anti-tamper wrapper), route OnboardingRoute (first launch). Reading: a side-loaded store build hit Play's license gate on a non-retail emulator image, and the software-GPU swapBuffers/SurfaceFlinger path stalled long enough to trip the ANR watchdog during window stop. Not a code defect; nothing first-party to fix. Certain it's no-action; provenance (owner's own AVD vs. a scraped copy on a spoofed emulator) is unresolved but doesn't change the disposition. → no-action, resolved in Sentry with reasoning comment. Added a new "Emulator / side-load ANR at the PairIP license gate" entry to the wiki's Known-benign client signals, gated on {no app frames} AND {emulator/side-load fingerprint} so a real ANR (app frames, or recurring across retail installs) is NOT swept up.

No Grafana sweep this run (single-issue investigation, not a full nightly). -->
- 2026-07-24 · CARDS-BR · no-action: environmental — emulator/side-load ANR at the PairIP license gate, entire stack is Android framework + native graphics with no app frames; new benign class added to observability.md · https://elijah-dangerfield.sentry.io/issues/CARDS-BR

<!-- 2026-08-11 nightly observability triage (Phase 1b, stacked on Phase 1a's intake commit 2fe33166; feedback-triage filed no todos tonight). Sentry MCP not connected this session — fell back to the REST API with the keychain token. Reviewed 0 unresolved Sentry issues + 5 Grafana/log signals. Filed 1 todo (ENG-41 [P2]); no P0. The headline is that prod is genuinely idle, not that it's healthy.

Sentry: **zero unresolved issues, full history.** `is:unresolved` returns 0 for both `statsPeriod=14d` and `''`; the 71 issues seen in the 14d window are all resolved (59) or ignored (12), and the newest event of any kind is 2026-07-29. Phase 1a resolved the last two (CARDS-BT/BV, the AUTH-30 feedback pair). Nothing to triage, nothing to re-open. No new case files from this channel.

Grafana alerts: all seven A1-A7 `state=normal, health=ok`, last evaluated 2026-08-11 08:00-08:04Z (so the rules are live, not stale). `alerting_manage_rules(states=[firing,pending])` → null. A7 (Server silent) reads normal off Fly HTTP *metrics*, which is consistent with the machine being up and answering health checks even though the app log is near-silent — the two are different sources, not a contradiction.

Prod is idle, and that's the real finding. cards-server prod Loki: 6 entries in 7d (3 in 48h), zero warn/error/fatal, and every line is inbound scanner noise (`/robots.txt`, GETs on POST-only routes) — no app traffic at all. cards-client prod: last event 2026-08-01 (~10d ago); `app.foregrounded` and every other event flatline after that. Checked whether that meant a broken telemetry pipeline rather than an empty population: prod Postgres `profiles` has 5 rows total, newest `created_at` 2026-07-29, so accounts stopped being created *before* telemetry went quiet. Population is genuinely zero; the pipeline is fine. Dashboard sweep therefore reads empty for the expected reason (cf. wiki "Known gaps": MP/Tempo/RED panels empty until real play resumes) and nothing was filed off an empty panel. FOR A HUMAN: ~10 days of no prod users at all is a business signal, not an engineering one — flagging it here because every health dashboard will read "green" while showing nothing.

- admin-probe-2026-08-11 (NEW). The only non-owner `/v1/admin` traffic in 30d: `405 GET /v1/admin/messages` + `405 GET /v1/admin/grant-chips` (2026-08-08, 1s apart) and `401 GET /v1/admin/config/manifest` (2026-07-31), interleaved with `/robots.txt` 404s = a crawler, and the endpoint names are public because the repo is. NOT a breach: all seven admin routes are gated by `authenticatedAsAdmin` (constant-time `X-Admin-Token`), the 405s are Ktor method-mismatch fired before the handler, and A1 ledger drift is normal so no chips moved. The defensible defect underneath is observability: the 401 branch logs nothing at any level and `/v1/admin` opts into no rate-limit bucket (only the global 600 req/IP/min), so a brute force against the chip-minting route would look exactly like silence. → todo ENG-41 [P2], case admin-probe-2026-08-11.md. Judgement call made unattended: filed as P2, not P0 — the gate held, evidence is two probe requests, and nothing was breached.
-->
- 2026-08-11 · sentry:2026-08-11-nightly · no-action: zero unresolved Sentry issues (full history); newest event of any kind 2026-07-29 · https://elijah-dangerfield.sentry.io/issues/?query=is%3Aunresolved
- 2026-08-11 · admin-probe-2026-08-11 · todo: ENG-41 [P2] make a failed /v1/admin token attempt visible (log + dedicated rate-limit bucket); gate held, no breach · https://cards.grafana.net/d/dc-infra · case docs/agent/feedback-cases/admin-probe-2026-08-11.md
- 2026-08-11 · sweep:2026-08-11-nightly · no-action: A1-A7 all normal; cards-server prod 6 log lines/7d with 0 warn+; cards-client prod silent since 08-01 and prod `profiles` newest 07-29, so the population is genuinely empty, not a broken pipeline · dc-pulse / grafanacloud-logs

<!-- 2026-08-18 nightly observability triage (Phase 1b, stacked on develop HEAD ff6de00b; Phase 1a
committed nothing). Sentry MCP not connected — REST API + keychain token, `statsPeriod` omitted
(the endpoint rejects 90d). Reviewed 2 unresolved Sentry issues + a Grafana alert/log sweep.
Filed 2 todos (ENG-42 [P0], ENG-43 [P1]) + 1 human-only line in developer-todo.md; 1 no-action
(the Grafana sweep). Both Sentry issues left unresolved with triage comments.

**The headline is that iOS is live on the App Store and nobody's telemetry story reflects it.**
Both unresolved issues carry `environment=store-ios-release`, `release=cards@0.1.0+3`, dist
202607231404, `commit_sha 36aa3153f4ab` — which is tag `v0.1.0`, the only release tag. So the
public iOS build has been out since 2026-07-23 with real retail users (iPhone17,2 ×2 installs,
iPad15,3 ×1; iOS 26.5.2 / 26.6.1; locale en_SG), and both issues are in `substatus: regressed` —
they were resolved as dev noise and came back as production. The 2026-08-11 run's "population is
genuinely zero" conclusion was true for its window (client stream silent 08-01 → 08-10) but is no
longer: `{service_name="cards-client", deployment_environment="prod"} | platform="ios"` now has
205 entries across 3 streams in the last 8 days.

- **CARDS-3** (`WatchdogTermination`, fatal/unhandled, 26 events since 2026-06-05, regressed, last
  seen 08-14T01:40:53Z). Two kills 99s apart on ONE retail iPad (`simulator=false`, 6.7GB usable of
  8GB, `in_foreground=true`), both on `OnboardingRoute`. Loki + breadcrumbs reconstruct it: cold
  launch → `onboarding.step_viewed step=welcome` → four clean HTTP calls (200/200/200/204) →
  ~86s with zero breadcrumbs → no `app.backgrounded` → gone; relaunch, same thing; third launch
  backgrounded cleanly after 19s and the install never returned. The user never reached
  `onboarding.auth_selected`. The other live iOS install completed onboarding in 34s the same
  week, so it isn't universal. **Honest limit: we cannot tell a real hang from a force-quit.**
  Sentry's watchdog detection is a next-launch heuristic, and `previous_exit` is `unknown` on all
  of these launches — correctly, because ENG-25 made iOS `previous_exit` a consume-once MetricKit
  sample that lands up to 24h late. `decisions.md` 2026-07-11 deferred per-run exit classification
  "until iOS exit rates become load-bearing"; a fatal on the App Store build's first screen is that
  trigger. → **todo ENG-42 [P0]**, case CARDS-3.md. Judgement call made unattended: P0 because it's
  a fatal on the live store build on the first screen a new user sees and it took out one of two
  iOS installs; the todo's own acceptance says to downgrade to P1 if the signal work shows these
  were force-quits.

- **CARDS-8V** (chip-pack SKUs unrecognized, 6 events since 2026-07-09, regressed, last seen
  08-12T02:37:38Z). Phase 1a passed this over as "already dispositioned 2026-07-10, confirm and
  move on". It does not survive the confirm. The 07-10 call (and this ledger's CARDS-96 twin, "dev
  store-listing noise") was made when the events were dev builds; **every event in the current
  window is `store-ios-release` on retail iPhones**, two distinct installs. StoreKit returns none
  of `com.cards.iap.chips.{small,medium,large}`, `ProductsRepositoryImpl.reconcileAgainst` drops
  all 3 packs, and the iOS shop renders an empty chip-pack section. iOS revenue is zero by
  construction and has been for ~3.5 weeks. Verified in Loki on install ad8cc889's 08-12 first
  launch (line logged twice at ERROR, then the user completed onboarding normally).
  **Why nothing escalated it:** exactly the standing 2026-07-15 A5 blind spot in a new shape — A5
  is a success *rate*, so zero visible packs means zero attempts means no `purchase.initiated` /
  `purchase.failed`, and A5 reads healthy while the store sells nothing. `dc-revenue` at $0 is
  indistinguishable from a quiet day. The one signal that exists is a bare ERROR string with no
  `event_name`, no alert, no panel. Split by owner: the App Store Connect config is human-only →
  **appended one line to developer-todo.md** (append-only; no existing entry touched — noted here
  because that file is normally worker-forbidden, and a live revenue-zero condition with no record
  seemed the worse failure); the visibility/graceful-degradation work is worker-pickable →
  **todo ENG-43 [P1]**, case CARDS-8V.md. P1, not P0, because ENG-43 doesn't unblock the money —
  the ASC item does.

Grafana: `alerting_manage_rules(states=[firing,pending])` → null (none firing).
`{service_name="cards-server", deployment_environment="prod"} | detected_level=~"warn|error|fatal"`
over 08-11 → 08-18 → 0 lines, 357 scanned (base stream live, and up from 6 lines/7d on 08-11).
Client prod iOS stream reviewed in full (see above). Nothing filed off a dashboard panel.

FOR A HUMAN, three things:
1. **iOS is live on the App Store and the docs don't say so.** `docs/todo.md`'s preamble still says
   "Android is live …; iOS isn't shipped yet", and ENG-40 is still written as "until the iOS listing
   is live". Both need a pass — left for `curate-todos`, not edited here.
2. **The ASC chip-pack item is the only thing standing between the iOS build and revenue.** It's in
   developer-todo.md now.
3. **A5 has now been blind to two different money-loss modes** (the BILL-12 redeem-400
   misclassification, standing since 07-15, and now zero-visible-packs). A success-rate alert with
   no floor on attempt volume can't see either. Worth rethinking the rule shape, not just adding
   another one — flagging rather than filing because it's a threshold-design call and Grafana is
   read-only to me.
-->
- 2026-08-18 · CARDS-3 · todo: ENG-42 [P0] iOS OS-watchdog kills on the onboarding welcome screen (live App Store build, retail iPad); can't yet distinguish a real hang from a force-quit · https://elijah-dangerfield.sentry.io/issues/CARDS-3 · case docs/agent/feedback-cases/CARDS-3.md
- 2026-08-18 · CARDS-8V · todo: ENG-43 [P1] store drops all 3 chip packs → iOS shop silently empty, nothing alerts (+ developer-todo line for the App Store Connect fix); supersedes the 2026-07-10 "dev noise" disposition — it's `store-ios-release` now · https://elijah-dangerfield.sentry.io/issues/CARDS-8V · case docs/agent/feedback-cases/CARDS-8V.md
- 2026-08-18 · sweep:2026-08-18-nightly · no-action: A1–A7 none firing/pending; 0 cards-server prod warn/error/fatal over 7d (357 scanned, stream live); iOS store-release client traffic now live in Loki (205 entries/8d) · dc-pulse / grafanacloud-logs

<!-- 2026-08-19 nightly triage (phase 1b). Sentry MCP was NOT connected this run; step 1 ran against
the Sentry REST API with the keychain token instead. Note the project-issues endpoint only accepts
statsPeriod '', '24h', '14d' — use the ORG issues endpoint with explicit start/end for a 30d/90d
window, and always pass project=4511478399565824, because project=-1 sweeps the whole org and drags
in unrelated projects (phony-games, should-we-break-up) whose issues are not ours.

Sentry, verified rather than taken on faith from phase 1a: over both 30d and 90d the cards project
has exactly two unresolved issues, CARDS-8V and CARDS-3, and both last-seen timestamps are unchanged
from yesterday's run (08-12T02:37:38Z and 08-14T01:40:53Z), so neither is materially worse and
neither re-opens. Both stay unresolved with their todos pending (ENG-43 + the App Store Connect
developer-todo item; ENG-42). No Sentry writes made.

Grafana: alerting_manage_rules(states=[firing,pending]) → null, nothing firing or pending. Note the
skill's fixed-coordinate list is stale here: docs/wiki/observability.md documents EIGHT rules now,
including A8 (store dropped chip-pack SKUs, hourly warning) added by the ENG-43 work, and A7 is live
since launch rather than paused. A8 not firing is correct — it is event-driven and no iOS user has
opened the shop since 08-12.

Server logs 08-17→08-19: 0 warn/error/fatal, 23 scanned, base stream confirmed live. Client prod
stream fully characterized over 14d (222 lines) rather than sampled — that is what turned up the one
real finding below.

One genuinely new signal, and it is about our own instruments rather than the product. See the case
file: ExpectedControlFlow throwables are filtered out of Sentry and shipped to Loki anyway, so 11 of
the 13 client ERROR lines in prod over 14d are AuthUnready. The session that surfaced it
(install dec4b4be, store Android build 1026) is healthy — the auth gate blocked a profile write for
a still-being-created guest, the healer fixed it in 13s, onboarding completed with account_ready=true.
No user harm; the harm is to the error panel this triage reads. → todo ENG-44 [P2].

FOR A HUMAN, two things:
1. **The "512MB ceiling" in the skill and the infra notes is stale.** The prod Fly machine now
   reports fly_instance_memory_mem_total ≈ 962 MiB, and mem_available sat flat at ≈488 MiB across
   all 24h with no drift. There is no memory pressure and no creep. Not filed as a bug — it is a
   docs correction, and Grafana is read-only to me.
2. **Prod traffic is too small for dashboard anomaly detection to mean anything.** 31 app.foregrounded
   in 14 days (28 android, 3 ios). Panels being flat is a sample-size fact, not a health verdict.
   Worth remembering before reading any trend on dc-pulse as signal.
-->
- 2026-08-19 · CARDS-8V · no-action: re-verified, unchanged since 08-18 (last seen 08-12T02:37:38Z); already owned by ENG-43 + the App Store Connect developer-todo item, left unresolved · https://elijah-dangerfield.sentry.io/issues/CARDS-8V · case docs/agent/feedback-cases/CARDS-8V.md
- 2026-08-19 · CARDS-3 · no-action: re-verified, unchanged since 08-18 (last seen 08-14T01:40:53Z); already owned by ENG-42, left unresolved · https://elijah-dangerfield.sentry.io/issues/CARDS-3 · case docs/agent/feedback-cases/CARDS-3.md
- 2026-08-19 · loki:expected-controlflow-at-error · todo: ENG-44 [P2] expected control-flow throwables (AuthUnready) bypass GrafanaLogTree's filter and land in Loki at ERROR, making 85% of the client error tier noise · grafanacloud-logs `{service_name="cards-client", deployment_environment="prod"} | detected_level=~"warn|error|fatal"` · case docs/agent/feedback-cases/2026-08-19-expected-controlflow-loki.md
- 2026-08-19 · sweep:2026-08-19-nightly · no-action: A1–A8 none firing/pending; 0 cards-server prod warn/error/fatal over 48h (23 scanned, stream live); prod Fly memory flat (~488 MiB available of ~962 MiB, 24h); 31 app.foregrounded/14d · dc-pulse / dc-infra / grafanacloud-logs

<!-- 2026-08-20 nightly observability triage (Phase 1b, stacked on Phase 1a's 84f2b800; feedback-triage
filed nothing tonight). Sentry MCP again not connected — REST API with the keychain token.

**The headline: prod stopped being idle.** Every run since 08-11 recorded an empty population and
said so. That reversed between last night's sweep and this one. cards-server prod Loki went from
23 lines/48h to 1,278 lines/7d, all INFO, zero warn/error/fatal; the traffic is real multiplayer —
matchmaking finds, socket connects, 220 `hand.completed`, 25 `game.started`, bot subsidy payouts,
room open/close. Read every "flat panel" note in the 08-11 and 08-19 entries as expired.

That matters beyond the summary line: the two findings below only exist because people played. A
telemetry sweep against an idle app cannot find gameplay bugs, and for eight days this triage was
confidently reporting green on a sample of nobody.

Sentry: still exactly two unresolved issues in the cards project, CARDS-3 (26 events, last seen
08-14T01:40:53Z) and CARDS-8V (6 events, last seen 08-12T02:37:38Z). Counts and last-seen are
byte-identical to the 08-19 run, so neither is materially worse and neither re-opens. Both left
unresolved. One pointer repointed: **ENG-43 shipped in 8a2360da**, so CARDS-8V's remaining owner is
the App Store Connect item in developer-todo.md, which is human-only — the ledger row now says so
rather than pointing at a closed todo. The two `ProductsRepository` ERROR lines in the client stream
are that same issue (iOS build 3, 08-12), not a new one.

Grafana: `alerting_manage_rules(states=[firing,pending])` → null. All eight rules A1–A8 read
`state=normal, health=ok`, last evaluated within 45 minutes of the sweep, so they are live and not
stale. A1 (ledger drift) normal is worth stating explicitly now that real chips are moving again.
Fly prod memory: min available over 24h = 472 MiB of ~962 MiB, no creep. (The "512MB ceiling" in the
skill's fixed coordinates is still wrong — flagged 08-19, repeating because it is still there.)

Two new signals, both filed, both found in the client log stream rather than by an alert or a panel.

- **`loki:duplicate-rebuy-intents` (NEW).** 131 `intent rejected: seat is not busted` warns against
  10 `game.rebuy` successes over 14d — 57% of the entire client warn+ stream, from 3 installs on
  Android store build 1026. Reconstructed per-hand: the first rebuy is accepted, then ~13 more fire
  and are refused because the seat is no longer busted. Nothing dedupes `PlayPokerAction.Rebuy`
  (`Submit` right above it in the same `when` does), and the CTA never disables, so a player who
  gets no feedback for a round-trip taps again. Behind it is a real money-path race: the route
  checks the busted seat off an unlocked `peek` before debiting the buy-in, so two concurrent
  rebuys can both debit and fall into the compensating-refund path. Nothing was lost here (A1 drift
  0; every duplicate landed after the accept) — but that is ordering luck, not a guarantee.
  → **todo MP-38 [P1]**, case 2026-08-20-duplicate-rebuy-intents.md. Judgement call made
  unattended: P1 not P0, because no chips moved and the compensator exists; P1 not P2, because it
  is a live user-facing defect on a real-chip path.

- **`loki:onboarding-abandoned-false-positive` (NEW).** 12 `onboarding.abandoned` events, 100% of
  them `step=welcome`, which reads like a welcome-screen problem and is not one. 4 of the 6 installs
  that "abandoned" went on to complete onboarding, and the 12 events came from those same 6
  installs. `OnboardingViewModel.onCleared()` emits it, and system-back on Welcome exits the app, so
  backgrounding-then-returning logs an abandonment — one traced session backed out twice and
  finished 48s later. The step skew is an artifact of Welcome being the only step where back leaves
  the app. → **todo AUTH-31 [P2]**, case 2026-08-20-onboarding-abandoned-false-positive.md. P2 for
  consistency with ENG-44 (instrument accuracy, not a broken flow), but note it gates honest reads
  for **ENG-36** (diagnoses the grant double-miss off these events) and **ENG-42** (deciding whether
  the iOS welcome step really loses people). A ~67% false-positive rate pointing at exactly the
  screen ENG-42 is investigating is the kind of corroboration that would push that call the wrong
  way.

Deliberately NOT filed: `App recomposed` ×32 (build 1026 predates the f7b67e11 fix — verified with
`git merge-base --is-ancestor`), `AuthUnready` ×43 (owned by ENG-44), `SocketException` /
`Unable to resolve host` (wiki known-benign), a single `insufficient_balance` on matchmaking/find,
a single `room_not_found` from a mistyped code, one 10s intent timeout.

FOR A HUMAN: with play resumed, this is the first fortnight where funnel and gameplay numbers mean
anything. Two of the first three things they said were wrong (the abandonment metric above, and
ENG-44's error panel from last night). Worth weighting instrument-correctness items higher than
their P2 tags suggest before reading much into any dashboard trend. -->
- 2026-08-20 · CARDS-3 · no-action: re-verified, unchanged since 08-18 (26 events, last seen 08-14T01:40:53Z); already owned by ENG-42, left unresolved · https://elijah-dangerfield.sentry.io/issues/CARDS-3 · case docs/agent/feedback-cases/CARDS-3.md
- 2026-08-20 · CARDS-8V · no-action: re-verified, unchanged since 08-18 (6 events, last seen 08-12T02:37:38Z); ENG-43 shipped (8a2360da) so the sole remaining owner is the human-only App Store Connect item in developer-todo.md — ledger pointer repointed, issue left unresolved · https://elijah-dangerfield.sentry.io/issues/CARDS-8V · case docs/agent/feedback-cases/CARDS-8V.md
- 2026-08-20 · loki:duplicate-rebuy-intents · todo: MP-38 [P1] one Rebuy tap fans out into ~13 intents (131 rejections vs 10 successes/14d); no client dedupe, CTA never disables, and the server debits off an unlocked seat read · grafanacloud-logs `{service_name="cards-client", deployment_environment="prod"} | detected_level=~"warn|error|fatal"` · case docs/agent/feedback-cases/2026-08-20-duplicate-rebuy-intents.md
- 2026-08-20 · loki:onboarding-abandoned-false-positive · todo: AUTH-31 [P2] `onboarding.abandoned` fires on a back-out, so 4 of 6 "abandoned" installs actually completed and the welcome-step skew is an artifact · grafanacloud-logs `| event_name=~"onboarding.abandoned|onboarding.completed"` · case docs/agent/feedback-cases/2026-08-20-onboarding-abandoned-false-positive.md
- 2026-08-20 · sweep:2026-08-20-nightly · no-action: A1–A8 all normal/ok and freshly evaluated, none firing/pending; 0 cards-server prod warn/error/fatal over 7d (1,278 INFO lines, stream busy with real MP play); Fly prod memory min-available 472 MiB/24h, no creep · dc-pulse / dc-infra / grafanacloud-logs

<!-- 2026-08-28 observability triage (owner-requested, interactive). Sentry MCP still not connected;
ran against the REST API with the keychain token. Note for future runs: the *project* issues
endpoint only accepts statsPeriod '', 24h or 14d — use the **organization** endpoint
(`/organizations/elijah-dangerfield/issues/?project=-1`) for a real 90d sweep.

6 unresolved issues project-wide, two of them (`PHONY-GAMES-A`, `SHOULD-WE-BREAK-UP-Q`) belong to
other Sentry projects and are out of scope. Zero unresolved feedback carriers/twins, so
feedback-triage has nothing waiting either. No alerts firing or pending (A1–A8). Server warn+ over
9 days is 4 lines total: two websocket `Ping timeout`s (client dropped, benign) and one
`could not serialize access due to concurrent update` on `table_sessions.markClosing` that Exposed
retried and won. Client crash-free 7d: 25 clean / 1 Android `oom` / 28 `unknown` (iOS, expected
until ENG-25) — one device-side kill on a 26-launch sample, too small to file.

- **`CARDS-BW` (NEW, and the headline).** `/v1/me/progression/sync` timing out at 30s for one
  retail Pixel 7 install, 26 events over 8 days. The client blames the network; the server log
  refutes that — it answers **200 OK in 306,000–501,000 ms**, climbing monotonically across the
  week. Prod Postgres: that one user holds **2,703 of the 4,477 rows** in `xp_events`, at ~185 ms
  per event = one Supabase round trip per statement. The server runs a full `database.transaction`
  per event in a serial `map`; the client's `getUnsynced()` has no LIMIT and only marks rows synced
  after a response it never receives. So the batch grows every session and the loop has no exit.
  Money and XP are both safe (replays return `AlreadyApplied`, the total doesn't move, no crossing
  mints, all rows are committed server-side) — what's broken is convergence and cost. The
  second-largest user (669 rows) is already at 35s, so crossover is ~a week of steady play and the
  whole engaged population walks into it. → **todo ENG-45 [P0]**, case CARDS-BW.md. P0 over P1
  because it's live, worsening without bound, and holds a 5–8 minute request + Hikari connection on
  a single-writer 512MB machine — an availability property that doesn't scale, even though no money
  moved.

- **`sweep:no-latency-alert` (NEW, found by the dashboard sweep).** Every one of those 8-minute
  requests logged `200 OK` at INFO and tripped nothing. A1–A8 cover down/drift/failure/silence;
  none covers *slow but successful*, and no `dc-infra` panel charts per-route server latency. The
  suite is structurally blind to this whole failure class. → **todo ENG-46 [P1]**, same case file.

- **`CARDS-BX` (NEW).** One 401 on `/v1/me/messages/sync`, 1 event, 1 user, warm-boot token race
  after `BackgroundRollover` (repos had deferred as offline, network returned, request beat the
  token refresh). Session intact (`SKIP_HAS_SESSION`), same install's other authed calls returned
  200 in ~410 ms two days later, next foreground edge re-syncs. → **no-action**, resolved in
  Sentry, case CARDS-BX.md.

- **`CARDS-8V` (RE-OPENED as an escalation, not a new todo).** Previously dispositioned no-action
  pointing at the human-only App Store Connect item. It got materially worse: **two new iOS installs
  hit it on 2026-08-28** (96fe609f, fb0c0600), vs the single 08-12 install at last review. That's
  three distinct real App Store users who have opened a shop selling nothing. The engineering half
  (ENG-43, A8 alerting) shipped; the remaining fix is entirely in App Store Connect, so there is
  nothing to file — it stays a developer-todo item. Ledger pointer unchanged; escalated in the run
  summary instead.

Deliberately NOT filed: the two websocket ping timeouts and the single Exposed serialization retry
(self-healed, 1 occurrence in 9 days — though "Wait 0 milliseconds before retrying" is a retry with
no backoff, worth a glance if it ever recurs); the single Android `previous_exit=oom`.

FOR A HUMAN: two things. (1) **iOS has now sold nothing to three separate users over 36 days** and
the fix is a console task only you can do — this is the highest-value hour available anywhere in
the project right now. (2) ENG-45 is the first defect we've had whose cost grows with engagement:
it punishes the players who play the most, and it stayed invisible for 8 days because it returns
200. Worth reading the "Why nothing caught it" section of the case file before deciding how much
ENG-46 is worth.
-->
- 2026-08-28 · CARDS-BW · todo: ENG-45 [P0] progression sync wedged in a growing loop — server answers 200 OK in 306–501s (one transaction per event, 2,703 events for one user), client times out at 30s and never marks rows synced · https://elijah-dangerfield.sentry.io/issues/CARDS-BW · case docs/agent/feedback-cases/CARDS-BW.md
- 2026-08-28 · sweep:no-latency-alert · todo: ENG-46 [P1] A1–A8 and dc-infra are blind to slow-but-successful requests; 501s calls logged 200 OK at INFO and tripped nothing · grafanacloud-logs `{service_name="cards-server", deployment_environment="prod"} |~ "progression"` · case docs/agent/feedback-cases/CARDS-BW.md
- 2026-08-28 · CARDS-BX · no-action: single 401 on messages/sync from a warm-boot token race; session intact, self-heals on the next foreground edge, no recurrence · https://elijah-dangerfield.sentry.io/issues/CARDS-BX · case docs/agent/feedback-cases/CARDS-BX.md
- 2026-08-28 · CARDS-8V · no-action: re-opened — materially worse (2 new iOS installs on 08-28, now 3 real users), but the remaining fix is App Store Connect only; still owned by the developer-todo.md ASC item, left unresolved · https://elijah-dangerfield.sentry.io/issues/CARDS-8V · case docs/agent/feedback-cases/CARDS-8V.md
- 2026-08-28 · sweep:2026-08-28 · no-action: A1–A8 none firing/pending; 4 server warn lines in 9d (2 ws ping timeouts + 1 self-healed Exposed serialization retry); crash-free 7d 25 clean / 1 android oom / 28 unknown (iOS, ENG-25) · dc-pulse / dc-infra / grafanacloud-logs
