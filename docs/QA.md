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

- Landing layout (AUTH-10): on the welcome page the bottom actions are clearly spaced — the guest CTA, the provider buttons, the "Sign in" link, and the Terms/Privacy footnote each read as separate, comfortably tappable, with a hairline divider above the legal footnote so "Sign in" is no longer jammed against it. The Apple and Google buttons render their dark brand variants (dark fill) rather than white slabs on the dark page.

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
- Variant B header (AUTH-17): the post-Google PickIdentity step renders like the guest path — "This is you" title visible, "Step 1 of 3" chip sitting in clear space above the avatar, never floating on top of the content. (Only the back arrow is absent, since the identity is already claimed.)

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
- If the offline cold boot never resolved a session (guest session couldn't refresh), a route that requires an account (create/join room) shows the **"You're offline"** gate sheet — "Your progress is safe. Try again once you're back online." — not the "Account needed" sheet. Reconnect and retry the same action; the account resolves and the route opens. *(AUTH-11.)*

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

### `ONB-17` 🚨 📱 Guest claims their account with Google, claim prompts clear (AUTH-12)

**State:** signed in as a guest (played a few hands as "Continue as guest"), online.

1. Open the "Save your progress" nudge (Profile, or the rank-detail "Claim your account" sheet).
2. Tap "Continue with Google" and complete Google's sheet.
3. Return to the app.

**Expected:** The claim reports success and, with no app restart, every "sign in and claim your account" / "Save your progress" prompt disappears — the account now reads as claimed everywhere. Progress (chips, XP, profile) carries over unchanged. Force-quit and relaunch: still claimed, no prompts return.

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

- On Device B, enter a 6-character code that is not a real room and tap Join. A "room not found" error appears in place under the code field; the screen does **not** move, navigate, or recompose — the input + keyboard stay put. Editing a character clears the error. (Covers todo ROOM-5.)
- Create the room on Device A with a non-zero buy-in. After Device B joins, both devices' lobby show the real buy-in (and matching blinds) — never $0 — and it stays correct as the second seat fills in. (Covers MP-24.)
- On a fresh account (10,000-chip grant), the create-table screen opens with the buy-in pre-set to 1,000 (blinds 5 / 10), not 5,000 — a sensible ~10% of the bankroll for a first-time host. The slider still drags up to the full balance. (Covers ROOM-13.)
- On the create-table screen, the avatar next to the room name is your own profile avatar (the emoji + color picked in Edit Profile), not a placeholder fox.

---

### `MP-1B` ⚠️ 📱 Share an invite link and deep-link join

**State:** two devices, both signed in, online. Device A is in a room's lobby (any visibility — private, open, or public). (Covers todo ROOM-7.)

1. Device A: in the lobby, tap "Share invite". Send the message (e.g. via Messages / Notes) to Device B.
2. Device B: open the shared message and tap the `cards://join/...` link.

**Expected:** The shared text reads naturally and contains the room code plus a `cards://join/CODE` link. Tapping it on Device B opens the app straight into that room's lobby (code pre-filled, auto-joins), no manual code entry. Both devices then show both members seated. The "Copy code" button still copies the bare code for paste-anywhere use.

---

### `MP-2` ⚠️ 📱 Find a public game via matchmaking

**State:** one device, signed in, online, wallet funded above the tier's buy-in.

1. Home → Multiplayer → "Find a game" (public matchmaking).
2. Set a buy-in range that spans at least one open/public table, or let it fall through to the bot fallback.

**Expected:** Either a chooser lists candidate tables (buy-in, seats taken / max, real-human count) and tapping one seats you at that table, or — on an empty result — the search waits and then seats you against bots. Finish line is sit-down: you have a stack at the table. The buy-in is reserved from your wallet (wallet drops by the buy-in, not spent).

- When the bot-fallback offer appears and you've already drawn down some of today's house-funded subsidy, the offer shows a heads-up line naming the remaining bonus chips (or "you've used today's bonus chips" once exhausted). With full headroom no caveat shows. Tapping "Keep waiting" clears the line (MP-6).
- The no-results state reads as a calm centered message ("We couldn't find anyone right now" + a supportive line), not a promotional banner. "Keep waiting for players" is the primary action; "Play bots for real chips" sits below it as a secondary offer; "Try again later" is the quiet exit. The subsidy disclosure (above) stays legible in this treatment (ROOM-9).
- Two devices, same buy-in range (including a tight range that falls between the round stakes, e.g. 3k-4k): Device A searches first and opens a table; Device B searches the same range and is seated *with A* (two members at one table), never stranded on its own empty table (MP-15).
- Staggered start (ROOM-12): Device A searches and falls through to its own waiting table (no candidates yet). A few seconds later Device B starts a search in the same range. A must still discover B's table while waiting and the two end up at one table — neither sits alone forever. (The older of the two tables wins, so exactly one device migrates.)
- Joined-table lobby (ROOM-11): when the chooser lists candidates and you tap Join on one, you land on a distinct joined-table screen ("You're in") showing the seat grid with the seated players and a "waiting for more players" / "dealing you in" line — NOT the spinning radar. Once a hand deals you go straight to the live table. (Falling through to the genuine wait, with no candidate picked, still shows the radar.)

---

### `MP-3` 🚨 📱 Play a hand to showdown

**State:** two seated members at the same table (from `MP-1`), a hand in progress.

1. Play a full hand: post blinds, act in turn (check/call/raise/fold) on both devices through every street.
2. Carry the hand to showdown (both players reach the river without folding).

**Expected:** Each device only ever sees its own hole cards — opponents' cards stay face-down until showdown reveal. Turn passes correctly; the action ring/timer points at the acting seat. At showdown the winning hand is revealed and the pot moves to the winner's stack. The post-hand summary shows the result. No duplicate-card crash, no stuck turn.

- **XP + stats credit (PROG-4):** finishing the hand awards XP (the Home/Profile XP total rises, and the hand-end XP burst shows) and advances player-stats — same as a solo bots hand, just at the full MULTIPLAYER multiplier on an all-human table. A finished MP hand must never silently award zero XP.
- **Showdown reveal survives a reconnect blip (MP-25):** carry a multiway hand to a river showdown, then background/foreground one device right as the hand resolves (so its socket reconnects on the Complete state). On resume that device must STILL show the opponents' revealed hole cards for the just-finished hand — the showdown isn't skipped just because the device missed the live hand-end event. Opponents who folded earlier stay mucked (no cards shown).
- **Opponent times out / folds preflop (MP-26):** heads-up, let the opponent's 30s turn timer run out preflop (or have them fold) so they never act. The non-acting player (the BB) must NOT be left on a frozen board — they see the hand result (winner takes the pot) and a Next Hand path, not a dead table with no acting seat and no winner. Works even though that device only ever received the terminal Complete snapshot.

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

- **Stale-tap after reconnect (MP-22):** background Device B right as a hand is resolving, then foreground and tap "Next hand" on the (briefly stale) summary before the resync lands. The tap must NOT show the terminal "waiting for your opponent to rebuy or leave" toast — both stacks are healthy, nobody busted. Either the table catches up silently and advances, or a brief "Catching up with the table, try that again in a moment" hint shows. The rebuy toast only ever appears when the opponent actually busted with no rebuy (cross-ref `MP-8`).

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
- **Back-button / iOS swipe leave (MP-23):** the same immediate reconcile holds when leaving via the top back-arrow, the Android system back, AND the iOS edge-swipe-back gesture — not only the in-room Leave button. After winning a pot, leave a real-chip table via the iOS swipe-back: the wallet shows the settled balance on the screen you land on, without backgrounding/foregrounding. No double credit if you confirm a leave dialog and the gesture both fire.
- After leaving a real-chip table with chips at your seat (Variant A), a toast confirms the credit on the screen you land on ("N chips from your seat went back to your wallet. New balance: M.") — the amounts match the wallet change. Leaving with nothing credited (lost the stack, or a bots-only practice table) shows no toast (MP-6).
- **Net-settle preview (ROOM-4):** on a real-chip table, the leave-confirm dialog states the *net* this leave settles to the wallet (stack minus buy-in, shown as e.g. "+1,250" up or red when down). If you have a posted blind / chips already in the live hand, a sub-note calls out the amount you forfeit by leaving now. A practice / solo table shows no settle line. The net shown matches the wallet change after leaving.
- **Reconciling affordance (MP-30):** during the brief window after a game/leave while the wallet sync is in flight, the Home header and Shop wallet render the chip badge as *updating* — the number dims and a small spinner sits beside it — then snaps back to full-strength once the authoritative balance confirms. A settled, idle wallet never shows the spinner. (Easiest to see on a slow connection.)
- **Synchronous leave cash-out (MP-29):** the balance shown after leaving must be correct on the *first* screen you land on even on a slow / flaky connection — the leave call itself settles the stack and returns the balance, so there's no window where the buy-in still reads as escrowed. Leave a real-chip table right after a hand, ideally with the network throttled: the wallet reflects the settled amount immediately, and a repeated back-tap (dead-button guard) never double-credits.

---

### `MP-8` ⚠️ 📱 Bust + re-buy

**State:** a seated member whose stack reaches 0 during a hand.

1. Play a hand to where Device B's stack busts (call/shove and lose).

**Expected:** Device B sees the bust dialog with re-buy options (move another buy-in from wallet, drop to a lower tier, or — if broke — soft-bust protection). Choosing re-buy moves a fresh buy-in wallet → stack and deals B back in on the next hand. Declining leaves the table cleanly. On a subsidized bots-for-chips table, the bust dialog reads "fresh stack on the house" and the chips stay real (cross-ref `MP-6` settlement). Wallet math is correct after the re-buy — no double debit.

- **Heads-up (MP-22):** while Device B sits on the bust dialog (hasn't rebought), Device A (the winner) is NOT offered a tappable "Next hand" at all — A sees the shared rebuy-grace countdown ("Opponent busted. Auto-continues in N") instead of a button that can only be refused. If A somehow does fire a next-hand request, the refusal toasts "waiting for your opponent to rebuy or leave" rather than silently doing nothing. (Cross-ref `MP-12` for the full match-over countdown + result.)

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

### `MP-13` ℹ️ 📱 Unparseable game frame shows "update may help", not a freeze

**State:** an in-progress MP table on a build whose game-frame shape is intentionally older than what the server sends (or simulate a server frame with a required field removed). Hard to reproduce without a deliberately-mismatched build — verify opportunistically or via the unit coverage (`ReconnectingRoomSocketTest`).

1. Be seated at a live table receiving game frames.
2. Have the server send (or replay) a game frame the client can't deserialize.

**Expected:** The app does not crash, hang on "dealing in", or sit on a frozen table. A clear message appears ("We're struggling to play this game. It may have been created with a newer app version. Updating may help") and the player is routed off the dead table to a safe place (lobby for a private game, Home/Find for a public one). (Covers todo ENG-7.)

---

### `MP-14` ⚠️ 📱 Host's picked felt + card back show on every player's table

**State:** two devices, both signed in, online. Device A (the host) owns a non-default felt and a non-default card back (buy/earn them first). Device B has different (or default) cosmetics. (Covers todo SHOP-3 + SHOP-5.)

1. Device A: on the create-room screen, the "Table felt" + "Card back" rows show only cosmetics the host owns (defaults always present), pre-selected to the host's equipped look. Pick a non-default felt B and card back B (different from what's equipped).
2. Create the private room, then add a bot or have Device B join.
3. Play a hand so both devices see the felt + opponents' card backs.

**Expected:** The picker only ever lists owned cosmetics. Both devices render the felt + card back the host *picked* (B) — Device B sees Device A's picked look, not its own. Explicitly picking the Default felt/card back forces the plain default for the whole table. The look pins at create time: swapping cosmetics in My Items afterward doesn't change the live room.

---

## Profile & items

### `PROF-1` ℹ️ 📱 Default felt + card back show as equipped on a fresh account

**State:** a brand-new account that has never opened the shop or equipped anything (reach via `ONB-1` / `ONB-2`).

1. Open Profile → My Items.
2. Find the default felt and the default card back tiles.
3. Equip a non-default felt (if one is owned), then re-check the default felt and the default card back.

**Expected:** On the fresh account both the default felt and default card back render the "Equipped" badge even though the user never explicitly equipped them. Equipping a non-default felt moves the badge off the default felt; the default card back keeps its badge because its slot is untouched. (Covers todo ITEM-1.)

- Acquisition line (SHOP-4): tapping the default felt or default card back opens its detail sheet with **no** "Earned"/"Bought … ago" line — they're granted at account creation, not earned. A genuinely earned or purchased cosmetic still shows its acquisition line.
- Shelf start-alignment (SHOP-6): the first tile of every cosmetic shelf (card backs, felts, emotes/avatars) starts at the same left inset — flush under its section header. The card-back shelf's first tile lines up with the felt and emote shelves' first tiles rather than sitting further in.

### `PROF-2` ℹ️ 📱 Game speed setting paces bot think time only

**State:** any account; Settings → Gameplay shows the "Game speed" picker (Normal / Fast).

1. Set Game speed to **Normal**, start a bot game and play a few turns — bots pause noticeably before acting.
2. Back out, set Game speed to **Fast**, start a new hand — bots act after roughly half the pause (a short floor remains so moves never read as a glitch).

**Expected:** Fast trims only the bots' deliberation pause. Deal, flip, and reveal animations play at the same calibrated pace on both settings — Game speed never changes animation timing (the old animation-scaling "Instant" tier was removed 2026-06-29). The setting persists across app restarts.

## Solo play

### `GAME-17` ℹ️ 📱 An instant bot fold reads clearly on the seat and player card

**State:** any account; start a bot game with 3+ opponents so a bot acts before you do.

1. Deal hands until the first-to-act bot folds before your first turn (a few hands at most).
2. Watch the folding bot's seat, then tap its avatar to open the player card.
3. Play on to the flop and tap the folded bot again.

**Expected:** The opening bot action never lands before the deal settles (~1s grace after the cards fly in). The fold announces itself: a FOLD pill pops in under the greyed avatar and stays for the rest of the hand, through showdown. The player card's "Last Move" row reads "Folded" — including after later streets have dealt. Never just an unexplained pair of grey cards. (Covers todo GAME-17.)

---

### `GAME-18` ⚠️ 📱 Busting at showdown shows the hand you lost to before the bust dialog

**State:** any account in a solo bot game.

1. Shove all-in and lose at showdown so your stack hits 0.
2. Watch what appears when the hand resolves.
3. Tap "Continue" on the showdown summary.

**Expected:** The full showdown summary shows first — board, revealed hole cards, the winner's hand — with a "Continue" CTA instead of "Next hand". Tapping it presents the "You went bust" dialog with the "Deal me in" recovery. The reveal never plays half-hidden under the bust dialog's scrim. Busting to a fold-out (no showdown) skips straight to the bust dialog. (Covers todo GAME-18.)

## Progression

### `PROG-1` ⚠️ 📱 Level-up celebration shows on a fresh account's first level-up

**State:** a brand-new account (reach via `ONB-1` / `ONB-2`) that has not yet leveled up — go straight from onboarding into a bot game without visiting other screens first.

1. Play bot hands until you earn enough XP to cross into the next level (the header LevelPill ring fills and rolls over).
2. Finish the hand and let the table return to Home (tap back / Next hand through to Home).
3. Repeat once more for a second level-up later in the session.

**Expected:** The full-screen level-up celebration presents every time a level is crossed — including the very first level-up of a fresh session — with the correct level number and any chip/boost/cosmetic reward rows. It never silently drops the user back to Home with no fanfare. A multi-level jump shows a single celebration for the net level. (Covers todo PROG-3 + PROG-5; the reward granter anchors the celebration watermark, and the Home notification arbiter presents the crossing once Home is settled so it can't be swept away before it plays.)

---

### `PROG-9` ℹ️ 📱 Multi-achievement celebration pages horizontally

**State:** a fresh account (earns several achievements fast) in a bot game.

1. Play the first hand or two until a hand ends having earned 2+ achievements at once (first hand of poker typically stacks a few).
2. On the celebration sheet, watch the first medallion reveal, then swipe left through the remaining pages.

**Expected:** Each achievement gets its own full-width page in a horizontal pager with a dot indicator underneath (active dot stretches and follows the swipe). The first page auto-reveals with confetti; later pages stay a "?" mystery until tapped. A single-achievement unlock shows one card with no pager chrome or dots. (Covers todo PROG-9.)

- Pager sizing (PROG-10): swiping between cards of different heights never jumps or abruptly resizes the sheet — it holds the tallest card's height — and tapping a mystery card grows it smoothly, not with a snap.

---

### `PROG-6` ⚠️ 📱 Play-style unlock celebration fires once at ~20 hands

**State:** an account whose play-style is not yet unlocked (fewer than 20 recorded hands — reach via a fresh account, or check Stats shows the "keep playing to reveal your style" state).

1. Play bot hands until you have played roughly 20 hands (the play-style sample threshold).
2. Return to Home and let it settle.

**Expected:** A one-shot "your play style is unlocked" dialog appears exactly once while you are settled on Home, with a "See my style" CTA that routes to Stats (where the radar now shows a shape) and a "Later" that dismisses. It does not re-appear on later Home visits or after backgrounding. If a level-up is also pending, the level-up celebration shows first (higher priority) and the play-style dialog follows on a later Home settle. (Covers todo PROG-6, routed through the PROG-5 Home notification arbiter.)

---

### `PROG-11` 🚨 📱 Earned chips survive a kill + relaunch (and refused spends say so)

**State:** a signed-in account on Home with a settled chip balance.

1. Play bot hands until an achievement or level-up grants chips; note the new Home balance.
2. Immediately force-quit the app (before any manual sync), then relaunch and let Home settle.
3. Optional (needs a network tool or airplane-mode timing): queue a shop spend while offline that the server will refuse (balance already spent elsewhere / on another device), then reconnect and let the wallet sync run.

**Expected:** After the relaunch the balance still includes the grant — earned chips never vanish and then "come back" on a later sync (the display is server snapshot + pending events, and relaunch triggers a sync). In the refused-spend case, the balance corrects to the server's value AND an error snackbar ("A purchase didn't go through, so we put your chips back.") explains it — never a silent balance change. (Covers todo PROG-11 + the ENG-20 relaunch-sync trigger; the 2026-07-09 vanishing-chips incident is the regression this guards.)

---

## Billing & IAP

### `BILL-4` 🚨 🍎 iOS chip-pack purchase via StoreKit (local `.storekit` config)

**State:** a **release** iOS build (the real StoreKit client only binds in release; debug uses the fake), running against the bundled `apps/ios/iosApp/Cards.storekit` test config attached to the run scheme's StoreKit Configuration. Signed in with a **claimed** (non-anonymous) account. Note the chip balance.

1. Shop → tap a real-money chip pack (e.g. Medium). The native StoreKit purchase sheet appears with the right localized price.
2. Confirm the purchase in the sheet.
3. Watch the chip balance and the purchase confirmation.
4. Buy the **same** pack a second time.

**Expected:** Real prices come from StoreKit (not the fallback). After confirming, the chips are credited once and the balance updates. The second purchase of the same pack succeeds again (consumable was finished — no "already purchased" dead end). Cancelling the sheet returns silently with no credit and no error toast. With `billing.realPurchasesEnabled` on, the balance reflects the server-returned authoritative total (no local double-credit). The account token pins to the signed-in user (a mismatched receipt is rejected server-side). Anonymous accounts hard-gate before the sheet ever opens.

---

## Tooling & debug

### `GAME-11` ⚠️ 📱 Shake feedback surface sits above an open bottom sheet

**State:** any screen that opens a bottom sheet (e.g. the shop product sheet, or a lobby picker). Debug build so the shake surface is reachable.

1. Open a bottom sheet so it's expanded on screen.
2. With the sheet still up, shake the device to bring up the "sun" feedback dialog (and, from it, tap through to the feedback form).

**Expected:** The shake dialog — and the feedback form it opens — render fully on top of the bottom sheet and its scrim, never behind it. Tapping the dialog scrim / back dismisses the dialog and returns to the sheet. (Covers todo GAME-11.)

### `ENG-8` ℹ️ 📱 Wiretap captures the gameplay WebSocket

**State:** a debug build (the inspector is debug-only; on iOS the framework must be built with `cards.wiretap.ios` left on). Be in or about to start a multiplayer game.

1. Join/host an MP table so the gameplay room socket connects, and play a hand (act, see opponents act).
2. Shake the device to open the Network inspector; find the WebSocket / sockets tab.

**Expected:** The gameplay socket connection appears in the inspector (URL `…/v1/rooms/<code>/socket`) alongside the HTTP calls, listing its inbound frames (game_state snapshots, game_event, intent_ack, emoji) and outbound frames (your submitted intents, next-hand requests), plus the connect and the close/error when you leave. A release build never shows the inspector. (Covers todo ENG-8.)
