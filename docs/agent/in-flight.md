# In-flight (cycle handoff)

## fix(identity): bind Darwin engine on the Supabase client (ENG-30)

**Problem:** On iOS release build 821 every `POST /auth/v1/signup` aborted with "TLS sessions are not supported on Native platform" because `createSupabaseClient` left the Ktor engine to auto-resolution and picked a TLS-incapable native engine, so no guest account ever minted and the "Finishing setup" banner was permanent.

**Approach:** Pass `httpEngine = platformHttpEngineFactory.create()` into the `createSupabaseClient { }` builder in `SupabaseClientFactory`, so the Supabase client binds Darwin on iOS / OkHttp on Android — the same seam ENG-28 used for the first-party clients. Updated the factory KDoc (it previously *documented* the fragile auto-detection as intentional).

**Reviewer notes:** The regression guard (`SupabaseHttpEngineTest`, new iosTest in identity/impl) mirrors `PlatformHttpEngineTest` and asserts that the engine factory this module feeds Supabase resolves to `Darwin` on iOS — it ran green on the simulator. Judgement call: I deliberately did *not* introspect the built `SupabaseClient`'s engine to assert usage directly, because that path goes through supabase-kt's `@SupabaseInternal` `KtorSupabaseHttpClient`, which is fragile across library upgrades. The factory-level mirror + the one-line production change are what pin the fix. If a reviewer wants a stronger guard, the durable option is a boot smoke test that mints a guest on a real iOS build (needs simulator + keychain, out of scope for a unit test). Verified `:apps:compose:assembleDebug` (Android/OkHttp path) and the iOS compile both stay green.

## feat(site): support page (FAQ + Contact us) + Settings row (SITE-1)

**Problem:** Both app stores require a public support URL on the listing, but `pages/` only hosted `privacy.html` + `terms.html`, so store submission was gated on it.

**Approach:** Added `pages/support.html` (an FAQ + "Contact us" mailto, same `style.css` and header/footer shape as the legal pages), linked it from the `index.html` footer plus a Support section, and cross-linked the privacy/terms footers to it. Added `LegalUrls.SUPPORT` (the single source of truth the client hands to `Router.openWebLink`) and surfaced a "Help and FAQ" row in Settings' Account & support section, wired in `ProfileFeatureEntryPoint`. Ran the `unslop-text` voice check against the page copy and the new strings (warm, plain, contractions, no em dashes).

**Reviewer notes:** The URL follows the existing `elijah-dangerfield.github.io/Cards/...` pattern; if/when the custom `downcard.app` domain lands, `LegalUrls` is the one update point (its KDoc already says so). The browser preview tool was unresponsive in this environment, so I verified the page structurally against the privacy/terms templates rather than a live render — worth a real-device look via the new QA entry `SITE-1`. `:apps:compose:assembleDebug` is green.
**Deferred:** The `developer-todo.md` launch item "Support contact + public support URL" is now technically satisfied by this page, but I did not touch that human-only file — reviewer, please note it in the PR Heads up so the human can tick it.
