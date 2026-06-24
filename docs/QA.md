# QA — pre-launch verification

Real-device checklist run by a human before each release. Organised by feature. Each test sets a known device state, lists the steps, and names the expected outcome.

**Per release:** copy the relevant sections into a GitHub tracking issue, tick scenarios as they pass, file bugs against the test ID.

**Notation**
- `🚨` release-blocker (must pass before shipping) · `⚠️` important · `ℹ️` nice-to-verify
- `📱` test on iOS and Android · `🍎` iOS only · `🤖` Android only
- `ONB-3` = stable test ID. Quote it in bugs and PRs.

**Terms**
- **Fresh install** — uninstall the app, then reinstall. Wipes local data + the device-bound install id.
- **Returning user** — a signed-in session already exists on the device from a prior launch.
- **Cold boot** — force-quit + re-launch (distinct from background → foreground).

---

## Onboarding

**Finish line for every test:** the user lands on Home. On first-account paths the welcome grant dialog shows with **10,000 chips**; on returning-user / existing-account paths it does **not** show.

---

### `ONB-1` 🚨 📱 Fresh install, online, "Continue as guest"

**State:** fresh install, online, no prior accounts on the device.

1. Open app → "Get started" → on PickIdentity tap "Continue as guest."
2. Complete any remaining onboarding screens (terms, name, avatar).

**Expected:** Lands on Home. Welcome grant dialog shows 10,000 chips + welcome copy. Dismissing reveals Home with the 10K balance already displayed — no 0 → 10K flicker, no spinner.

---

### `ONB-2` 🚨 📱 Fresh install, online, email sign-up (new email)

**State:** fresh install, online. Use an email never registered with the app.

1. Open app → "Get started" → "Sign up with email."
2. Enter the new email + a valid password; submit.
3. Complete remaining onboarding screens.

**Expected:** Lands on Home. Welcome grant dialog shows 10,000 chips. A verification email arrives in the inbox (link tap is covered by `ONB-13`).

---

### `ONB-3` 🚨 📱 Apple Sign-In, returning user

**State:** fresh install, online. Use an Apple ID that already has an account on this app (from a prior install or device).

1. Open app → "Get started" → "Continue with Apple."
2. Complete Apple's sheet.

**Expected:** Lands on Home. **No** welcome grant dialog. Profile shows the prior account's name + avatar. Chip balance reflects the prior wallet (not 10K starter). Onboarding name / avatar screens are skipped.

---

### `ONB-4` 🚨 📱 Apple Sign-In, new user

**State:** fresh install, online. Use an Apple ID that has **never** signed into the app.

1. Same as `ONB-3`.

**Expected:** Currently lands on Home **without** the welcome grant dialog. *(Known issue — todo `AUTH-3`: new OAuth sign-ups should route through PickIdentity / grant. Confirm current behaviour matches and flag if it changes.)*

---

### `ONB-5` 🚨 📱 Google Sign-In

Two variants, both must pass:

- **Variant A — returning user.** A Google account with a prior app account.
- **Variant B — new user.** A Google account that has never signed in.

**State:** fresh install, online.

1. Open app → "Get started" → "Continue with Google."
2. Complete Google's sheet.

**Expected:**
- Variant A — same as `ONB-3` (no grant, prior profile loads).
- Variant B — same as `ONB-4` (lands on Home without grant — same `AUTH-3` issue).

---

### `ONB-6` 🚨 📱 Fresh install, **offline**, "Continue as guest"

**State:** fresh install. Airplane mode **on** before launching.

1. Open app → "Get started" → "Continue as guest."
2. Complete remaining onboarding screens.

**Expected:** Lands on Home. Welcome grant dialog shows 10K chips. The "account-creation pending" banner (`AccountSetupBanner`) is visible at the top of Home. No crashes, no error spinners.

**Then bring the device online:**

3. Turn airplane mode off. Wait up to ~5s.

**Expected:** Pending banner disappears once `GuestAccountCreator` succeeds. Chip balance stays at 10K — no duplicate grant, no flicker. Wallet sync completes silently.

---

### `ONB-7` ⚠️ 📱 Fresh install, offline, attempt email sign-up

**State:** fresh install, airplane mode on.

1. Open app → "Get started" → "Sign up with email."
2. Enter any email + password; submit.

**Expected:** A clear no-connection error appears — not a generic server error, not a hang. The user can back out and choose "Continue as guest" instead.

---

### `ONB-8` ⚠️ 📱 OAuth cancellation mid-flow

**State:** fresh install or signed-out, online.

1. PickIdentity → "Continue with Apple" (or Google).
2. On the platform sheet, tap Cancel.

**Expected:** Returns to PickIdentity with no half-state. Re-tapping the OAuth button works cleanly. No crash, no spinner stuck on.

---

### `ONB-9` 🚨 📱 Returning user, online cold boot

**State:** prior session signed in (real account or guest). Online.

1. Force-quit the app.
2. Re-open from the home screen.

**Expected:** Skips onboarding entirely. Lands on Home with the user's actual profile + chip balance. No grant dialog. No spinner stall longer than ~1 second.

---

### `ONB-10` 🚨 📱 Returning user, **offline** cold boot

**State:** prior session signed in. Airplane mode on. Force-quit, then re-launch.

1. Open the app from the home screen.

**Expected:** Skips onboarding. Lands on Home with cached profile + cached chip balance. "Connection issues" banner appears at the top. No "account needed" dialog when navigating Home surfaces. Tapping anything network-required (multiplayer, real-money purchase) reads as a *connection* problem — not as account-less. *(Verifies the path called out by `AUTH-6` in todo.md.)*

---

### `ONB-11` ⚠️ 📱 Sign out → continue as guest

**State:** signed into a real account, online.

1. Settings → Sign out → confirm.
2. On returning to PickIdentity, tap "Continue as guest."
3. Complete remaining onboarding screens.

**Expected:** Onboarding shows the "new here" banner (or whatever guest-re-entry copy applies). A new guest identity is created. Lands on Home. *(Per `AUTH-6`, the "new here" banner currently does not show — confirm actual behaviour and flag if it changes.)*

---

### `ONB-12` ⚠️ 📱 Email sign-up: existing account

**State:** fresh install or signed-out, online. Use an email already registered.

1. PickIdentity → "Sign up with email."
2. Enter the existing email + any password; submit.

**Expected:** Inline error explains that the account exists. A "Sign in instead" CTA is visible — tapping it pre-fills the email on the sign-in screen.

---

### `ONB-13` ⚠️ 📱 Email sign-up: verify-email link tap

**State:** completed `ONB-2`. Verification email has arrived.

1. Tap the verification link in the email (on the same device).

**Expected:** Opens the app (or web) into the verified state. If on-device, the app's "Verify your email" banner clears; account status flips to verified in Profile.

---

### `ONB-14` ℹ️ 📱 Email sign-up: weak password

**State:** fresh install or signed-out, online.

1. PickIdentity → "Sign up with email."
2. Enter a valid email + a password that fails the rules (e.g. `"abc"`); submit.

**Expected:** Inline error explains the password rule. Form remains editable; fixing the password and resubmitting succeeds.

---

### `ONB-15` ℹ️ 📱 Onboarding interrupted by backgrounding

**State:** fresh install, online.

1. Start onboarding. On any screen with a form (PickIdentity, email entry, name entry), background the app.
2. Wait ~30 seconds.
3. Re-foreground.

**Expected:** Resumes on the same screen with state intact (form fields, selection). No crash, no jump back to the welcome screen.

---

### `ONB-16` ℹ️ 📱 Slow network during welcome grant

**State:** fresh install, online but throttled (use the platform's developer network-link conditioner if available; skip if no throttle is possible).

1. Open app → "Continue as guest."
2. Walk through onboarding quickly; arrive at Home with the welcome dialog still showing.

**Expected:** Dialog renders even if the wallet fetch hasn't completed — chip count shows a placeholder (em-dash or similar). Once the wallet response lands, the chip count updates in place to 10K. No crash if the dialog is dismissed before the count lands.

---

## Social gating

Friends/social is descoped to V2 behind the `social.enabled` app-config flag (default off). These confirm the surfaces stay hidden in the shipped default (SOC-2).

---

### `SOC-1` ⚠️ 📱 Social surfaces hidden by default

**State:** any signed-in session, online, `social.enabled` at its default (off). Claim an account so the friend inbox would otherwise be eligible.

1. Open Home and scroll the full screen.
2. Open the Profile tab and scroll the full screen.
3. Join or start a multiplayer table and tap a human opponent's avatar to open their player card.

**Expected:** Home shows no "Friends" strip and no "Recently played with" strip (the achievements strip still shows). Profile shows no friend-requests inbox. The opponent player card shows no "Add friend" section. Nothing renders in a disabled/greyed state — the surfaces are simply absent.
