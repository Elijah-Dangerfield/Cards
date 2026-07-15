# In-flight

Handoff log for the reviewer. One block per commit this cycle.

## fix(billing): map redeem 4xx to Rejected, not Unavailable (BILL-12)

**MONEY PATH — please eyeball.** Reproduced test-first (red at `expected Rejected / was Unavailable`, green after the fix) in `BillingRepositoryImplTest`.

**Problem:** `BillingRepositoryImpl.redeem` runs through `authedCall`, whose client has `expectSuccess`, so a 400 throws `ClientRequestException` at `client.post(...)` before the old `when(response.status){ BadRequest -> Rejected }` branch could run. `Catching` caught the throw and `getOrDefault(Unavailable)` collapsed a hard `receipt_rejected` into `Unavailable` — the user saw "chips on the way / payment went through" and the unfinished transaction was re-redeemed every launch. The `BadRequest -> Rejected` branch was dead code.
**Approach:** dropped the unreachable status `when` (the success block now just parses the 200 body into `Granted`) and map the failure in `getOrElse`: a `ClientRequestException` (4xx) is the server's definitive "no" on this receipt, so it becomes `Rejected` (honest failure dialog, no false-pending); everything else (5xx / timeout / unreachable) stays `Unavailable` so the launch-time redeemer can still recover a paid-for purchase. This matches `RetryPolicy`'s own transient-vs-permanent split (its default predicate already refuses to retry 4xx). Chose mapping-the-thrown-exception over disabling `expectSuccess` for this one call so the repo keeps the shared client contract the rest of the app relies on.
**Reviewer notes:** A rejected outstanding transaction is still deliberately left unfinished (not consumed) by `DefaultPurchaseChipPackUseCase.redeemOutstanding`, so it keeps being retried on each launch — that's intended and is exactly what lets BILL-11's server fix recover the stranded small/large packs once it lands. Added `ktor-client-mock` + explicit networking/ktor test deps to `:libraries:billing:impl` (main's `implementation` deps aren't on the test classpath in this KMP setup).
