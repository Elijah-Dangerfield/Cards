# In-flight (this cycle)

## feat(auth): flush user-scoped syncs on account claim

**Problem:** A guest claiming an account keeps the same `userId`, so `AppEvent.UserChanged` never fires — pending XP/chips/inventory/equipment only flushed on the next foreground.
**Approach:** Added a dedicated `AppEvent.AccountClaimed` event dispatched from `SupabaseAuthRepositoryImpl.emitLocked` when an anon→claimed transition keeps the same id (single choke point, so it covers Apple/Google/email claim paths automatically — chosen over nudging syncs from each claim call site, which would have been N call sites to keep in sync). The progression/chips/inventory/equipment/achievement repositories override `onAccountClaimed` to fire their (idempotent) `sync()`.
**Reviewer notes:** I included `AchievementRepositoryImpl` in the claim re-sync even though the todo only named progression/chips/inventory/equipment — the earned set is server-authoritative and a just-claimed account should reconcile it too; harmless (idempotent) but flag if you'd rather scope it down. `onAccountClaimed` has an empty default in `AppEventListener`, so non-syncing listeners (telemetry, in-app-message) are unaffected.
