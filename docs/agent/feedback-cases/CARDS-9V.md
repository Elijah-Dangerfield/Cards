# Observability case CARDS-9V

- **Sentry issue:** https://elijah-dangerfield.sentry.io/issues/CARDS-9V (`ReceiptRejectedException: Receipt rejected: apple_account_mismatch (store=apple, product=chip_pack_small)`)
- **Signal:** server-side error issue, **no user feedback attached** (intake channel = Sentry crash/error). prod, escalating, 15 events / 1 user, first seen 2026-07-14T19:32Z, last 2026-07-15T00:36Z.
- **Culprit:** `BillingRoutes.kt:125` (`com.dangerfield.cards.server.routes.BillingRoutesKt$billingRoutes$1$1$1`)
- **Disposition:** no-action — **duplicate of BILL-11** (root) + BILL-12 (client misclassification). Feedback-triage filed both from the same install/session tonight; this is the machine-side (server-exception) twin of that user report. No second todo.

## IDs
- install: cb87c0e4-ca5b-4552-81f8-343b8312cd3f (owner device; AUTH-19 lineage 087ac8d1 → 52f3f9c1 → 6f0a900c) — **same install as CARDS-9Y/BILL-11 and CARDS-9H**
- caller (post-upgrade): 6f0a900c-b6b5-429c-a00b-c3ec6bc80f19
- stale appAccountToken: 52f3f9c1-1a94-4640-b24c-560a9b7534eb (pre-AUTH-19 guest id)
- session (server-side, this event): 502a2021-22df-43e0-a087-7b5ab35c8721 · trace 9371f0a85e545247c95e4df148357b0a
- product chip_pack_small (the stranded "…803" order from the BILL-11 report)

## Server activity (Loki `cards-server`, prod, trace 9371f0a8…, confirmed this run)
1. `AppStoreReceiptValidator.validate` (line 118, WARN): `Apple receipt account mismatch: appAccountToken=52f3f9c1… caller=6f0a900c…`
2. `BillingRoutes` (line 120, WARN): `Receipt rejected: reason=apple_account_mismatch store=apple product=chip_pack_small user=6f0a900c…`
3. `400 Bad Request POST /v1/billing/redeem` (1211ms)
The `ReceiptRejectedException` is captured to Sentry but logged to Loki only at **WARN** — it does not appear in the `detected_level=~"error|fatal"` server-error sweep.

## Working theory
Exactly the BILL-11 mechanism: StoreKit transactions for the small (…803) / large (…555) packs were minted under the pre-AUTH-19 guest id `52f3f9c1`, so their `appAccountToken` is `52f3f9c1`; after the account upgrade the caller is `6f0a900c`, and `AppStoreReceiptValidator.validate` (`apps/server/.../data/AppStoreReceiptValidator.kt:122`) rejects any receipt whose `appAccountToken` ≠ caller → `apple_account_mismatch` → 400 `receipt_rejected`. The 15 events are the client's cross-launch retry loop (BILL-12) re-posting the same stranded receipt. Fix is BILL-11 (accept receipts whose token is in the install's upgrade lineage / re-stamp on upgrade); the false-"pending" + infinite retry that generates the repeat events is BILL-12. Prod but sandbox/beta receipts only today → no real-money loss yet, must fix before real-money go-live. Full session detail in `docs/agent/feedback-cases/62fc0f3d25054a34a14bb00a93c06f09.md` (BILL-11).
