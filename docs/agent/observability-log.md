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
| [CARDS-9H](https://elijah-dangerfield.sentry.io/issues/CARDS-9H) | Redeem unreachable — order left uncredited | no-action: dup of BILL-7 |
| [CARDS-96](https://elijah-dangerfield.sentry.io/issues/CARDS-96) | Store did not recognize 3/3 chip-pack SKUs | no-action: dev store-listing noise |

## Grafana signals

| Slug | What | Disposition |
|---|---|---|
| `alerts:A1-A7-2026-07-12` | Firing-alert sweep (rules A1–A7) | no-action: none firing |
| `health:writers-2026-07-12` | Single-writer health, both envs | no-action: one healthy writer per env; no server error/fatal logs in 24h |

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
