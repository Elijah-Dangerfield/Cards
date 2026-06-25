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
