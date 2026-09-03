# Turning on R8 obfuscation (ENG-53)

**Status:** not started. Play deadline **Feb 2027**, so this is deliberate work, not a fire.
**Supersedes:** the backlog note "Enable R8 minification for the Android release" (`docs/backlog.md`), which had the right analysis but predates Play putting a date on it.

## What Play is actually asking

Android vitals → "App optimization is below our threshold: **Obfuscation (1%)**". The 1% is
bundled libraries that shipped pre-obfuscated; none of our own code is. Play's stated consequence
is that percentages under 25% "may impact your visibility and publishing capabilities".

Current state, in one line: `ApplicationConventionPlugin.kt` sets `isMinifyEnabled = false` for
release, there is no `proguard-rules.pro` anywhere in the repo, and release builds therefore
produce no `mapping.txt`.

## Read this first: our tests cannot catch R8 breakage

This is the part that matters for "how do we know it's safe", and it is uncomfortable.

**Every test we have runs on unminified bytecode.** `apps/integration` is `androidUnitTest`, so
the scenario harness, the ViewModel tests and the server suite all execute JVM classes that R8
never touched. There is also no Robolectric or `compose-uiTest` infrastructure at all
(`docs/plans/compose-ui-testing-spike.md`: "none of this has been built"). So there is no existing
test, at any level, that would go red if R8 broke the app.

R8 failures are release-only and specific: a DTO that won't deserialize, a nav route whose class
name changed, a service loader that finds nothing. They surface as a crash in one flow, on a real
device, after upload.

**So the safety net is not tests. It is: correct keep rules, a smoke run against the actual
minified artifact, and a staged rollout with readable crash reports.** Building a UI test
framework to de-risk this would be the wrong order of work — it is a much bigger project than the
change it would be protecting, and it still would not run against the minified build unless we
also set `testBuildType = "release"` and wrote instrumentation tests.

## Where this stack will actually break

Ranked by risk, not by how scary they sound. Most modern libraries ship their own
`consumer-rules.pro`, which is why this is tractable at all.

| Risk | Why | Mitigation |
|---|---|---|
| **Type-safe nav routes** | `androidx.navigation` keys destinations on the route's **qualified class name**, and our routes are `@Serializable` classes. Renaming them changes the key. | `-keep` the route classes and their `Companion`/serializer. Highest-risk item here. |
| **kotlinx.serialization** | `@Serializable` models resolved through generated serializers; sealed hierarchies and `@SerialName` are the sharp edges. | Ships consumer rules that cover the common case. Verify sealed/polymorphic DTOs explicitly. |
| **supabase-kt** | Least mature keep rules of anything we depend on, and it sits on the auth path. | Exercise sign-in and token refresh on the minified build. |
| **Ktor engine selection** | Engines can be found via `ServiceLoader`, which R8 does not see. | Keep the engine class if the release build fails to make a request. |
| **`::class` name reads** | Becomes `a$b` after renaming. The backlog's "Obfuscation watch" guidance mostly held (log tags are stable strings), but a grep found stragglers — see below. | Fix `KLog` before Stage 2; the rest are cosmetic. |
| **kotlin-inject / anvil** | Compile-time codegen, not reflection, so usually fine. | Cold launch is the test: a broken graph fails immediately. |
| **Room** | Ships consumer rules. | Covered by the persistence step of the smoke run. |

### Stragglers found 2026-09-03

A grep for `::class.simpleName` / `::class.qualifiedName` outside tests turned up three groups.
Only the first is a mechanism rather than a message:

- **`libraries/core/.../logging/KLog.kt:256-258`** uses `ScopedLogger::class.qualifiedName`,
  `KLog::class.qualifiedName` and `LoggingEngine::class.qualifiedName` — a stack-frame filter that
  finds the real caller by skipping the logging framework's own frames. Under obfuscation those
  names no longer match the frames, so the filter stops filtering and every log line gets
  attributed to the logger instead of its call site. **Fix this before Stage 2**, or every log we
  ship afterwards points at the wrong place.
- **`DefaultGuestAccountCreator.kt:101,157,198`** interpolate a state class name into log
  *messages*. Cosmetic: the message gets less readable, nothing breaks.
- **`IconButton.kt:141,173`** render `icon::class.simpleName` as fallback preview text. Cosmetic
  and preview-only.

## The plan

Three stages, each independently shippable and revertible, so a failure tells you *which class* of
change caused it rather than "R8 broke something".

### Stage 1 — shrink only, no renaming

```
isMinifyEnabled = true
```
with `proguard-rules.pro` containing `-dontobfuscate` and `-dontoptimize`.

This validates that nothing we need got *removed*, with names still intact so any stack trace is
readable. If the app misbehaves here, it is a missing `-keep`, not a naming problem.

Run the smoke checklist below. Ship it. Let it sit through a staged rollout.

### Stage 2 — turn on renaming

Drop `-dontobfuscate`. This is the one Play is asking for, and the one that breaks
name-dependent code: nav routes, serializers, anything reflective.

Before building, confirm `release.yml` uploads `mapping.txt` to Sentry. It already resolves the
mapping conditionally and uploads only when minify produced one, so this should just start
working — but check the run log rather than assuming, because from here on an un-deobfuscated
crash report is useless.

Run the full smoke checklist again. Ship at **the smallest staged rollout Play allows** and watch
Sentry for a day before widening.

### Stage 3 — optimization

Drop `-dontoptimize`, and consider `isShrinkResources = true` for the size win. Lowest value and
highest weirdness, so do it last or not at all.

## Smoke checklist (run against the installed release build, not debug)

Each line exercises a different failure class. This is the actual safety net.

```bash
./gradlew :apps:compose:assembleRelease
adb install -r apps/compose/build/outputs/apk/release/compose-release.apk
```

1. **Cold launch** — the DI graph builds. Catches kotlin-inject and anvil.
2. **Onboarding as guest through to Home** — network, auth, DTO deserialization, supabase-kt.
3. **Navigate to every tab, then deep into one** (Shop → a product sheet) — type-safe nav routes.
4. **Play a full bots hand to showdown** — gameplay engine, Room persistence, the sync outboxes.
5. **Force-quit and relaunch** — Room read-back, session restore.
6. **Settings → About** — confirms `BuildInfo` still reports a real version rather than `a$b`.
7. **Check Sentry** received a readable release and, if anything crashed, a deobfuscated trace.

Steps 1 to 4 can be driven with `adb shell input` the same way the update-sheet screenshot was
captured; it is not elegant but it exercises the real artifact, which is the only thing that
counts here.

## Rollback

`isMinifyEnabled = false` and re-release. There is no data migration and no server coupling, so
reverting is a one-line change. That is a large part of why the staged approach is affordable.

## What would make this genuinely safe later

Not required for this work, but worth knowing the option exists: `testBuildType = "release"` plus
a handful of instrumentation tests would let a real UI test run against the minified artifact in
CI. That is the only way any of this becomes automatically verifiable. It is a bigger project
than enabling R8, so it should be justified on its own merits rather than smuggled in here.
