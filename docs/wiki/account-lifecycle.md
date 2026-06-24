# Account lifecycle — deletion + orphan handling

This doc covers how accounts end (deletion, orphan sweep) and the trade-offs we accepted to keep the model simple. For how accounts begin (anonymous → claim via Apple / Google / email), see `wiki/architecture.md`'s client / server split.

## Deletion: hard-delete, not soft

`DELETE /v1/me` is a **hard-delete**. The row goes away; no recovery window, no tombstone. The V11 FK with `ON DELETE CASCADE` on `auth.users` means deleting a user wipes profile + wallet + ledger + inventory + equipment + messages atomically.

**Why not soft-delete + recovery window:**

- No support inbox yet. The "I deleted by mistake, can you restore?" email has no recipient, so a 30-day window adds no user-visible value in V1.
- No EU users yet, so GDPR's right-to-erasure isn't applying pressure.
- The migration path to soft-delete is cheap when it's needed: add `deleted_at TIMESTAMPTZ` to `profiles`, flip `DELETE /v1/me` to `UPDATE profiles SET deleted_at = now()`, add a daily sweep endpoint that hard-deletes rows older than 30 days (mirrors the existing `sweep-anon` / `sweep-rooms` workflow pattern).

**Triggers to revisit:**

- A support inbox + a real restore-my-account request lands.
- The first EU user signs up, or App Store review flags data retention.
- A second team member starts handling account ops and needs the recovery safety net.

## Orphan sweep (abandoned anonymous accounts)

Anonymous accounts that get abandoned (user reinstalled, switched devices, or just walked away) are deleted only when we're very sure they're dead. The bar is deliberately high — orphan rows are cheap; deleting someone's progress by accident is indefensible.

**Hard guards (all must hold):** anonymous + never claimed + **zero real-money purchases** + **at or below level 1** (any meaningful XP → preserved, never deleted).

**Two trigger paths:**

1. **Opportunistic** (`DefaultOrphanInstallSweep`, fires on `/v1/me`) — when a device / install id is now bound to a *different* active anon account. The "one in use, one unreachable" case. **Shipped.**
2. **Scheduled** (`DefaultOrphanAnonymousSweep`, exposed at `POST /v1/admin/sweep-anonymous-users`) — ≥ 1 year fully inactive. **Built but not auto-triggered.** Lives in `post-launch.md` as the "wire automatic invocation" item.

A real-money purchase (`wallet_events.delta > 0` with reason `chip_pack`) is the absolute floor — that gate is enforced by a `LEFT JOIN` in the sweep's pre-filter, so it's structurally impossible to delete a paying account.

## Anti-farming: install_id only in V1

V1 ships with `install_id`-only cleanup. No `recovery_id`, no platform-keychain integration (iCloud Keychain / Block Store), no Welcome-back screen, no revival on reinstall. Claim (Apple / Google / email) is the only durable identity path.

**Consequences we accepted:**

- Reinstall = fresh anon account; all progress lost for non-claimed users.
- Cross-device = fresh anon (iPhone + iPad with the same Apple ID don't share progress until either is claimed).
- The starter-grant farming loop stays open. The disincentive is intrinsic — a farmer loses their old account every loop, which contains all progress and any earned / paid cosmetics.

**Why this is acceptable:**

- Pre-launch, zero users. Revival isn't load-bearing for any current cohort.
- Loss-disclosure UX tells anon users "sign in to keep this" at the moments it matters (shop pre-purchase, stats banner, settings account section).
- Two upgrade paths are pre-designed in `../backlog.md` ("Anti-farming on the starter chip grant"):
  - **Option B** (~3 days): `install_id + identifierForVendor` / `ANDROID_ID`. Adds same-device-reinstall revival + casual anti-farm gate. No KMP keychain work.
  - **Option C** (~1-2 weeks): `install_id + recovery_id` via iCloud Keychain / Block Store. Adds cross-device revival, new-phone-from-iCloud revival, strongest anti-farm gate.

**Revisit trigger:** watch anon-revival complaint volume in support and orphan-account count in OTel metrics. If either grows, Option B is the cheap first step.

## Key files

- Server delete: `DELETE /v1/me` route handler, `WalletRepository.deleteAllForUser`, `ProfileRepository.delete`.
- Opportunistic sweep: `apps/server/.../data/DefaultOrphanInstallSweep.kt`.
- Scheduled sweep: `apps/server/.../data/DefaultOrphanAnonymousSweep.kt`, `AdminRoutes.kt` (the route).
- Cascade FK: V11 migration.
