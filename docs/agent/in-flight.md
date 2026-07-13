# In-flight (worker handoff log)

## fix(shop): refund chips when the XP-boost grant fails (ECON-2)

**Problem:** `ShopViewModel.confirmXpBoostPurchase` debited chips then called `xpBoostRepository.grant()` with no compensation — a grant write that failed after the debit left the player short chips with no boost.

**Approach:** Kept debit-first (targets the exact bug) and wrapped the grant in `Catching`; on failure I refund the exact spend via `chipsRepository.addChips("boost.refund.<id>")` and emit a new `ShopEvent.BoostPurchaseFailed` so the tap surfaces an error snackbar ("we put your chips back") instead of failing silently. This mirrors `InventoryRepositoryImpl`'s redeem/refund compensation and reuses `addChips` (already the refund path in inventory sync), so it needs no new repo API. Alternative rejected: grant-before-debit with a grant-revert — the faithful ordering-mirror of `redeemChipOffer`, but it would need a brand-new "remove from stash" method on `XpBoostRepository` that exists only for this rare error path, whereas the two stores can never share a real transaction anyway so compensation is the honest model either way.

**Reviewer notes:** The two mutations hit different stores (wallet outbox vs. boost cache) so true atomicity isn't possible; compensation is the ceiling. Both sides are local writes, so the failure path is rare — I still gave it real user feedback (error snackbar) rather than swallowing, and a queryable `shop.boost_grant_failed` log at the branch. Test-first: added `confirmXpBoost_grantFailsAfterDebit_refundsChips_andEmitsFailure` (red before the compensation, green after) plus a `grantError` hook on the test's `FakeXpBoostRepository`. New user-facing copy ran through the app's warm/plain voice, no em dashes.
