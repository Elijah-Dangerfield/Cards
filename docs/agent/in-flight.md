# In-flight — worker handoff log

## docs(wiki): describe server-minted reward chips (ENG-16)

**Problem:** `docs/wiki/progression.md` still described the pre-ENG-9 flow (client grants level/achievement chips optimistically via `levelup_<level>`, server "confirms or voids").
**Approach:** Rewrote the "Levels and level-up rewards" section + grant-path table around the as-built flow (server mints at progression/achievements sync under `levelup:<n>` / `achievement:<id>` keys, `RefusedServerOwned` on client-asserted credits, local credit is display-only). Also went beyond the item's named page: `docs/wiki/achievements.md` made the same stale claim ("server-authoritative reward granting: deferred"), so its table row, data-flow diagram, and that section now say granting shipped and only unlock *detection* remains client-trusted. Corrected the XP-boost row too (stash grant, manually activated — not `boostExpiresAt` extension).
**Reviewer notes:** None.

## docs(identity): drop the phantom sign-out clear from AvatarPackCache (ENG-18)

**Problem:** `AvatarPackCache`'s KDoc promised a sign-out clear via a `ProfileRepositoryImpl` SignedOut listener "once that gets wired — see the TODO there"; neither the listener nor the TODO exists.
**Approach:** Took the doc fix the item itself recommended — the catalog is user-agnostic, so the KDoc now says there is deliberately no sign-out clear (only the staleness drop). Rejected wiring an actual clear: it would add code to invalidate data that stays correct across accounts. Also killed the stale "(mock)" in `PublicFindScreen`'s KDoc per the item's hint.
**Reviewer notes:** None.

## refactor(admin): migrate the config-admin app off runCatching (ENG-17)

**Problem:** `apps/admin` production code used `runCatching` in 9 places, against the repo-wide `Catching` convention (swallows `CancellationException`).
**Approach:** Dropped a minimal local `Catching` (typealias + factory, same rethrow-cancellation contract) into `apps/admin` and migrated all 9 callsites. Rejected the alternative of adding a `js()` target to `:libraries:core`: the admin module's own build file declares it deliberately shares no client code, and growing core a JS target (plus js actuals for its expects) for one local-only dev tool is the wrong trade.
**Reviewer notes:** The local helper is a knowing duplication of core's `Catching` semantics — if a second shared type ever gets needed in `apps/admin`, that's the moment to revisit a core `js()` target.
