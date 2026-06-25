# In-flight

## fix(profile): use Catching over runCatching in highlight pulse (ENG-3)

**Problem:** `MyItemsShelves`'s highlight-pulse `LaunchedEffect` wrapped `highlightRequester.bringIntoView()` in `runCatching`, the one client-side `runCatching` left in a main source set — it swallows `CancellationException` so a recompose that cancels the effect mid-call is silently eaten.
**Approach:** Swapped to `Catching {}` from `:libraries:core` (rethrows `CancellationException`). `rg runCatching` over client commonMain now only matches the `Catching` definition itself.
**Reviewer notes:** None.
