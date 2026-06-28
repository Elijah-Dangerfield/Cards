# In-flight (this cycle)

Reviewer reads this to write the PR. One block per commit.

---

## fix(lobby): reconcile wallet on leave for real-chip rooms (MP-27)

**Problem:** After an opponent-left kick collapsed the play screen back to the lobby, leaving the lobby left the buy-in showing as escrowed until a foreground/background forced a `/wallet/sync` — the post-kick lobby exit landed Home on a stale balance.
**Approach:** `LobbyViewModel.Leave` now fires a one-shot, fire-and-forget `chips.sync()` (on `AppCoroutineScope` so it survives the screen pop) for any real-chip room (`Room.buyIn > 0`) before the `leaveRoom` POST, mirroring the play VM's `reconcileWalletAfterGame`. Free tables (`buyIn == 0`) skip it — nothing was escrowed. Kept it a silent sync rather than replicating the play screen's celebratory credit confirmation, which is intentionally play-screen-only.
**Reviewer notes:** Two new tests pin both branches (real-chip leave syncs once; free table doesn't). The fix is scoped to the explicit Leave path per the feedback-case diagnosis; if the kick ever lands the user on a *closed* lobby socket without an explicit Leave, the `walletReconciled` latch is already in place to extend the same reconcile to a close-under-us path. Server balance was already correct — this is client-display only.
