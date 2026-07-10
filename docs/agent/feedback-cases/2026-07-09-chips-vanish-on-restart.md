# Feedback case 2026-07-09-chips-vanish-on-restart

- **Sentry issue:** none — the original report was eaten by the sampling bug (see `2026-07-09-prod-feedback-never-ingested.md`); content relayed by the owner in chat 2026-07-10
- **Reported:** owner's first prod/TestFlight session, 2026-07-09 ~20:00–21:15 UTC, release `cards@0.1.0+1`
- **Disposition:** todo: PROG-11 (client sync bug) + ECON-1 (ledger gap) + BILL-6 (sandbox purchases) + ENG-17 (prod telemetry dark, found during investigation)

## Bug description (owner, verbatim-ish)
> An achievement granted me 500 chips but when I killed and reopened the app the 500 disappeared. Grafana showed total economy still 10k. Then I signed in with Google and my balance was magically 11,500, and the economy became 12k.

## Evidence — prod Postgres via Grafana datasource `cards-prod-db` (ffrewas5byf40d)

Backend OTel was useless (prod ships no logs/traces — ENG-17), but the `wallet_events` ledger + `wallets` tables tell the whole story. All times UTC 2026-07-09:

| time | event | delta | notes |
|---|---|---|---|
| 20:01:18 | wallet `6b608e78-…` created, balance seeded 10,000 | — | **no ledger row for the starter grant** (ECON-1) |
| 20:01:51 / 20:27:13 | IAP SKU errors in Sentry | — | launch + relaunch markers (the kill happened between) |
| 20:36:23 | `achievement_grant:POT_5000` | +500 | applied only when Google sign-in triggered a sync |
| 20:36:29 | `levelup_grant:3` | +1,000 | same sync burst |
| 21:10:36 | `iap.chip_pack_small` | +5,000 | sandbox purchase, order 2000001202599296 |
| 21:10:51 | `iap.chip_pack_large` | +120,000 | sandbox purchase; balance now 136,500 |

Second wallet `087ac8d1-…` created 2026-07-10 13:14:57Z, `shop.cardback_marble` −500, balance 9,500 (starter again unledgered).

## Working theory (server side confirmed; client side to reproduce)
Nothing was lost and nothing was duplicated. The achievement (+500) and level-up (+1,000) grants sat **unsynced client-side**; kill+relaunch (20:27) neither flushed the outbox nor folded it into the displayed balance, so the client showed the bare server balance (10,000) — chips "vanished". The Google sign-in (20:36) fired a full sync that applied both grants: 10,000+500+1,000 = **11,500 exactly** as observed. The "10k → 12k economy" was stat-panel rounding of 10,000 → 11,500. Conservation holds **except** starter grants bypass `wallet_events`: prod SUM(deltas) = 126,000 vs supply 146,000 — the 20,000 gap is the two unledgered starter grants.

Remaining client question for PROG-11 (test-first): why doesn't app launch flush/fold the progression outbox — is sync simply not triggered on cold start, or is the fold missing on the wallet display path?

Also confirmed live: TestFlight purchases are StoreKit sandbox (free) but recorded as real — 125,000 unpaid chips now dominate the prod supply and `billing_transactions` has no environment column (BILL-6).
