# In-flight log

Ephemeral handoff notes from workers to the reviewer. Reviewer reads these when writing the PR, then clears the file.

## docs(wiki): multiplayer host authority matches the ROOM-16 derived model (ENG-30)

**Problem:** `docs/wiki/multiplayer.md` still said the host "auto-promotes to the first still-connected member" on host drop, but since ROOM-16 host authority is derived, never reassigned: the effective host is the first connected human in seat order (fallback: first human), computed identically client/server, and the tagged `hostUserId` no longer gates mutations.
**Approach:** Rewrote the stale reconnect claim as its own architecture bullet (derived effective host, client/server mirror, `hostUserId` demoted to data), switched the Private "who deals" row to "Effective host," and added an "Effective host" glossary entry. Also updated `docs/QA.md` MP-5 — it described the same auto-promotion model — so its Expected now checks that the promoted member's host affordances actually work (the ROOM-16 bug was silent `not_host` 403s) and that the badge returns to the original host on reconnect, which the lobby VM test `effectiveHostUserId_originalHostReconnects_returnsToOriginal` pins.
**Reviewer notes:** QA MP-5 wasn't named in the ENG-30 acceptance but carried the identical stale claim; treating it as in-scope doc drift rather than deferring. Docs-only commit, no code touched.
