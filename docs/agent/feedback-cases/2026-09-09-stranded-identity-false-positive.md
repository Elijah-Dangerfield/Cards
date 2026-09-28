# Grafana signal 2026-09-09-stranded-identity-false-positive

- **Signal:** `loki:stranded-identity-false-positive` — the `StrandedIdentity` canary
  (`stranded_fallback_online_onboarded`) firing on real production/store installs.
- **Query:** `{service_name="cards-client", deployment_environment="prod"} |= "stranded_fallback_online_onboarded"`, 30d.
- **Disposition:** todo: AUTH-32 [P2]

## What the canary is

`StrandedIdentityDetector` (`libraries/identity/impl/.../auth/StrandedIdentityDetector.kt`) is an
observe-only breadcrumb: on `onForeground`/`onConnectivityRegained`, if the profile is still
`Profile.Fallback` while onboarded+online+not-deliberately-signed-out, it warns. Its own KDoc: "Post-
[GuestSessionHealer] this should read **zero** — if it ever fires, the heal path isn't recovering
someone it should."

## What's actually happening

30d sweep found **17 fires**, but ~11 of those are a single 2026-09-01 burst on `release_channel=local`
+ `is_sideloaded=true` + `is_emulator=true` (dev/QA rapid-restart testing, build_number=1, `genuine_install=false`) —
out of scope, same class as the ENG-68/CARDS-C4 dev noise. The remaining **6 are real**: two on iOS
beta install `91628081` (build 968), one Android store install `beaf7187` (1026), two Android store
installs `bdf966c3`/`c3ba45f6` (1135, low-device-class), and one today, install `c2d9d189` (1135,
high-device-class) — spread across three separate production releases over a month, not a one-off.

Today's hit (session `29e58c53`) was traced in full: cold launch (`app.launched`, `previous_exit=clean`)
→ `AuthUnready: FinishingSetup` ×6 within ~20ms → the canary fires at t+1073ms → `game.started`
(mode=bots) at t+5701ms → an unrelated `Dropped 1 orphan equipment row` warning. The user reached a
playable game 5.6s later; nothing else in the session suggests a stuck identity.

## Root cause (proven from the dispatcher, not inferred)

`AppEventDispatcher.kt:76-82` fires **both** `AppEvent.ColdBoot` and `AppEvent.OnForeground(isColdBoot=true)`
on the same cold launch — `GrafanaAppEvents.kt:91`'s own comment confirms ordering ("`OnForeground`
with `isColdBoot` is guaranteed to run after every `ColdBoot`"), but ordering of dispatch is not the
same as the async heal *coroutine* (launched from `ColdBoot`, via `appScope.launch`) having finished
by the time `OnForeground`'s handler runs.

`StrandedIdentityDetector`'s two siblings in the same package both guard against exactly this:
- `GuestSessionHealer.onForeground` (`GuestSessionHealer.kt:79`): `if (event.isColdBoot) return`
- `AuthReResolver.onForeground` (`AuthReResolver.kt:46`): `if (event.isColdBoot) return`

`StrandedIdentityDetector.onForeground` has no such guard — it runs its check on the cold-boot echo
of foreground too, so it can sample `profileRepository.current()` while the `ColdBoot`-triggered heal
is still in flight and see the pre-heal `Profile.Fallback`, firing a false positive during the
completely normal mint-in-progress window.

**Proven:** the dispatcher fires the duplicate event; the two siblings guard against it; the detector
doesn't. **Inferred, not proven:** that this race is the *complete* explanation for every one of the
6 real hits — today's session is consistent with it (fired near launch, resolved by the next event 5.6s
later) but Loki has no visibility into whether `Profile.Fallback` actually cleared afterward on the
other 5 installs, since none has a second hit to confirm recovery.

## Why this matters enough to file, not just note

The canary's entire value is reading zero in a healthy population — the same argument ENG-44 made for
`AuthUnready`-at-error and ENG-34 for offline noise: false positives on a "should never fire" signal
erode the ability to trust it when it fires for a real reason. It has now fired on production across
three separate release builds over a month without ever being looked at.

## Not filed as user-facing

No harm observed: today's traced session reached gameplay 5.6s later, and bots mode may not require a
fully-resolved server profile in the first place, which is itself an open question this fix should
settle.
