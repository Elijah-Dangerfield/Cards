# In-flight (cycle handoff)

## fix(identity): bind Darwin engine on the Supabase client (ENG-30)

**Problem:** On iOS release build 821 every `POST /auth/v1/signup` aborted with "TLS sessions are not supported on Native platform" because `createSupabaseClient` left the Ktor engine to auto-resolution and picked a TLS-incapable native engine, so no guest account ever minted and the "Finishing setup" banner was permanent.

**Approach:** Pass `httpEngine = platformHttpEngineFactory.create()` into the `createSupabaseClient { }` builder in `SupabaseClientFactory`, so the Supabase client binds Darwin on iOS / OkHttp on Android — the same seam ENG-28 used for the first-party clients. Updated the factory KDoc (it previously *documented* the fragile auto-detection as intentional).

**Reviewer notes:** The regression guard (`SupabaseHttpEngineTest`, new iosTest in identity/impl) mirrors `PlatformHttpEngineTest` and asserts that the engine factory this module feeds Supabase resolves to `Darwin` on iOS — it ran green on the simulator. Judgement call: I deliberately did *not* introspect the built `SupabaseClient`'s engine to assert usage directly, because that path goes through supabase-kt's `@SupabaseInternal` `KtorSupabaseHttpClient`, which is fragile across library upgrades. The factory-level mirror + the one-line production change are what pin the fix. If a reviewer wants a stronger guard, the durable option is a boot smoke test that mints a guest on a real iOS build (needs simulator + keychain, out of scope for a unit test). Verified `:apps:compose:assembleDebug` (Android/OkHttp path) and the iOS compile both stay green.
