# In-flight (follow-on run)

## chore(server): replace runCatching with Catching in five callsites (ENG-1)

**Problem:** AGENTS.md mandates `Catching {}` over `runCatching` everywhere (it rethrows `CancellationException`), yet five `apps/server` production callsites still used raw `runCatching`.
**Approach:** Mechanical swap in `WebhookConfigChangeNotifier`, `PostgresRoomStore`, `RoomSocketRoutes` (x2), `ConfigAdminRoutes`; added the `Catching` import where missing. `Catching` is a `runCatching` drop-in returning `Result`, so `.getOrNull()` / `.onFailure` callsites are unchanged.
**Reviewer notes:** None. Server compiles.
