# In-flight log

Ephemeral handoff notes from workers to the reviewer. Reviewer deletes when the PR opens/updates.

## docs(wiki): correct sibling-bus semantics in client-patterns (ENG-24)

**Problem:** The client-patterns wiki claimed `SessionRejectionBus` shares `ShopDeepLinkBus`'s conflated / consume-once shape and placed both buses in `:libraries:cards` — both wrong.
**Approach:** Rewrote the "Sibling buses" section around the real impl (non-replaying `MutableSharedFlow`, buffer 8, `DROP_OLDEST`, plus `rejectionEpoch`), explained *why* the two delivery semantics differ (lazy consumer vs boot-time consumer), and added `AccessDeniedBus` since its kdoc explicitly names it the mechanically-parallel sibling. Fixed the Key-files module paths, including the stale `ShopViewModel.observeBus` reference (the VM collects `scrollRequests` in `init {}`).
**Reviewer notes:** None.
