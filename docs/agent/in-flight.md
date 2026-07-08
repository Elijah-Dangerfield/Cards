# In-flight log

Per-commit handoff notes for tonight's cycle. The reviewer reads these when writing the PR, then deletes the file.

## fix(room): stop the achievement celebration pager resizing between pages (PROG-10)

**Problem:** The multi-achievement celebration pager was jumpy while swiping — pages differ in height (mystery vs revealed cards, descriptions, cosmetic rows), so the sheet abruptly resized between pages (owner request 2026-07-08).
**Approach:** Uniform size, per the owner's first-listed option: `beyondViewportPageCount = earned.size` keeps every page composed so the pager holds the tallest card's height for the whole celebration (unlock counts are small, so precomposing all pages is cheap), plus `animateContentSize()` on the pager and card so the one remaining height change (a tapped mystery card revealing taller content) animates instead of snapping. Rejected animate-only: mid-swipe the pager still visibly stretches/shrinks as adjacent pages compose in and out.
**Reviewer notes:** Precomposing all pages means each card's pop-in (fade + scale) plays at sheet entrance rather than as its page swipes into view; the tap-to-reveal mystery still paces the celebration, and the reveal sequence stays gated on tap (no early haptics). No test — pure animation behavior; QA sub-bullet added under PROG-9's entry.

## fix(server): apply the never-delete-progress guards to the scheduled anon sweep (AUTH-18)

**Problem:** `DefaultOrphanAnonymousSweep` deleted every anon account older than the TTL unconditionally — the hard guards the wiki promises (no IAP spend, no meaningful XP, no active room seat) only existed on the install sweep, so wiring the sweep cron would have wiped idle-but-progressed accounts.
**Approach:** Extracted the guards into a shared `OrphanCandidateVerifier` used by both sweeps (single source of truth; returns a `SkipReason` for the log line), and added `WalletRepository.hasIapSpend` (`reason LIKE 'iap.%'`) since the TTL sweep has no SQL gate. `SweepResult` + the admin response gained a `skipped` count. Rejected duplicating the checks per sweep — that drift is exactly what caused this gap. The install sweep now re-checks IAP despite its SQL pre-filter (belt and suspenders on the paying-account floor).
**Reviewer notes:** All 7 `OrphanCandidateVerifierTest` guards + 4 new anon-sweep skip tests + a real-Postgres `hasIapSpend` test are green (`:apps:server:test`, testcontainers ran). Decision logged in `docs/decisions.md` 2026-07-08. Six test fakes gained a `hasIapSpend = false` override.

## fix(onboarding): keep the PickIdentity header footprint on the OAuth path (AUTH-17)

**Problem:** Onboarding via Google (identity already claimed) hid the back button and its spacers, collapsing the PickIdentity header 56dp — the host's "step N of N" overlay chip landed on the avatar and the layout read as broken/title-missing (feedback case cdcfbae0290e471a8bde94cc5b58dc1f).
**Approach:** The header band now always reserves the back affordance's footprint (a Box sized from the same `IconSize`/`IconButton.Size.padding` tokens the button uses); the button renders inside it only when the identity isn't claimed. Rejected moving the step chip out of the overlay into a host header row — it would reflow every onboarding step for a divergence specific to this one.
**Reviewer notes:** Red-first Compose UI test (`OnboardingScreenTest`, new androidUnitTest source set for the module mirroring `features/room/impl`) pins guest and post-OAuth title positions equal and the chip above the content. The reserved height derives from the IconButton tokens — if the button's default size ever changes, the band follows it.

## fix(room): reveal the showdown before the bust dialog in solo play (GAME-18)

**Problem:** Busting at showdown against bots popped `BustDialog` straight over the table, so the player never saw the hand they lost to — the card reveal played under the scrim (feedback case 53e19e0438c84ebda67ad88133e8f79d).
**Approach:** Sequenced the existing `ShowdownDialog` first (CTA swapped to "Continue" via a new `ctaText` param), then the bust dialog on acknowledge; the bust dialog drops the XP/achievement rows when the reveal already showed them. Rejected the alternative of embedding the result inside `BustDialog` — it would duplicate the showdown layout and crowd the recovery moment. Fold-out busts (nothing to reveal) go straight to the bust dialog.
**Reviewer notes:** Red-first Compose UI test in `PlayPokerScreenTest` (`soloBustAtShowdown_showsRevealBeforeBustDialog`) reproduces the bug, plus a fold-out guard test. Real-MP busts keep `MultiplayerBustDialog` untouched — the reveal-first sequencing there needs the rebuy-grace countdown thought through.
**Deferred:** Real-multiplayer bust path still replaces the showdown reveal with `MultiplayerBustDialog`; same UX gap in principle but interacts with the rebuy-grace countdown — nothing filed yet, reviewer please triage.
