# 2026-10-08 — "Could not find your session", then a welcome screen offering 14,020 chips

**Source:** owner, from his own iOS device, after updating to 0.5.0.
**Status:** fully traced. The chips were never inflated. One real display bug (AUTH-33), one real
UX hole (AUTH-34). The forced sign-out itself is working as designed.

## The short version

Nothing was created and nothing was lost. The owner's real account still holds its 14,020 chips.
The new guest account holds exactly 10,000, as it should. The welcome screen showed the *old*
account's balance while introducing the *new* one.

## Timeline, all on install `f6d7a420-38d1-4e92-8efc-b1700065fb9e` (iOS 27.0.1, App Store, 0.5.0+1272)

| Time (UTC) | What |
| --- | --- |
| 2026-09-22 18:42 | Account `e8226cac…` "LuckyJack66" created on iOS 0.1.0, the day Apple approved it |
| 2026-09-24 00:58 | Its wallet reaches **14,020** and stops changing |
| 2026-10-08 17:59:42 | `Session unrecoverable: cached profile e8226cac… (isAnonymous=false) has no session and retry could not revive it — routing to recovery instead of minting over the account` |
| 2026-10-08 17:59:42 | `Session rejected by auth server — tearing down (wasAnonymous=false)` |
| 2026-10-08 17:59:54 | `onboarding.auth_selected` `method=guest` `returning=false` |
| 2026-10-08 17:59:56 | `starter_grant` **+10,000** → new account `fb9c8ac6…` "QuietEight49" |

Both profiles carry the same `install_id`, which is what ties the two accounts to one device and
one person.

## Why the session vanished — and why that part is not a bug

The iOS Keychain can lose its copy of the session across an app update while every other app file
survives. This is **documented in our own code** because it happened to the owner once before, on
a TestFlight upgrade: see the KDoc on `SessionMirrorStore` and `SecureSessionManager`.

`SessionMirrorStore` was built as the fix — a file-backed copy that survives upgrades. It is
deliberately **anonymous-only**, and `SecureSessionManager.persist` clears it the moment an account
is claimed:

```kotlin
if (session.isAnonymous()) mirror.write(key, encoded) else mirror.clear()
```

The reasoning (decisions.md 2026-07-11) is sound: the mirror stores a refresh token unencrypted in
the app sandbox, which is acceptable for an anonymous account whose only credential *is* device
possession, and not acceptable for a claimed account that has a real credential to sign back in
with.

`e8226cac…` was claimed (`isAnonymous=false`). So there was no mirror, by design, and the designed
recovery is "sign in again." `GuestSessionHealer` then did exactly the right thing — AUTH-19's
whole purpose — by **refusing to mint a guest over the real account** and routing to recovery.

So the chain worked. The hole is what happened next.

## Bug 1 — the welcome screen showed the wrong number (AUTH-33)

`GetHomeScreenNotification`:

```kotlin
starterGrant != null -> Exact(starterGrant)
chipBalance == null  -> return null
// A fresh account's balance equals its grant before they've played.
chipBalance > 0      -> Exact(chipBalance)
```

That comment is the bug. The assumption holds for a genuinely fresh install and breaks for a new
account created on a device that still has a previous account's balance cached. `starterGrant` was
null, so it fell through to `chipBalance`, which was still 14,020 from LuckyJack66.

Verified against the prod ledger: `fb9c8ac6…` has exactly one wallet event, `starter_grant +10000`.
Every `starter_grant` in the last four days is exactly one per user at exactly 10,000, and every
wallet's `balance` equals the sum of its events. There is no drift and no double grant.

## Bug 2 — nothing said the old account was still there (AUTH-34)

The healer logged "routing to recovery". Twelve seconds later the user picked guest in ordinary
onboarding, tagged `returning=false`. Whatever the recovery screen offered, the path to "create a
brand-new account" was walkable without ever being told that LuckyJack66 and its 14,020 chips were
one Apple sign-in away.

That is how a correctly-refused mint still ended in a stranded account. The account was protected;
the user was not told it had been protected.

## Scope

One occurrence, iOS only, in 14 days — the three matching log lines are three statements from this
single incident.

**But the exposed population has not arrived yet.** Over 7 days, iOS launches split **57 on 0.1.0
against 8 on 0.5.0**. Roughly 88% of iOS users have not taken the update that triggers this, and
every claimed iOS account that does take it is a candidate.

## Recovery for anyone who hits it

Sign back in with the original provider. The account, its chips, XP and stats are all intact
server-side; only the device's copy of the session was lost.
