# Recovery and Orphaned Accounts

How orphan rows from abandoned anon accounts get cleaned up — and what we're deliberately *not* doing in V1 around account recovery.

**V1 posture (locked 2026-05-29):** install_id-only cleanup. **No recovery on reinstall.** Anon users who uninstall lose their account. Claim (Apple / Google / email signin) is the only path to make an account durable. Two upgrade paths (B and C) preserved at the bottom of this doc for when revival becomes a real complaint vector.

**Companion to:** [decisions.md](./decisions.md) (the locked architectural calls), [todo.md](./todo.md) (engineering items implementing this), [backlog.md](./backlog.md) (the parked Option B / C upgrade paths), [product-spec.md §6.1](./product/product-spec.md#61-anonymous-by-default).

---

## V1: just two IDs

| ID | What it is | Where it lives | Used for |
|---|---|---|---|
| **user_id** | Supabase auth UUID | Server | Account identity |
| **install_id** | UUID per app installation | App-local (DataStore / file) | L1 server-side orphan cleanup, telemetry tagging, crash correlation |

That's it. No recovery_id, no platform keychain integration.

**Behavioral consequences accepted:**

- **Reinstall = fresh start** for anon users. No revival prompt, no Welcome-back screen. All progress (chips, level, achievements, cosmetics) gone.
- **Cross-device = fresh start.** Opening the app on a second device (iPhone + iPad with the same Apple ID) creates a separate anon account; the two don't share progress.
- **Starter farm exploit stays open.** Uninstall + reinstall mints a fresh 10K starter; loop is unprevented at the wallet layer. Disincentive is purely *"the farmer loses their old account's progress along with the gain."*
- **Spec §6.1 language amended.** The "best-effort revival on reinstall" language gets dropped; documented now as "claim is the only durable identity path in V1."

---

## Boot flow (V1)

```
Splash launches
  │
  ▼
Live Supabase session?
  ├─ Yes → done, normal app flow
  │
  └─ No → fresh anon signin
          ├─ Generate install_id (if app-local storage doesn't have one)
          └─ Send install_id with first /me
```

That's the whole boot. No keychain read, no recovery lookup, no Welcome-back screen.

---

## L1 — server-side orphan cleanup on /me

**Triggered by:** every authenticated `/v1/me` request, regardless of caller.

**Catches:** the "user signed out and signed into a different account on the same install, without uninstalling" case. The previous anon's row still has `install_id_X`; the new owner's row now also has `install_id_X`; L1 finds the mismatch and cleans up the orphan.

**Does NOT catch:**
- Reinstall orphans (the previous install_id is gone with the app data; no new row references it)
- Cross-device orphans (different install_ids)

Both are accepted leaks per the V1 trade-off. Storage cost of leaked rows is ~10KB each; in V1 volumes, negligible.

**Mechanism:** background task fired by the /me handler — does not block the response.

**Step 1 — SQL pre-filter** to find candidates:

```sql
SELECT p.id
FROM profiles p
LEFT JOIN wallet_events we
  ON we.user_id = p.id AND we.source LIKE 'iap.%'
WHERE p.install_id = :current_install_id
  AND p.id != :current_user_id
  AND p.is_anonymous = TRUE
  AND we.id IS NULL          -- zero IAP purchases (join filter)
```

The join filter is the cheap gate — eliminates anyone who's spent real money.

**Step 2 — Kotlin per-candidate verification.** For each candidate row, re-check in code:

- `is_anonymous` is still true (defense against TOCTOU)
- `level <= 1` (the low-XP threshold — see open questions)
- Zero unlocked achievements
- No wallet events in the last N days
- No active row in `room_sessions`

**Step 3 — Delete the verified candidates.** Same path as user-initiated `DELETE /v1/me`: `SupabaseAdminClient.deleteUser(id)` + `ProfileRepository.delete(id)`. The FK + `ON DELETE CASCADE` from V11 cleans up dependent rows.

**Why Kotlin verification rather than pushing everything into SQL:** the safety conditions will evolve as we learn what we should and shouldn't delete. Keeping the verification in Kotlin keeps the logic readable, testable, and easy to extend without rewriting SQL every time.

**Why background, not synchronous on the /me response:** cleanup latency doesn't matter to the user; the /me response ships in tens of milliseconds. Cleanup can take seconds and still be cheaper than the next /me's chance to catch the same orphan.

---

## Loss-disclosure UX

Because anon = ephemeral by design in V1, we need to *tell* users that. The existing claim card on the profile screen says *"Sign in to keep what you've built"* — good baseline, but probably not enough by itself given the consequences.

**Likely additional surfaces (TBD):**

- **Shop pre-purchase:** before an anon user completes a real-money chip-pack purchase, a one-time confirmation: *"This purchase is tied to this install. Sign in to keep it permanent if you reinstall."* Two buttons: `[Sign in]` / `[Buy as guest]`.
- **Stats page:** a small persistent banner *"Sign in to back up your progress"* (only shown to anon users with `level > N` or `chips > M` — once you've accumulated something, the warning becomes relevant).
- **Settings → Account section:** explicit copy when anon — *"You're playing as a guest. Uninstalling will end this account. Sign in to make it permanent."*

These aren't proactive *claim prompts* in the sense the 2026-05-20 decision rejected (those were begging for conversion at unrelated moments). These are **disclosure of consequences at the moment they matter** — same shape as warning a user before a destructive action. Worth confirming with product before they ship.

---

## Anti-farm posture

Without recovery_id, the starter-grant gate has no signal to dedup against. Acceptance:

- Uninstall + reinstall = fresh 10K starter, infinitely repeatable
- Disincentive is intrinsic: the farmer loses their old account (all progress, all achievements, all cosmetics they earned or paid for) every time they reset for chips
- The exchange rate ("hours of progress lost" vs "10K chips gained") is unfavorable enough that this isn't expected to be a significant attack vector pre-launch

If it does become one — chip-pack secondary market, MP league exploit, etc. — the upgrade path is Option B below, ~3 days of work.

---

## Upgrade paths preserved for V1.x / V2

When revival becomes a real complaint or the anti-farm posture needs strengthening, two paths are pre-designed:

### Option B — install_id + IDFV / ANDROID_ID

- iOS: `UIDevice.current.identifierForVendor` survives same-app reinstall on the same device.
- Android: `Settings.Secure.ANDROID_ID` is per-app-per-signing-key-per-device since API 26; survives same-app reinstall.
- ~3 days of engineering. One platform read per ID, no KMP keychain work, no Google Play Services dependency.

**What it adds:**
- Same-device reinstall revival ("you uninstalled and came back? here's your account")
- Casual starter-farm exploit closed (single-device reinstall doesn't mint chips)

**What it doesn't get you:**
- Cross-device account access (the IDs are per-device, not per-account)
- Revival after a factory reset / new phone (IDs change)

### Option C — install_id + recovery_id via iCloud Keychain / Block Store

- iOS: Keychain Services with `kSecAttrSynchronizable=true` so the UUID rides iCloud.
- Android: Block Store via Google Play Services so the UUID rides the Google account.
- ~1–2 weeks of engineering. KMP expect/actual, platform-specific testing.

**What it adds (on top of Option B):**
- Cross-device account access (same Apple/Google account on a second device finds the same recovery_id)
- New-phone-restored-from-iCloud → account follows
- Strongest anti-farm posture (recovery_id survives device migration; only a different platform account gets a fresh starter)

**Trade-off vs B:** real engineering work + platform-specific dependencies, in exchange for the cohort that switches devices or restores from backup.

**Detailed Option C design** (boot decision tree with Welcome-back screen, three options Continue/Sign in/Start fresh, skeleton-loader UX, recovery endpoint shape, anti-farm semantics across all scenarios) was sketched out in detail before the V1 scope-cut — preserved in git history at `13b84b37` for when Option C is on the table again.

---

## Open questions (V1)

1. **L1 "low XP" threshold.** Tentatively `level <= 1`. Revisit once we have real install data on how anon orphans typically look at deletion time.
2. **L1 "recent activity" window.** Conservatively wide (90 days no wallet events / room sessions / achievement grants) so a returning user is never deleted accidentally.
3. **Loss-disclosure UX exact placement.** Shop pre-purchase confirmation? Stats banner? Settings copy? All three? Designer + product call.
4. **Loss-disclosure threshold for the stats banner.** Below what level/chip count is the warning more noise than signal? Pick a floor once we have install data.
