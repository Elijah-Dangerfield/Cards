# In-flight log

## docs(wiki): describe grace-expiry forfeit as built, drop MP-1 clause (ENG-21)

**Problem:** The multiplayer wiki's player-states section still framed the forfeit/spectator downgrade as future work "when `MP-1` ships," but MP-1 landed in #67/#69/#70.
**Approach:** Rewrote the bullet to describe the as-built reap path (MemberLeft → `settleLeaver` → `forfeitSeat` → `removePlayer`, table plays on) rather than just deleting the MP-1 clause — the old text also implied the reaped player's socket stays attached as a spectator, which isn't what the code does (grace expiry means the socket is already gone; non-member spectator attach is a separate, live path the page covers two bullets down).
**Reviewer notes:** Verified the section's adjacent claims against the server on the way through: sit-out is still engine-only (`SittingOut` exists only in `Seat.kt` + engine tests, no client toggle), 5-min `DEFAULT_REAPER_GRACE`, 25s `FORMING_PUBLIC_REAPER_GRACE`, Public-only bot trim, spectator Private-room block. All hold.

## docs(wiki): cover account claim as built + single-writer model (ENG-22)

**Problem:** `docs/wiki/architecture.md` listed Apple/Google claim as future "Phase 3.1" work (it shipped — `ClaimAccountScreen`) and never mentioned the single-writer hosting model.
**Approach:** Updated the stack-table auth row and added a "Single-writer hosting model" section written from `SingleWriterGuard.kt`'s actual behavior (boot-time session-level advisory lock, crash-loop-on-contention, connection-close handoff, deferred sharding). Placed it after the client/server boundary section since it's a hosting-shape fact, not a stack-table row.
**Reviewer notes:** None.

## test(profile): add an active-XP-boost preview to ProfileScreen (ENG-23)

**Problem:** ENG-23 claimed ProfileScreen had zero previews; it already has three (on `main`, not just develop), covering signed-in-with-items + friend requests, guest nudge, and stocked shelf.
**Approach:** Shipped the one acceptance variant genuinely missing — the active XP boost banner — as `ProfileScreenPreview_ActiveBoost`. `rememberBoostRemainingMs` pins the countdown in inspection mode, so any non-null expiry renders the burning state statically. Alternative was to close the item as already-satisfied, but the boost banner was a real coverage hole worth the 20 lines.
**Reviewer notes:** The todo-maintainer's 2026-07-04 top-up appears to have run against a stale view of the tree — three of tonight's five items claimed "zero previews" for files that have had them on `main` for a while. Worth a look at the maintainer's checkout step.

## docs(todo): drop already-satisfied preview items (ENG-24, ROOM-16)

**Problem:** Both items asked for preview coverage that already exists on `main`.
**Approach:** Verified acceptance line-by-line instead of re-shipping: `AppGuardLayer.kt` previews all four guard states (Normal / MaintenanceBanner / MaintenanceBlocking-with-message / UpgradeRequired); the rooms screens carry eleven previews spanning `PublicFindScreen` normal (wallet-capped at 50k) + insufficient and every `PublicSearchingScreen` phase (searching, chooser with candidates, joined waiting/ready, bot-offer with three subsidy variants, joining-bots, both errors). Removed the bullets with no code change.
**Reviewer notes:** Same stale-premise pattern as ENG-23 above — see that block's note about the todo-maintainer.
