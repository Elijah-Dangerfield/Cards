# 2026-10-04 — CARDS-CK: an SDK_INT guard that could never run

**Signal:** Sentry CARDS-CK, `NoClassDefFoundError: Failed resolution of: Landroid/app/ApplicationExitInfo;`
**Where:** `AndroidPreviousExitProvider`, reached from `AppLaunchedEmitter.onForeground` on cold boot
**Who:** Huawei P20 (EML-L09), **Android 10 / API 29**, France, `cards@0.5.0+1272`, store release
**Status:** fixed 2026-10-04, held for 0.6.0 (not a crash, so not hotfix-worthy)

## What happened

`ApplicationExitInfo` is API 30. The class had:

```kotlin
private val exitInfo: ApplicationExitInfo? by lazy { readLatestExitInfo() }
```

and `readLatestExitInfo()` opened with the correct guard:

```kotlin
if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
```

**The guard was unreachable.** `by lazy` compiles to a synthetic `getExitInfo()` whose return
type is `ApplicationExitInfo`. ART resolves a member's signature in order to execute it at all,
so on API 29 `getExitInfo()` threw before a single instruction of `readLatestExitInfo()` ran. The
stack says exactly this: `previousExit(...:33)` → `getExitInfo(...:30)`, line 30 being the `by lazy`.

An `SDK_INT` check protects the *instructions* inside a method. It cannot protect a type named in
a field or method **signature**.

## Why it looked harmless

`AppEventDispatcher.notifyListeners` wraps every listener in `Catching`, so nothing crashed. The
cost was quieter and worse:

- **No `app.launched` event on any device below API 30**, for as long as this shipped.
- A Sentry error logged on every cold boot for those users.
- **The version-adoption numbers were wrong.** The `service_version` breakdown is built on
  `app.launched`, so Android ≤10 users on 0.5.0 were invisible in it. Adoption read lower than
  reality, and we were measuring crashes and OOM on exactly the low-end population most likely
  to have them.

Shipped in 0.4.0, which never published (parked behind Play managed publishing), so 0.5.0 was its
first real exposure. Two days from rollout to detection.

## The fix

Keep the platform type out of every signature reachable below API 30:

- The provider now holds `ExitRecord?` — plain data, ints and strings.
- Every mention of `ApplicationExitInfo` lives in `ExitInfoReader`, a `@RequiresApi(R)` private
  object loaded only from behind the SDK check.
- `previousExitForReason` still names `ApplicationExitInfo.REASON_*`, which is safe because those
  are compile-time constants and get inlined to integers. There is a comment saying so, because
  the next person to touch it needs to know the safety is not accidental.

## On the test

`AndroidPreviousExitProviderApiLevelTest` asserts the structural rule by reflecting over the
compiled class and failing if the API-30 type appears in a field, return, or parameter type.

It is an indirect test and worth being honest about why. A JVM unit test **cannot** reproduce this
defect: android.jar stubs put `ApplicationExitInfo` on the test classpath at every API level, so
there is no `NoClassDefFoundError` to assert on. `PreviousExitReasonMappingTest` uses the class
directly and passed throughout, before and after. Reproducing the real failure needs an API-29
device or emulator. The structural rule is the part that is checkable in CI, so that is what is
checked.

Verified red/green by reintroducing the exact offending shape (`private val probe:
ApplicationExitInfo? by lazy`) and confirming the test fails, then removing it and confirming it
passes.

## The actual lesson

This section first said Android Lint "would have caught it before it was ever committed." **That
was wrong, and checking took one command.** `lintRelease` run against the exact buggy commit comes
back clean.

Lint understands the `SDK_INT` guard wrapped around the API *call* and is satisfied by it. What
broke was the *signature* — the synthetic getter's return type — and lint does not model class
resolution at that level. A deliberately unguarded `getHistoricalProcessExitReasons` call in the
same file does trip `NewApi`, so the check is live and working; this variant is simply invisible to
it. That is worth knowing precisely, because "lint will catch the next one" is a comfortable belief
that would have left the real gap unguarded. The reflection test is the only thing watching it.

Lint went into CI anyway (ENG-77) and immediately paid for itself with four unrelated real errors,
including an `ACCESS_NETWORK_STATE` permission the app only had because `androidx.work` happened to
declare it. Just not this one.

Worth noting what did and did not find this: not a test, not a review, not an alert, and not lint.
It surfaced because someone read a Sentry issue list two days after release. The dashboards stayed
green the whole time — they were green *because* the telemetry had stopped.
