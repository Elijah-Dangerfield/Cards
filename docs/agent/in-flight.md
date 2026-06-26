# In-flight

## docs(copy): present-tense the MP 2x XP line (PROG-3)

**Problem:** `StatsExplainersSheet` still said "Multiplayer hands will earn 2× when it ships" — MP is shipped, and line 75 of the same file already treats it as live, so the sheet contradicted itself.
**Approach:** Rewrote the line to "Multiplayer hands earn 2× the XP of bot hands." Combed the app (grep over user-facing strings) for other "when it ships / coming soon" copy describing now-live features; the only hits were code comments and the legit tournament / quick-match / recently-played "Coming soon" surfaces, which are genuinely V2 per the hint — left untouched.
**Reviewer notes:** This copy lives as an inline string in the composable, not in `:libraries:resources`. I matched the file's existing convention rather than migrating it in this commit (out of scope for a copy fix). Flag if you'd rather it move to resources.
