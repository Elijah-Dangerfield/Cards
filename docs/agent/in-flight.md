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

## fix(config): seed flag from manifest default when a rule attaches (ENG-4)

**Problem:** Adding a targeting rule to a flag with no DB row hit the FK and 409'd; the admin worked around it by minting a DB base override from the in-code default before every rule write (so a failed add could leave an unintended base override), and launchOp's failure path skipped reload() so a rejected rule looked like it silently failed to render.
**Approach:** Moved the seed server-side: PUT /rules/{id} now lazily materializes the flag row from its shipped *manifest* default (seedFlagFromManifestIfMissing) before attaching the rule; a flag with neither a DB row nor a manifest entry still returns an honest 409. Dropped the client-side upsertFlag(seed) side effect, and made launchOp reload on both success and failure. The real server message was already surfaced via AdminApiException/describeError. Test-first: added a red-then-green route test for the lazy-seed path plus a guard for the no-manifest 409.
**Reviewer notes:** Judgement call — I chose lazy-seed-from-manifest over relaxing the FK (keeps the rule→flag invariant and a clean resolve union; rejected FK-relax for splitting the source of truth). The seed writes an audited `create_flag` row with the manifest default, which is honest (it's the shipped value, not an operator guess) and sets ENG-5 up to relabel that layer as a read-only "shipped default" vs an explicit "global override". Sliced ENG-5: its server-side lazy-create sub-part is done here, so I trimmed that clause; the UX reframe (read-only in-code default, relabel base editor) remains under ENG-5.
**Deferred:** ENG-5 UX reframe stays in docs/todo.md (rewritten to drop the now-shipped lazy-create clause).

## refactor(admin): reframe base value as an explicit global override (ENG-5)

**Problem:** The config admin presented three layers (in-code default → DB base value → resolved) and made editing the "base value" a primary action, implying the site owns the default. The app actually *ships* the default; the site should mainly do targeting.
**Approach:** Relabeled the layers in `FlagsView.kt` — "shipped default" reads "what the app ships with (read-only)" and only renders an editor for the optional "global override (all targets)" layer (the DB row), which now only shows when one exists. The editor section explains the override falls back to the shipped default when removed, and the empty-rules / button copy was reworded to match. The shipped default was already read-only (no editor existed for it); this makes that hierarchy legible instead of presenting the override as the base the operator owns.
**Reviewer notes:** UI-only relabel in the JS admin tool — no behaviour/endpoint change (still `upsertFlag`/`deleteFlag` under the hood). No test infra in `:apps:admin` (no jsTest source set), and the change is pure copy/layout, so no test added. Left the "Add a brand-new flag" form copy as-is; it creates an override row for a path the app may not ship, which is a legitimate (if rarer) use and out of scope for this relabel.
