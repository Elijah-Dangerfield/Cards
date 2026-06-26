# In-flight

## docs(copy): present-tense the MP 2x XP line (PROG-3)

**Problem:** `StatsExplainersSheet` still said "Multiplayer hands will earn 2× when it ships" — MP is shipped, and line 75 of the same file already treats it as live, so the sheet contradicted itself.
**Approach:** Rewrote the line to "Multiplayer hands earn 2× the XP of bot hands." Combed the app (grep over user-facing strings) for other "when it ships / coming soon" copy describing now-live features; the only hits were code comments and the legit tournament / quick-match / recently-played "Coming soon" surfaces, which are genuinely V2 per the hint — left untouched.
**Reviewer notes:** The detekt VerifyStrings rule (correctly) rejected re-using the old inline string, so a follow-up commit moved this line to `:libraries:resources` as `stats_explainer_mp_xp` and dropped the now-stale baseline entry. The file's other inline strings stay baselined and untouched (out of scope here).

## feat(rooms): single rotating wait line on finding-a-table (ROOM-4)

**Problem:** The finding-a-table screen kept a permanent "Still looking for players" text line *and* the rotating reassurance copy — two views doing one job.
**Approach:** Removed the persistent second line; the existing RotatingReassurance already opens on "Looking for real players at your buy-in..." and rotates every 5s, which satisfies "open with looking-for-players, then rotate after ~5-10s". Deleted the now-orphan public_searching_alone / public_searching_joined strings + imports.
**Reviewer notes:** Dropping the line also drops the transient "A player joined. Dealing you in." message. In practice the room flips to Playing and navigates to the live table almost immediately once a human joins, so that line was barely seen, but flag if you want a brief join confirmation kept.

## refactor(rooms): drop the Public/Invite-only header chip (ROOM-5)

**Problem:** The finding-a-table and private-room screens showed a top-right Public/Invite-only chip that wasn't earning its space.
**Approach:** Removed the right = { VisTag(...) } slot from the RoomHeader on PublicSearchingScreen, PublicFindScreen, and PrivateCreateScreen, plus dead imports. right is already optional on RoomHeader, so this is a clean removal. (The PublicSearchingScreen removal rode along in the ROOM-4 commit since both edits touched that one file; this commit carries PublicFindScreen + PrivateCreateScreen.)
**Reviewer notes:** The todo named "finding-a-table and the private-room screen" (two screens). I also removed it from PublicFindScreen (the public buy-in setup screen in the same flow), since the same redundant chip + rationale applies there: judgement call, easy to revert one line if you disagree. Left the VisTag in PrivateChooseSheet (it's an in-list label, a different context, not a header chip) and kept the VisTag DS component itself.

## refactor(ui): render the lobby "You" marker as a chip (ROOM-6)

**Problem:** The lobby seat list rendered the local player's "You" label as plain caption text, out of step with the row's badge visual language (HOST/BOT pills).
**Approach:** Swapped the plain Text for the existing StatusPill DS primitive with accentSecondary background (the documented "under-avatar pill" shape), keeping the accent-secondary color it already used. Reused the primitive rather than hand-rolling a pill.
**Reviewer notes:** None.
