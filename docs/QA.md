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

**Expected:** Routes through onboarding — PickIdentity (name + avatar) then the starter-grant reveal — and lands on Home with the welcome grant. The native Apple identity links to the fresh guest, so chips / XP carry through (AUTH-3).

---

### `ONB-5` 🚨 📱 Google Sign-In

Two variants, both must pass:

- **Variant A — returning user.** A Google account with a prior app account.
- **Variant B — new user.** A Google account that has never signed in.

**State:** fresh install, online.

1. Open app → "Get started" → "Continue with Google."
2. Complete Google's sheet.

**Expected:**
- Variant A — same as `ONB-3` (no grant, prior profile loads, onboarding skipped).
- Variant B — new account: routes through onboarding (PickIdentity then the starter-grant reveal) and lands on Home with the welcome grant, instead of landing cold on Home (AUTH-3).

---

### `ONB-6` 🚨 📱 Fresh install, **offline**, "Continue as guest"

**State:** fresh install. Airplane mode **on** before launching.

1. Open app → "Get started" → "Continue as guest."
2. Complete remaining onboarding screens.

**Expected:** Lands on Home. Welcome grant dialog shows 10K chips. The first time the pending state is hit, a one-time "Finishing your account" explainer dialog (`AccountSetupExplainerDialog`, AUTH-1) appears reassuring the user their play is saved and that multiplayer + purchases are paused; dismissing it ("Got it") reveals the standing thin "account-creation pending" banner (`AccountSetupBanner`) at the top of Home. No crashes, no error spinners.

- The explainer dialog shows **once per device**: dismiss it, force-quit, relaunch offline → only the thin banner shows, not the dialog again.
- While still offline, open Profile and Settings: each shows an in-page "Account setup unfinished" banner with a **Retry** button (`AccountSetupRetryBanner`, AUTH-1) above the "Save your progress" nudge. Tapping Retry while offline keeps it pending (shows "Retrying…"), not an error.

**Then bring the device online:**

3. Turn airplane mode off. Wait up to ~5s.

**Expected:** Pending banner disappears once `GuestAccountCreator` succeeds — on Home and on the Profile/Settings in-page banners. Chip balance stays at 10K — no duplicate grant, no flicker. Wallet sync completes silently.

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

**Expected:** Skips onboarding. Lands on Home with cached profile + cached chip balance. "Connection issues" banner appears at the top. No "account needed" dialog when navigating Home surfaces. Creating / joining a multiplayer room surfaces a *connection* error ("Couldn't reach the server"), not the account-less "Sign in first to create a room" copy. Real-money purchase still hard-gates. *(AUTH-6.)*

---

### `ONB-11` ⚠️ 📱 Sign out → continue as guest

**State:** signed into a real account, online.

1. Settings → Sign out → confirm.
2. On returning to PickIdentity, tap "Continue as guest."
3. Complete remaining onboarding screens.

**Expected:** A new guest identity is created and lands on Home. The Home "new here?" tutorial banner shows again above the header — the previous account's dismissal does not carry across the sign-out (its dismissed flag is reset on the identity change). *(AUTH-6.)*

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

## Offline gating

Every network-required surface follows one rule off a cached / fallback identity (no confirmed server session): **reads render cached content**, **server-mutating surfaces soft-gate** (visible, affordances stay tappable but failures surface as a connection error rather than success), and **money + multiplayer hard-gate**. The matrix below walks each surface once so a single offline pass confirms the whole app honors it (AUTH-5).

---

### `AUTH-5` ⚠️ 📱 Offline gating matrix across network-required surfaces

**State:** a returning session on a cached / fallback profile, offline. Reach this via `ONB-10` (returning user, airplane mode, cold boot) or `ONB-6` (fresh guest, offline, account-creation still pending). Stay offline for every step.

Walk each surface and confirm the column it lands in:

1. **Home** — reads cached. Profile header, chip balance, level all render from cache. The "Connection issues" banner shows; no "account needed" dialog fires from navigating Home.
2. **Shop** — reads cached (catalog grid renders the last-fetched offers). Chip-funded redeems still work (local spend + Pending row); tapping a **real-money** chip pack hard-gates with a connection / not-signed-in error snackbar, never a silent success.
3. **Profile** — reads cached (equipped flair, stats, level all from cache).
4. **Edit Profile** — soft-gates. The avatar picker falls back to the hardcoded starter list when the pack fetch never landed (`loadError` shown). A name change surfaces a connection error inline; an avatar-only save navigates back optimistically then surfaces a connection-error snackbar — never a silent drop.
5. **Claim account** — hard-gates. Every link / sign-up path (email + OAuth) surfaces a clear no-connection error, not a hang or generic server error.
6. **Inventory (My Items)** — reads cached; equip / unequip toggles apply optimistically (Pending) and reconcile on reconnect. No hard error from toggling offline.
7. **Multiplayer** — hard-gates. Create / join surfaces a *connection* error ("Couldn't reach the server"), not the account-less "Sign in first" copy (cross-ref `ONB-10`).
8. **Settings** — reads cached; the account-setup retry banner (if pending) shows "Retrying…" not an error when tapped offline (cross-ref `ONB-6`).

**Expected:** No surface shows a success state for a write that didn't reach the server, no surface hangs, and no read-only surface blocks on the network. Money + multiplayer never proceed; everything else either renders cached or queues with an honest connection-error message.

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

---

## Multiplayer

Multiplayer is the load-bearing feature. These walk the major MP surfaces as device-runnable scenarios. Run them with two real devices (or one device + one bot table) unless a test says otherwise. Architecture primer: [`wiki/multiplayer.md`](./wiki/multiplayer.md).

**Terms** (full glossary in the wiki): **Stack** = chips in front of you at the table; the buy-in returns to your wallet on leave. **Host** = the member who created a private room and taps Start. **Grace** = the 5-min window the server holds a disconnected seat before sweeping it.

**Finish lines vary by path:** joining ends at the lobby, finding-a-game ends at sit-down, playing ends at the post-hand summary or the next-hand prompt.

---

### `MP-1` 🚨 📱 Create a private room + join by code

**State:** two devices, both signed in, online. Device A is the host.

1. Device A: Home → Multiplayer → create a private room. Note the room code shown.
2. Device B: Multiplayer → join by code → enter Device A's code.

**Expected:** Device B lands in the same room's lobby. Both devices show both members in the seat list, each marked connected. The host badge sits on Device A. No spinner stall longer than ~2s after the code is entered.

---

### `MP-2` ⚠️ 📱 Find a public game via matchmaking

**State:** one device, signed in, online, wallet funded above the tier's buy-in.

1. Home → Multiplayer → "Find a game" (public matchmaking).
2. Set a buy-in range that spans at least one open/public table, or let it fall through to the bot fallback.

**Expected:** Either a chooser lists candidate tables (buy-in, seats taken / max, real-human count) and tapping one seats you at that table, or — on an empty result — the search waits and then seats you against bots. Finish line is sit-down: you have a stack at the table. The buy-in is reserved from your wallet (wallet drops by the buy-in, not spent).

- When the bot-fallback offer appears and you've already drawn down some of today's house-funded subsidy, the offer shows a heads-up line naming the remaining bonus chips (or "you've used today's bonus chips" once exhausted). With full headroom no caveat shows. Tapping "Keep waiting" clears the line (MP-6).
- Two devices, same buy-in range (including a tight range that falls between the round stakes, e.g. 3k-4k): Device A searches first and opens a table; Device B searches the same range and is seated *with A* (two members at one table), never stranded on its own empty table (MP-15).

---

### `MP-3` 🚨 📱 Play a hand to showdown

**State:** two seated members at the same table (from `MP-1`), a hand in progress.

1. Play a full hand: post blinds, act in turn (check/call/raise/fold) on both devices through every street.
2. Carry the hand to showdown (both players reach the river without folding).

**Expected:** Each device only ever sees its own hole cards — opponents' cards stay face-down until showdown reveal. Turn passes correctly; the action ring/timer points at the acting seat. At showdown the winning hand is revealed and the pot moves to the winner's stack. The post-hand summary shows the result. No duplicate-card crash, no stuck turn.

---

### `MP-4` ⚠️ 📱 Multi-hand sequence — button rotation

**State:** two seated members, having just finished `MP-3`.

1. From the post-hand summary, tap "Next hand" (or wait for the auto-deal on a server-dealt table).
2. Play three consecutive hands.

**Expected:** The dealer button advances one seat each hand; blinds rotate with it. Stacks carry over hand-to-hand (the winner's stack from hand 1 is its starting stack in hand 2). No re-deal of the same button, no stack reset between hands.

---

### `MP-5` 🚨 📱 Host disconnect + auto-promotion

**State:** three+ seated members, host is Device A, a hand not currently mid-deal.

1. Device A (host): force-quit the app (or enable airplane mode).
2. Observe Devices B and C for ~10s.

**Expected:** Within the grace window the host badge auto-promotes to the first still-connected member. The table keeps playing — the remaining members are not kicked. Device A's seat shows as disconnected (held), not gone. If Device A returns within grace, it reconnects to its seat (see `MP-6`).

---

### `MP-6` 🚨 📱 Reconnect mid-hand

**State:** a member (Device B) seated in an active hand.

1. Device B: toggle airplane mode on for ~5s, then off (stay within the 5-min grace).

**Expected:** The client retries the socket with backoff and reconnects. Device B's view resyncs to the current hand state (its hole cards, the board, whose turn it is, pot + stacks) — no stale snapshot, no "you left" screen. If it was Device B's turn, the turn is still available (the timer accounts for the gap).

---

### `MP-7` ⚠️ 📱 Graceful leave vs. force-quit

**State:** two seated members at a table mid-hand.

1. Variant A (graceful): Device B taps the in-room Leave button → confirms in the leave dialog.
2. Variant B (force-quit): on a fresh hand, Device B force-quits instead.

**Expected:**
- Variant A — Device B's seat is released immediately; its remaining stack returns to its wallet (wallet goes up by the cashed-out stack). Device A sees B leave and the hand continues / settles around the empty seat. Back navigation lands cleanly on Multiplayer with no stuck "Leaving…" state.
- Variant B — Device B's seat shows disconnected and is swept after the grace window (not instantly); the stack still cashes back. Device A's game is not ended by B's drop.
- On a **subsidized** bots-for-chips table (Variant A, mid-hand), the leave-confirm dialog names the exact stack returning to the wallet ("The N chips at your seat are real…") — the number matches the stack shown at the seat, and the wallet rises by that amount after leaving (MP-6).
- After winning a pot on a real-chip table and leaving (Variant A), the Home/wallet balance reflects the credited stack **right away** — without backgrounding and foregrounding the app. No delayed phantom jump appears on the next resume (todo `MP-7`).
- After leaving a real-chip table with chips at your seat (Variant A), a toast confirms the credit on the screen you land on ("N chips from your seat went back to your wallet. New balance: M.") — the amounts match the wallet change. Leaving with nothing credited (lost the stack, or a bots-only practice table) shows no toast (MP-6).

---

### `MP-8` ⚠️ 📱 Bust + re-buy

**State:** a seated member whose stack reaches 0 during a hand.

1. Play a hand to where Device B's stack busts (call/shove and lose).

**Expected:** Device B sees the bust dialog with re-buy options (move another buy-in from wallet, drop to a lower tier, or — if broke — soft-bust protection). Choosing re-buy moves a fresh buy-in wallet → stack and deals B back in on the next hand. Declining leaves the table cleanly. On a subsidized bots-for-chips table, the bust dialog reads "fresh stack on the house" and the chips stay real (cross-ref `MP-6` settlement). Wallet math is correct after the re-buy — no double debit.

- **Heads-up:** while Device B sits on the bust dialog (hasn't rebought), Device A (the winner) taps "next hand". A is not left with a dead button — a notice toasts ("waiting for your opponent to rebuy or leave") instead of the tap silently doing nothing. (Cross-ref `MP-12` for the full match-over countdown + result.)

---

### `MP-9` ⚠️ 📱 Sole opponent leaves a 2-player room — no reconnect storm

**State:** a 2-player room (Device A + Device B), then Device B leaves so Device A is the only human left.

1. Device B: leave (or force-quit) the room.
2. Device A: stay on the play screen and watch for ~30s.

**Expected:** Device A does not wedge into a tight connect→reconnect loop. If the socket half-opens and keeps dropping, reconnect attempts back off and the attempt counter climbs (1, 2, 3…), then after a bounded number of failures the screen lands on a terminal "lost connection / leave" state instead of looping forever. No rapid-fire "Room socket connected / reconnecting (attempt=1)" churn in the session log. (Covers todo MP-8.)

---

### `MP-10` ⚠️ 📱 Lobby Leave button leaves and navigates

**State:** two devices in the same private room's lobby (from `MP-1`), pre-deal.

1. Device B: tap the in-room "Leave room" button (not the top back arrow). Confirm in the leave dialog.
2. Repeat on a second attempt from a fresh join.

**Expected:** The Leave button behaves identically to the top back arrow every time — it shows the leave-confirm dialog, then notifies the server and navigates back out of the lobby. Device B never stays stranded on the now-empty lobby. Device A sees B drop from the seat list. (Covers todo ROOM-2.)

- Each lobby seat shows the correct member. The local player's own seat carries a "you" caption under their name; no other seat does. The host badge is unaffected — a host who is also you shows both HOST and the "you" caption. (Covers todo ROOM-3.)

---

### `MP-11` ℹ️ 📱 Lobby never flashes a "$0" buy-in

**State:** two devices; Device A creates a private room with a real buy-in, Device B joins by code.

1. Device A: create a room (any tier).
2. Device B: join by code and watch the lobby as it loads.

**Expected:** Neither device ever shows a "$0" buy-in or "0 / 0" blinds in the lobby. The stakes/buy-in row either shows the real values or is absent while the room snapshot is still hydrating — it never renders a zero. (Covers todo MP-16, partial — the underlying zero-snapshot source is still being pinned.)

---

### `MP-12` ⚠️ 📱 Heads-up match-over countdown + result

**State:** a 2-player (heads-up) real-chips room, a hand that busts Device B to 0.

1. Play a hand to where Device B busts heads-up (B's stack hits 0, A wins the pot).
2. Both devices: watch the table without acting.
3. **Rebuy path** — Device B taps "Rebuy now" on the countdown banner before it expires.
4. **Expiry path** — replay the bust and let the countdown run to 0 without rebuying.

**Expected:**
- Both devices show a live countdown ticking down (~60s). Device B (busted) sees "Rebuy in N or lose your seat" + a Rebuy CTA; Device A (winner) sees "Opponent busted. Auto-continues in N".
- **Rebuy path:** B's rebuy clears the countdown on both devices and play resumes — A is not routed off, no result screen shows.
- **Expiry path:** when the window expires, A sees a "You won the match" result (not a silent pop) and B sees "Match over"; tapping Done routes each off the dead table cleanly. A's wallet reflects the cashed-out stack on the surface they land on. (Covers todo MP-14.)

---

## Profile & items

### `PROF-1` ℹ️ 📱 Default felt + card back show as equipped on a fresh account

**State:** a brand-new account that has never opened the shop or equipped anything (reach via `ONB-1` / `ONB-2`).

1. Open Profile → My Items.
2. Find the default felt and the default card back tiles.
3. Equip a non-default felt (if one is owned), then re-check the default felt and the default card back.

**Expected:** On the fresh account both the default felt and default card back render the "Equipped" badge even though the user never explicitly equipped them. Equipping a non-default felt moves the badge off the default felt; the default card back keeps its badge because its slot is untouched. (Covers todo ITEM-1.)

### `PROF-2` ℹ️ 📱 Table speed setting scales the deal/reveal animations

**State:** any account; Settings → Gameplay shows the "Table speed" picker (Normal / Fast / Instant).

1. Set Table speed to **Normal**, start a bot game, deal a hand and watch the flop/turn/river.
2. Back out, set Table speed to **Fast**, start a new hand — the same cards should fly in and flip noticeably quicker.
3. Set Table speed to **Instant**, start a new hand and reach showdown.

**Expected:** Normal plays the calibrated pacing. Fast roughly halves the deal-in and reveal timing. Instant snaps hole cards and community cards straight to settled face-up with no fly/flip animation — action resolves immediately. The setting persists across app restarts and applies on both bot and multiplayer tables. (Covers todo GAME-6.)
