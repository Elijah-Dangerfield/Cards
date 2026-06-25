# In-flight

## feat(rooms): confirm credited stack on leaving a real-chip table (MP-6)

**Problem:** A player who wins on a real-chip / subsidized MP table and leaves
gets their seat stack cashed back to the wallet server-side, but the client only
learns the new number on the next sync — the balance bump reads as an
unexplained glitch (Sentry CARDS-2N / 2Y). The leave path had the data after
`chipsRepository.sync()` but never surfaced it.

**Approach:** `PlayPokerViewModel.leaveAndReconcileWallet` already syncs the
wallet on an MP leave (MP-7). It now captures the balance before/after that sync
and, on a real gain, hands the delta + new balance to a new injected
`LeaveCashOutNotifier` seam. The default impl composes the localized message and
fires the global `showSnackBar` (host-agnostic, so it survives this VM's
teardown and lands on Home; `delayBy` lets that host mount first). I pulled the
rendering into a DI seam rather than inlining it because the message uses
`getString` — a suspend resource read that throws in Android JVM unit tests
("Resources not mocked") — so inlining would have made the credited-amount
decision untestable. The VM keeps the testable decision; a fake notifier records
the call. Rejected alternative: emitting a `PlayPokerEvent` for the entry point
to toast — the leave fires `onBack()` immediately after `LeaveTable`, tearing
down the entry-point event collector before the async sync resolves, so that
path is dead by the time the credited amount is known.

**Reviewer notes:** The 600ms `delayBy` in `SnackbarLeaveCashOutNotifier` is a
judgement call to clear the leave→Home nav transition before the toast presents;
worth an eyeball on-device that it lands on Home and not mid-transition. The
credited amount is derived from the local balance delta around the sync (not a
server-reported `refunded` field), which is correct as long as no other wallet
mutation races the leave sync — the single-flight mutex on `sync()` plus the
fact that leaving tears down the table makes a concurrent credit unlikely, but
it's a delta, not an authoritative line item.

## test(mp): FakeRoomServer turn-cycle harness for the integration tier (MP-2)

**Problem:** The MP test harness could only hand-feed canned server frames
(`FakeRoomConnectionHandle` replays whatever the test pushes). Nothing exercised
a *full* deal → act → settle cycle through the client session without booting
Ktor — the second MP-2 sub-item.

**Approach:** Added `FakeRoomServer` (test source set, `:features:room:impl`
commonTest) — a `RoomConnectionHandle` that drives a **real** `GameEngine` and
fans `StateSnapshot` → `Event` → `IntentAck` back out in the exact wire order
`RoomSocketRoutes.handleClientFrame` uses. It mirrors the server `GameSession`'s
seat-building, button rotation, and actor/turn/nonce gates, but stays in the
test set so it pulls in no server (Exposed/Ktor/OTel) deps. **Directional call:**
the server's `GameSession` is app-local to `:apps:server` and can't be reached
from a feature test, so rather than add a `:apps:server` test dependency (heavy,
and it'd drag the whole server graph into a UI-feature test), the fake drives the
same `GameEngine` the session wraps — the engine *is* the authority, so the fake
reproduces server behaviour without the server's I/O. Opponent seats are
auto-played by an injectable `OpponentPolicy` (default check-or-call) so the
client under test only ever submits its own seat, mirroring the production bot
driver. `mpScenario().withServer(occupants)` opts in; `serverStartHand()` /
`iSubmit()` drive it. Four tests cover deal, fold-out (single winner by fold),
call-down-to-showdown (engine awards the better hand), and the submit-reaches-
wire assertion.

**Reviewer notes:** The fake re-implements the seat/button/nonce logic rather
than sharing the server's — a deliberate duplication (the source lives in
`:apps:server`, off-limits to a feature test). If that logic drifts server-side,
this fake won't catch it; it's a client-session harness, not a server-contract
test. `Rebuy` is acked-as-rejected (out of scope for turn cycles); emoji is
attributed to the local seat. The deck is seeded `Random(42)` unless a
`deckFactory`/`stackedDeck` is supplied.

## feat(onboarding): one-time account-setup explainer dialog (AUTH-1)

**Problem:** AUTH-1 sub-part (1) — when guest-account creation is left pending
(signed up offline / network blip) the only surface is the thin
`AccountSetupBanner`, which is easy to miss on first contact and doesn't explain
that play is safe or what's paused.

**Approach:** Added `AccountSetupExplainerDialog` (DS `Dialog`, single "Got it"
CTA) hosted as a top-level overlay in `App.kt`, gated by a new pure
`shouldShowAccountSetupExplainer(pending, hasSeenExplainer)` off the existing
`rememberAccountSetupStatus` live status + a new device-scoped
`AppData.accountSetupExplainerSeen` flag (mirrors `tutorialBannerDismissed` —
not in `resetAccountScoped`; the pending state only arises during initial
creation, so once-per-device is the right scope). Dismissing flips the flag so
the dialog shows exactly once and the thin banner takes over thereafter. The
gate is extracted as a pure fn so the show/suppress decision is unit-tested
without Compose. Exposed `AppCache` on `AppComponent` to read/write the flag.
**Directional call:** used the plain 3-arg `Dialog(title, description,
primaryButtonText)` (no emoji bubble) — focused/calm reads better for a
reassurance dialog than a celebratory accessory; reviewer can add `topAccessory`
via the slotted overload if they want more warmth. Shipped sub-part (1) only;
rewrote the AUTH-1 bullet to the remaining sub-part (2) (device-verify banner
copy + placement), which needs Studio to eyeball placement.

**Reviewer notes:** The explainer only fires after the *first* creation attempt
has Failed (that's what `rememberAccountSetupStatus.pending` requires) — i.e. it
won't flash during the happy Idle→InProgress→Succeeded path, only on a genuine
degraded/offline signup. Couldn't run `:apps:compose:testDebugUnitTest` at first
because stale KSP output from a deleted/uncommitted `uitest.harness` UI-test
experiment under `build/generated/ksp/android/androidUnitTestDebug/` referenced
missing `TestAppComponent`/`TestProfileRepository` types; a `rm -rf` of that
stale dir cleared it and the module's android unit tests (incl. the new one) go
green. That stale-artifact / abandoned-harness situation is filed in backlog —
worth a look since it'll bite the next worker who runs that test task on a dirty
build dir.

**Deferred:** Found an abandoned/incomplete Compose UI-test harness
(`com.cards.uitest.harness`, `TestAppComponent` etc.) that exists only as stale
generated KSP output with no committed sources — likely a prior MP-2 UI-test
attempt. Filed in `docs/backlog.md` for the reviewer to triage against MP-2.
