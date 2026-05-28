## refactor(shake): route ShakeHandler through DispatcherProvider.main

**Problem:** `ShakeHandler` constructed its `CoroutineScope` with raw `Dispatchers.Main`, violating the repo's dispatcher-injection rule (production code consumes `DispatcherProvider.*` so tests can swap a `TestDispatcher`).
**Approach:** Added `dispatchers: DispatcherProvider` to the `@Inject` constructor and routed the scope through `dispatchers.main`. `apps:compose` already depends on `:libraries:flowroutines`.
**Reviewer notes:** No new test — the todo entry explicitly noted this dispatcher swap doesn't change observable behavior and no test sibling exists.
