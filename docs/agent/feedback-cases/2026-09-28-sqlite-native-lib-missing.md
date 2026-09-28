# App is permanently unusable when `libsqliteJni.so` is missing

**Triaged:** 2026-09-28 · **Disposition:** todo **ENG-72 [P0]** · Sentry `CARDS-CD`, `CARDS-CE`, `CARDS-CC`

Room cannot open its database, the exception is unhandled, and the app dies on launch. There is no fallback and no recovery: the user's only option is to reinstall, and nothing in the app tells them that.

## What happens

```
java.lang.System.loadLibrary
androidx.sqlite.driver.bundled.NativeLibraryLoader.loadLibrary   (NativeLibraryLoader.android.kt:44)
androidx.sqlite.driver.bundled.BundledSQLiteDriver$NativeLibraryObject.<clinit>
androidx.sqlite.driver.bundled.BundledSQLiteDriver.open
  → UnsatisfiedLinkError: dlopen failed: library "libsqliteJni.so" not found
```

`RealAppDatabaseProvider` calls `.setDriver(BundledSQLiteDriver())`. That driver ships `libsqliteJni.so` as a per-ABI native library inside the app bundle. If the device's ABI split is absent, `dlopen` fails, `<clinit>` throws, and Room has no database.

`CARDS-CE` is the same fault one step later: once a class's static initialiser has thrown, the JVM marks it erroneous, so every subsequent touch raises `NoClassDefFoundError: …BundledSQLiteDriver$NativeLibraryObject` instead. CD/CE are one bug, not two.

All three events are `level=fatal`, `mechanism=UncaughtExceptionHandler`, `handled=no`.

## Scale and timing

| | |
|---|---|
| Users | 2 distinct (`071a2275…` on Pixel 6 Pro, `8c12ebe0…` on `sdk_gphone64_x86_64`) |
| Events | 3 |
| OS | Android 12, both |
| Release | `cards@0.1.0+1135`, `store-android-release` |
| First seen | 2026-09-27 23:59Z |
| Population | 746 `app.foregrounded` on 1135 over 7d, 25 on 1209 |

**It is on the old build, not the new one.** 1135 shipped 2026-09-03 and this did not appear until 2026-09-27, so the app code did not change underneath it. That rules out the R8 work in 0.3.0 as the cause, which was the obvious first guess.

## Why it is hard to attribute

The crash names `androidx.sqlite`, so it reads like a library bug. It is not. The library is doing the only thing it can when its native dependency is absent. The interesting question is why the `.so` is missing from an install that Play built, and that has nothing to do with the code in the stack trace.

The likeliest causes, none proven here:

- **A partial or interrupted app-bundle update.** Play composes an install from a base APK plus config splits (ABI, density, language). A base that lands without its ABI split produces exactly this: the app starts, then dies the moment it needs native code. The Sep 27 onset overlapping the v0.3.0 staged rollout is suggestive, since a rollout is when devices update, but two users is not enough to call it.
- **An ABI the bundle does not carry.** One of the two devices is an x86_64 emulator.
- **Storage reclamation** stripping split APKs.

## Why it deserves a fix regardless of cause

The cause is outside our control. The blast radius is not.

There is no `abiFilters`, no `extractNativeLibs`, no `jniLibs` packaging configuration anywhere in the build, so the app trusts Play completely and has no answer when that trust is misplaced. A missing native library is a recoverable condition that we currently treat as fatal.

`androidx.sqlite:sqlite-framework` provides `AndroidSQLiteDriver`, which uses the platform's own SQLite and needs no bundled `.so`. Catching the load failure and falling back turns "the app is bricked" into "the app runs on the platform database", which every Android device has.

## The fix

1. **Fall back.** Wrap the `BundledSQLiteDriver()` construction so an `UnsatisfiedLinkError` / `NoClassDefFoundError` selects `AndroidSQLiteDriver` instead. Note the fallback is not free: the bundled driver exists to pin a known SQLite version across OS levels, so record the fallback in telemetry rather than letting it happen silently.
2. **Make it visible.** Emit an event when the fallback fires. Today the only signal is a fatal crash; afterwards there would be none at all unless we add one, which would be worse.
3. **Then find the cause.** With the crash no longer fatal, the fallback rate tells you whether this is two unlucky installs or a systemic split-delivery problem, and whether it tracks the rollout.

## Proven vs inferred

**Proven:** the stack, that the driver is `BundledSQLiteDriver`, that no ABI/native-lib packaging config exists, that all three events are fatal and unhandled on 1135, and that this did not occur in the first three weeks of that build's life.

**Inferred:** that a missing ABI split is the mechanism. It is the standard cause of this exact stack, but the events carry no packaging metadata to confirm it.

**Not ruled out:** correlation with the v0.3.0 rollout. Two users over two days is too small to separate from coincidence. The fallback is worth shipping either way; the correlation question is answerable later from the fallback rate.
