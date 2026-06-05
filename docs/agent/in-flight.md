# In-flight

## feat(stats): recent-XP rows get source emoji, type-colored points, relative time

**Problem:** Recent-XP rows showed a plain label + uncolored `+N` and never said when the XP was earned.
**Approach:** Each row now leads with a per-`XpSource` emoji (🃏/🪙/👀/💪/🎯), colors the `+N` by source (content/teal/coral/info/success), and stacks an `earnedAgo(...).label()` relative timestamp under the points — reusing the existing day-granular `EarnedAgo` DS util the hint pointed at rather than building a parallel minutes/hours formatter. Source label strings were left as the pre-existing inline literals (out of scope; not new violations).
**Reviewer notes:** `EarnedAgo` buckets at day granularity, so several same-day events all read "today" — acceptable per the "earnedAgo-style" hint, but if the mock wants minute/hour precision that's a follow-up formatter. No new tests: the maps are exhaustive `when`s over `XpSource` (compiler-checked) and the relative-time factory is already covered by `FormatEarnedAgoTest`. Verified by module compile + `:features:progression:impl:testDebugUnitTest`.
