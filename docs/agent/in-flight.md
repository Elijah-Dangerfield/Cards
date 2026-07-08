# In-flight log

## docs(wallet): document the silent InsufficientChips reconcile (ENG-25)

**Problem:** `docs/wiki/wallet.md` claimed a refused debit means "client surfaces a toast"; no toast exists — the client logs a warning, drops the event, and silently resets to the authoritative balance.
**Approach:** Rewrote the sync-contract outcome table to match the code, and added the missing `RefusedServerOwned` row (server-owned reward credits, ENG-9) plus a line on the client's resolved-vs-unknown handling, since "table matches the code" was the acceptance and the table was missing a whole outcome. Also corrected the same stale "soft reconcile message" claim in the `WalletRoutes.kt` KDoc — comment-only, no behavior change, despite the item's "no code change" hint, because leaving the same lie in the server doc-comment would defeat the item.
**Reviewer notes:** The wiki now points the "no user-facing surface yet" gap at the backlog; I appended the wallet-reconcile case as a third bullet on the existing "Surface a reason when a multiplayer intent is rejected / the room closes" backlog item rather than filing a new one — same transient-surface shape.
**Deferred:** The user-facing surface for a refused debit — appended to the grouped backlog item above.
