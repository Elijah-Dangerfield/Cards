# Observability case: `stranded_fallback_online_onboarded` firing on genuine prod installs

- **Signal:** Grafana signal, slug `sweep:stranded-fallback-canary-2026-09-11`. Not a Sentry issue — a client WARN breadcrumb, `StrandedIdentityDetector.kt`, tag `StrandedIdentity`.
- **Source:** Loki `{service_name="cards-client", deployment_environment="prod"} |= "stranded_fallback_online_onboarded"`, 30d window.
- **Reported:** found during the 2026-09-11 nightly observability sweep (last run was 2026-09-02; this signal is not in the ledger before now).

## What the canary is

`StrandedIdentityDetector` (`libraries/identity/impl/.../auth/StrandedIdentityDetector.kt`) is deliberately observe-only. On `onForeground` / `onConnectivityRegained`, if the cached profile is still `Profile.Fallback` while the device is onboarded and online (and auth isn't already in a deliberate unauthenticated state), it logs `stranded_fallback_online_onboarded` at WARN. Its own KDoc: "Post-`GuestSessionHealer` this should read **zero** — if it ever fires, the heal path isn't recovering someone it should."

## What the data shows

17 hits in the last 30 days. 14 are `is_emulator=true` / `is_sideloaded=true` / `release_channel=local`, build_number=1 — dev/CI noise, not real users, consistent with prior runs never flagging this. **3 are `genuine_install=true`, `release_channel=store`, `installer_package=play_store`, build 1135 (current prod)** — all in the last 8 days, none previously ledgered:

| install_id | when (UTC) | device | session |
|---|---|---|---|
| `c2d9d189…` | 2026-09-08 | Android, `device_class=high`, real Pixel-class | `29e58c53…` |
| `bdf966c3…` | 2026-09-03 | Android, `device_class=low`, os 11 | `dbfa46b4…` |
| `c3ba45f6…` | 2026-09-03 | Android, `device_class=low`, os 11 | `7618c0ca…` |

### Case 1 — `c2d9d189`, cold boot (proven mechanism)

Full session trace, 2026-09-08:
```
app.foregrounded (cold_start=true)
accessToken: no session — request will go unauthed         (+3ms)
app.launched (previous_exit=clean)                          (+4ms)
app.previous_run (outcome=unknown)                           (+5ms)
stranded_fallback_online_onboarded ...Profile.Fallback...    (+78ms)
auth unready: FinishingSetup  x6                              (+1.14s)
game.started (mode=bots)                                     (+5.7s)
```
The canary fires **78ms after cold-boot foreground** — before the async heal (`GuestSessionHealer.heal("coldBoot")`, which mints over the network) could plausibly have completed. The user was never stuck: a bots game started cleanly 5.6s later, which requires a resolved identity.

**Root cause (proven from the code, not just the trace):** `GuestSessionHealer.onForeground` explicitly skips cold-boot foreground because `onColdBoot` already runs the heal for that trigger (`if (event.isColdBoot) return`). `StrandedIdentityDetector.onForeground` has **no such guard** — it runs its check on every foreground, cold-boot included, so on a fresh cold boot it can sample `profileRepository.current()` while the just-kicked-off `onColdBoot` heal is still in flight and see the pre-heal `Profile.Fallback`. That's a **false positive on the canary itself**, not evidence the heal failed.

### Cases 2 & 3 — `bdf966c3` / `c3ba45f6`, post-onboarding (same-second, unresolved cause)

Both installs ran an **identical scripted sequence** within the same second: several background/foreground cycles through onboarding, `onboarding.completed`, one more background→foreground (14.5s later), then the canary fires 172ms after that foreground — followed immediately by a `PATCH /v1/me` `409 display_name_taken` and a `GET /v1/me/active-rooms` `504 Gateway Timeout`. The two installs' event timestamps track each other to within a second throughout, including hitting the *same* display-name conflict.

**Not fully resolved — flagging both readings rather than picking one:**
- This could be two real low-end Android devices coincidentally onboarding at the same moment, in which case the canary firing 62s after `onboarding.completed` (not at cold boot) is a **second, different race** — the profile cache reading `Fallback` again on a later foreground after a real mint, not the cold-boot one found in Case 1.
- Or — the identical timing and the identical display-name collision are strong tells for **automated crawler traffic** (Play's pre-launch report / a robo-test harness), which can present as `genuine_install=true` / `installer_package=play_store`. The wiki's "Known-benign client signals" doesn't list this pattern yet; I'm not adding it there on one sweep's evidence.

Didn't chase this further — the mechanism in Case 1 is enough on its own to file, and case 2/3 either reinforces it (a second race) or is noise (crawler). Left as an open question in the acceptance criteria below rather than guessed at.

## Disposition

**Actionable → todo AUTH-32 [P2].** Not user-impacting today (both proven sessions completed normally), but this is a designed-to-be-zero canary now firing on the current prod build, which quietly erodes exactly the signal that would catch a *real* future stranding regression. Signal-hygiene class, same shape as ENG-34 (offline noise) and ENG-35 (banned-403 noise).

## What's proven vs inferred

- **Proven:** the canary fired on 3 genuine-flagged prod installs in the last 8 days, zero previously ledgered. The exact cold-boot race mechanism in Case 1, read directly off `GuestSessionHealer.onForeground`'s existing cold-boot guard vs `StrandedIdentityDetector.onForeground`'s missing one.
- **Inferred:** that Case 1's mechanism (detector races the still-in-flight cold-boot heal) is the whole story. Cases 2/3 are not explained by the same mechanism (foreground was 62s after a completed onboarding, not a cold boot) — flagged as unresolved, not force-fit to the Case 1 theory.
- **Ruled out:** the user being stuck. Case 1 played a game 5.6s later; cases 2/3's trace continues past the warning with ordinary requests (no crash, no stuck-on-fallback screen visible in the client log).
