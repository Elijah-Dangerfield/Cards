# In-flight (worker handoff log)

## refactor(billing): delete Dev/NoOp billing clients (BILL-6)

**Problem:** `DevBillingClient` and `NoOpBillingClient` were dead code — both platform bindings replaced them, and their own TODO said to remove them once real bindings landed.
**Approach:** Deleted both classes, moved `DEV_FAKE_CATALOG` into `FakeBillingClient.kt` (both platform clients still construct the fake from it in debug), dropped the now-empty `replaces=` lists, and rewrote the kdoc in `BillingClient`, `ProductsRepositoryImpl`, and `BillingAvailabilityImpl` that described the old default-binding story.
**Reviewer notes:** `docs/agent/compose-ui-testing-spike.md` still cites the Dev→NoOp replacement as a DI-swap example; left it as-is since it's a historical spike record. `developer-todo.md` also mentions `DevBillingClient` but workers can't touch that file.
